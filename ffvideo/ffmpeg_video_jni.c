/*
 * Software video decoding through FFmpeg's libavcodec for FfmpegVideoDecoder.
 *
 * Shaped after media3's vpx_jni.cc: the decoder thread sends packets and takes frames, the playback
 * thread either copies a frame into a VideoDecoderOutputBuffer (YUV mode, for a GL renderer) or keeps
 * it referenced until it is drawn into the Surface as YV12 (surface mode, the one a SurfaceView gets).
 *
 * Frames come out of libavcodec when it has them, not one per packet: B-frames and frame threading
 * hold some back. They are queued here and handed out one per decode call, which is the rhythm
 * SimpleDecoder works in; a call that has none to give produces a skipped output buffer.
 */
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>

#define LOG_TAG "ffmpegvideo_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define FUNC(RETURN_TYPE, NAME, ...)                                           \
  JNIEXPORT RETURN_TYPE JNICALL Java_com_brouken_player_FfmpegVideoDecoder_##NAME( \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)
#define SFUNC(RETURN_TYPE, NAME, ...)                                          \
  JNIEXPORT RETURN_TYPE JNICALL Java_com_brouken_player_FfmpegVideoDecoder_##NAME( \
      JNIEnv* env, jclass clazz, ##__VA_ARGS__)

/* C.VIDEO_OUTPUT_MODE_YUV and _SURFACE_YUV. */
#define OUTPUT_MODE_YUV 0
#define OUTPUT_MODE_SURFACE_YUV 1
/* VideoDecoderOutputBuffer.COLORSPACE_*. */
#define COLORSPACE_UNKNOWN 0
#define COLORSPACE_BT601 1
#define COLORSPACE_BT709 2
#define COLORSPACE_BT2020 3
/* HAL_PIXEL_FORMAT_YV12: Y, then V, then U, chroma stride aligned to 16. */
#define IMAGE_FORMAT_YV12 0x32315659

/* Frames waiting for a decode call to hand them out. */
#define QUEUE_SIZE 16
/* Frames handed out in surface mode and not yet released. Output buffers are few (renderer's own
 * count), so this only has to be larger than that. */
#define SLOT_COUNT 32
/* decoderPrivate of a buffer that holds no slot. */
#define NO_SLOT (-1)

static jmethodID initForYuvFrame;
static jmethodID initForPrivateFrame;
static jfieldID dataField;
static jfieldID decoderPrivateField;
static jfieldID timeUsField;

typedef struct {
  AVCodecContext* codec;
  AVPacket* packet;
  AVFrame* queue[QUEUE_SIZE];
  int queue_head;
  int queue_count;

  /* Converts what is not 4:2:0 8-bit (MJPEG's 4:2:2, the odd 4:4:4) into it. */
  struct SwsContext* sws;

  pthread_mutex_t slots_lock;
  AVFrame* slots[SLOT_COUNT];

  ANativeWindow* window;
  jobject surface; /* global ref of the Surface the window came from */
  int window_width;
  int window_height;
} Context;

static int ensure_ids(JNIEnv* env) {
  if (timeUsField) return 1;
  jclass out = (*env)->FindClass(env, "androidx/media3/decoder/VideoDecoderOutputBuffer");
  if (!out) return 0;
  initForYuvFrame = (*env)->GetMethodID(env, out, "initForYuvFrame", "(IIIII)Z");
  initForPrivateFrame = (*env)->GetMethodID(env, out, "initForPrivateFrame", "(II)V");
  dataField = (*env)->GetFieldID(env, out, "data", "Ljava/nio/ByteBuffer;");
  decoderPrivateField = (*env)->GetFieldID(env, out, "decoderPrivate", "J");
  timeUsField = (*env)->GetFieldID(env, out, "timeUs", "J");
  return initForYuvFrame && initForPrivateFrame && dataField && decoderPrivateField && timeUsField;
}

static void clear_queue(Context* c) {
  while (c->queue_count > 0) {
    av_frame_free(&c->queue[c->queue_head]);
    c->queue_head = (c->queue_head + 1) % QUEUE_SIZE;
    c->queue_count--;
  }
  c->queue_head = 0;
}

/* Moves every frame libavcodec has ready into the queue. */
static int drain(Context* c) {
  for (;;) {
    if (c->queue_count == QUEUE_SIZE) return 0;
    AVFrame* frame = av_frame_alloc();
    if (!frame) return AVERROR(ENOMEM);
    int ret = avcodec_receive_frame(c->codec, frame);
    if (ret < 0) {
      av_frame_free(&frame);
      return ret == AVERROR(EAGAIN) || ret == AVERROR_EOF ? 0 : ret;
    }
    c->queue[(c->queue_head + c->queue_count) % QUEUE_SIZE] = frame;
    c->queue_count++;
  }
}

static AVFrame* take(Context* c) {
  if (c->queue_count == 0) return NULL;
  AVFrame* frame = c->queue[c->queue_head];
  c->queue[c->queue_head] = NULL;
  c->queue_head = (c->queue_head + 1) % QUEUE_SIZE;
  c->queue_count--;
  return frame;
}

static int is_yuv420p(int format) {
  return format == AV_PIX_FMT_YUV420P || format == AV_PIX_FMT_YUVJ420P;
}

/* The frame as 8-bit 4:2:0, converting when the codec gave something else. */
static AVFrame* as_yuv420p(Context* c, AVFrame* frame) {
  if (is_yuv420p(frame->format)) return frame;
  AVFrame* out = av_frame_alloc();
  if (!out) goto fail;
  out->format = AV_PIX_FMT_YUV420P;
  out->width = frame->width;
  out->height = frame->height;
  if (av_frame_get_buffer(out, 32) < 0) goto fail;
  c->sws = sws_getCachedContext(c->sws, frame->width, frame->height, frame->format, out->width,
                                out->height, AV_PIX_FMT_YUV420P, SWS_BILINEAR, NULL, NULL, NULL);
  if (!c->sws) goto fail;
  sws_scale(c->sws, (const uint8_t* const*)frame->data, frame->linesize, 0, frame->height,
            out->data, out->linesize);
  av_frame_copy_props(out, frame);
  av_frame_free(&frame);
  return out;
fail:
  LOGE("cannot convert pixel format %d", frame->format);
  av_frame_free(&out);
  av_frame_free(&frame);
  return NULL;
}

static int colorspace_of(const AVFrame* frame) {
  switch (frame->colorspace) {
    case AVCOL_SPC_BT709:
      return COLORSPACE_BT709;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
      return COLORSPACE_BT2020;
    case AVCOL_SPC_BT470BG:
    case AVCOL_SPC_SMPTE170M:
      return COLORSPACE_BT601;
    default:
      /* What these codecs carry when they say nothing: SD, BT.601. */
      return frame->height <= 576 ? COLORSPACE_BT601 : COLORSPACE_UNKNOWN;
  }
}

FUNC(jlong, nInit, jstring jCodecName, jbyteArray jExtraData, jint threads) {
  if (!ensure_ids(env)) return 0;
  const char* name = (*env)->GetStringUTFChars(env, jCodecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(name);
  (*env)->ReleaseStringUTFChars(env, jCodecName, name);
  if (!codec) return 0;

  Context* c = calloc(1, sizeof(Context));
  if (!c) return 0;
  pthread_mutex_init(&c->slots_lock, NULL);
  c->codec = avcodec_alloc_context3(codec);
  c->packet = av_packet_alloc();
  if (!c->codec || !c->packet) goto fail;

  if (jExtraData) {
    jsize size = (*env)->GetArrayLength(env, jExtraData);
    c->codec->extradata = av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!c->codec->extradata) goto fail;
    (*env)->GetByteArrayRegion(env, jExtraData, 0, size, (jbyte*)c->codec->extradata);
    c->codec->extradata_size = size;
  }
  c->codec->pkt_timebase = (AVRational){1, 1000000};
  c->codec->thread_count = threads;
  c->codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
  /* Show what can be shown of a damaged frame rather than nothing: a scratch in an old rip should
   * cost a smear, not a frozen picture. */
  c->codec->err_recognition = 0;
  c->codec->flags2 |= AV_CODEC_FLAG2_SHOW_ALL;
  if (avcodec_open2(c->codec, codec, NULL) < 0) goto fail;
  return (jlong)(intptr_t)c;

fail:
  avcodec_free_context(&c->codec);
  av_packet_free(&c->packet);
  pthread_mutex_destroy(&c->slots_lock);
  free(c);
  return 0;
}

/* Returns 0, or a negative AVERROR when libavcodec refused the packet outright. */
FUNC(jint, nDecode, jlong jContext, jobject jData, jint size, jlong timeUs) {
  Context* c = (Context*)(intptr_t)jContext;
  uint8_t* data = (*env)->GetDirectBufferAddress(env, jData);
  /* libavcodec reads past the end of a packet; the input buffer has no such padding, so copy. */
  if (av_new_packet(c->packet, size) < 0) return AVERROR(ENOMEM);
  memcpy(c->packet->data, data, size);
  c->packet->pts = timeUs;
  c->packet->dts = AV_NOPTS_VALUE;
  int ret = avcodec_send_packet(c->codec, c->packet);
  if (ret == AVERROR(EAGAIN)) {
    /* Full: take what is ready, then the packet goes in. */
    ret = drain(c);
    if (ret >= 0) ret = avcodec_send_packet(c->codec, c->packet);
  }
  av_packet_unref(c->packet);
  if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_INVALIDDATA) return ret;
  /* An undecodable packet is dropped, as every player drops it; the next keyframe recovers. */
  return drain(c);
}

/*
 * Fills the output buffer with the next frame. Returns 0 when it did, 1 when there is none yet and
 * -1 on failure. timeUs of the buffer is set to the frame's own presentation time.
 */
FUNC(jint, nGetFrame, jlong jContext, jobject jOutputBuffer, jint outputMode) {
  Context* c = (Context*)(intptr_t)jContext;
  AVFrame* frame = take(c);
  if (!frame) return 1;
  frame = as_yuv420p(c, frame);
  if (!frame) return -1;

  int64_t pts = frame->best_effort_timestamp != AV_NOPTS_VALUE ? frame->best_effort_timestamp
                                                                : frame->pts;
  if (pts != AV_NOPTS_VALUE) (*env)->SetLongField(env, jOutputBuffer, timeUsField, pts);

  if (outputMode == OUTPUT_MODE_YUV) {
    int y_stride = frame->linesize[0];
    int uv_stride = frame->linesize[1];
    jboolean ok = (*env)->CallBooleanMethod(env, jOutputBuffer, initForYuvFrame, frame->width,
                                            frame->height, y_stride, uv_stride,
                                            colorspace_of(frame));
    if ((*env)->ExceptionCheck(env) || !ok) {
      av_frame_free(&frame);
      return -1;
    }
    jobject buffer = (*env)->GetObjectField(env, jOutputBuffer, dataField);
    uint8_t* dst = (*env)->GetDirectBufferAddress(env, buffer);
    int uv_height = (frame->height + 1) / 2;
    memcpy(dst, frame->data[0], (size_t)y_stride * frame->height);
    dst += (size_t)y_stride * frame->height;
    memcpy(dst, frame->data[1], (size_t)uv_stride * uv_height);
    dst += (size_t)uv_stride * uv_height;
    memcpy(dst, frame->data[2], (size_t)uv_stride * uv_height);
    av_frame_free(&frame);
    return 0;
  }

  pthread_mutex_lock(&c->slots_lock);
  int slot = NO_SLOT;
  for (int i = 0; i < SLOT_COUNT; i++) {
    if (!c->slots[i]) {
      slot = i;
      c->slots[i] = frame;
      break;
    }
  }
  pthread_mutex_unlock(&c->slots_lock);
  if (slot == NO_SLOT) {
    LOGE("no free frame slot");
    av_frame_free(&frame);
    return -1;
  }
  (*env)->CallVoidMethod(env, jOutputBuffer, initForPrivateFrame, frame->width, frame->height);
  (*env)->SetLongField(env, jOutputBuffer, decoderPrivateField, slot);
  return (*env)->ExceptionCheck(env) ? -1 : 0;
}

FUNC(void, nReleaseFrame, jlong jContext, jobject jOutputBuffer) {
  Context* c = (Context*)(intptr_t)jContext;
  jlong slot = (*env)->GetLongField(env, jOutputBuffer, decoderPrivateField);
  (*env)->SetLongField(env, jOutputBuffer, decoderPrivateField, NO_SLOT);
  if (slot < 0 || slot >= SLOT_COUNT) return;
  pthread_mutex_lock(&c->slots_lock);
  av_frame_free(&c->slots[slot]);
  pthread_mutex_unlock(&c->slots_lock);
}

static void copy_plane(uint8_t* dst, int dst_stride, const uint8_t* src, int src_stride, int width,
                       int height) {
  for (int y = 0; y < height; y++) {
    memcpy(dst, src, width);
    dst += dst_stride;
    src += src_stride;
  }
}

/* Draws the buffer's frame into the Surface. Returns 0, or non-zero when it could not. */
FUNC(jint, nRenderFrame, jlong jContext, jobject jSurface, jobject jOutputBuffer) {
  Context* c = (Context*)(intptr_t)jContext;
  jlong slot = (*env)->GetLongField(env, jOutputBuffer, decoderPrivateField);
  if (slot < 0 || slot >= SLOT_COUNT) return -1;

  if (!c->surface || !(*env)->IsSameObject(env, c->surface, jSurface)) {
    if (c->window) ANativeWindow_release(c->window);
    if (c->surface) (*env)->DeleteGlobalRef(env, c->surface);
    c->window = ANativeWindow_fromSurface(env, jSurface);
    c->surface = (*env)->NewGlobalRef(env, jSurface);
    c->window_width = 0;
    c->window_height = 0;
  }
  if (!c->window) return -1;

  pthread_mutex_lock(&c->slots_lock);
  AVFrame* frame = c->slots[slot];
  int ret = -1;
  if (!frame) goto done;
  if (c->window_width != frame->width || c->window_height != frame->height) {
    ANativeWindow_setBuffersGeometry(c->window, frame->width, frame->height, IMAGE_FORMAT_YV12);
    c->window_width = frame->width;
    c->window_height = frame->height;
  }
  ANativeWindow_Buffer buffer;
  if (ANativeWindow_lock(c->window, &buffer, NULL) || !buffer.bits) goto done;
  int width = frame->width < buffer.width ? frame->width : buffer.width;
  int height = frame->height < buffer.height ? frame->height : buffer.height;
  uint8_t* y = buffer.bits;
  copy_plane(y, buffer.stride, frame->data[0], frame->linesize[0], width, height);
  int uv_stride = (buffer.stride / 2 + 15) & ~15;
  int buffer_uv_height = (buffer.height + 1) / 2;
  uint8_t* v = y + (size_t)buffer.stride * buffer.height;
  uint8_t* u = v + (size_t)uv_stride * buffer_uv_height;
  int uv_width = (width + 1) / 2;
  int uv_height = (height + 1) / 2;
  copy_plane(u, uv_stride, frame->data[1], frame->linesize[1], uv_width, uv_height);
  copy_plane(v, uv_stride, frame->data[2], frame->linesize[2], uv_width, uv_height);
  ret = ANativeWindow_unlockAndPost(c->window);
done:
  pthread_mutex_unlock(&c->slots_lock);
  return ret;
}

FUNC(void, nFlush, jlong jContext) {
  Context* c = (Context*)(intptr_t)jContext;
  clear_queue(c);
  avcodec_flush_buffers(c->codec);
}

FUNC(void, nClose, jlong jContext) {
  Context* c = (Context*)(intptr_t)jContext;
  clear_queue(c);
  for (int i = 0; i < SLOT_COUNT; i++) av_frame_free(&c->slots[i]);
  avcodec_free_context(&c->codec);
  av_packet_free(&c->packet);
  sws_freeContext(c->sws);
  if (c->window) ANativeWindow_release(c->window);
  if (c->surface) (*env)->DeleteGlobalRef(env, c->surface);
  pthread_mutex_destroy(&c->slots_lock);
  free(c);
}

FUNC(jstring, nErrorString, jint error) {
  char message[AV_ERROR_MAX_STRING_SIZE];
  av_strerror(error, message, sizeof(message));
  return (*env)->NewStringUTF(env, message);
}

SFUNC(jstring, nVersion) { return (*env)->NewStringUTF(env, av_version_info()); }

SFUNC(jboolean, nHasDecoder, jstring jCodecName) {
  const char* name = (*env)->GetStringUTFChars(env, jCodecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(name);
  (*env)->ReleaseStringUTFChars(env, jCodecName, name);
  return codec != NULL;
}
