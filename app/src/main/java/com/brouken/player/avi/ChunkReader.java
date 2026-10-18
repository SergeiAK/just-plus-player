/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Synthex (from Media3 1.11.0, androidx.media3.extractor.avi.ChunkReader):
 * - every chunk of the stream is indexed with its offset, not only key frames, so a seek resumes at
 *   the index of the first chunk actually read after the seek position (Media3 took the last key
 *   chunk at or before it — for audio, the chunk before the video key frame, which is never read —
 *   and stamped everything after the seek one chunk early);
 * - a stream with a sample size (byte-counted audio: AC3, DTS, MP3 CBR, PCM in the old muxers) is
 *   timed by the bytes before each chunk, from the sizes in idx1, instead of spreading the duration
 *   evenly over the chunks (which drifts by up to a chunk, and for DTS divided by the length in bytes);
 * - MPEG-4 Part 2 video goes through Mpeg4VopReorderer: packed B-frames are unpacked and every frame
 *   is stamped with the slot it is shown in rather than the one it is decoded in.
 */
package com.brouken.player.avi;

import static androidx.media3.common.C.TRACK_TYPE_AUDIO;
import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.annotation.Nullable;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/** Reads chunks holding sample data. */
/* package */ final class ChunkReader {

  /** Parser states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    CHUNK_TYPE_VIDEO_COMPRESSED,
    CHUNK_TYPE_VIDEO_UNCOMPRESSED,
    CHUNK_TYPE_AUDIO,
  })
  private @interface ChunkType {}

  private static final int INITIAL_INDEX_SIZE = 512;
  private static final int CHUNK_TYPE_VIDEO_COMPRESSED = ('d' << 16) | ('c' << 24);
  private static final int CHUNK_TYPE_VIDEO_UNCOMPRESSED = ('d' << 16) | ('b' << 24);
  private static final int CHUNK_TYPE_AUDIO = ('w' << 16) | ('b' << 24);

  private final AviStreamHeaderChunk streamHeaderChunk;
  private final TrackOutput trackOutput;

  /** The chunk id fourCC (example: `01wb`), as defined in the index and the movi. */
  private final int chunkId;

  /** Secondary chunk id. Bad muxers sometimes use an uncompressed video id (db) for key frames. */
  private final int alternativeChunkId;

  private final long durationUs;
  private final boolean allSamplesAreSyncSamples;

  /**
   * Whether chunk times come from the bytes before the chunk: a stream with a sample size, where
   * dwRate / dwScale counts samples of dwSampleSize bytes each.
   */
  private final boolean timedByBytes;

  private int chunkCount;
  private int currentChunkSize;
  private int bytesRemainingInCurrentChunk;

  /** Number of chunks as calculated by the index. */
  private int currentChunkIndex;

  /** Bytes of this stream before the current chunk; meaningful when {@link #timedByBytes}. */
  private long currentChunkByteStart;

  private int indexChunkCount;
  private int indexSize;
  private long firstIndexChunkOffset;
  private long[] keyFrameOffsets;
  private int[] keyFrameIndices;

  /** Offset of every chunk of the stream in the index, in index order. */
  private long[] chunkOffsets;

  /** Bytes of the stream before each chunk in the index; filled when {@link #timedByBytes}. */
  private long[] chunkByteStarts;

  private long indexByteCount;

  /** Set for MPEG-4 Part 2 video: chunks are read whole and handed to it. */
  @Nullable private final Mpeg4VopReorderer vopReorderer;

  /** The current chunk, read whole for {@link #vopReorderer}. */
  private byte[] chunkData = new byte[0];

  public ChunkReader(
      int id,
      AviStreamHeaderChunk streamHeaderChunk,
      TrackOutput trackOutput,
      boolean allSamplesAreSyncSamples,
      boolean reorderMpeg4Video) {
    this.streamHeaderChunk = streamHeaderChunk;
    this.allSamplesAreSyncSamples = allSamplesAreSyncSamples;
    @C.TrackType int trackType = streamHeaderChunk.getTrackType();
    checkArgument(trackType == TRACK_TYPE_AUDIO || trackType == TRACK_TYPE_VIDEO);
    @ChunkType
    int chunkType = trackType == TRACK_TYPE_VIDEO ? CHUNK_TYPE_VIDEO_COMPRESSED : CHUNK_TYPE_AUDIO;
    chunkId = getChunkIdFourCc(id, chunkType);
    durationUs = streamHeaderChunk.getDurationUs();
    this.trackOutput = trackOutput;
    alternativeChunkId =
        trackType == TRACK_TYPE_VIDEO ? getChunkIdFourCc(id, CHUNK_TYPE_VIDEO_UNCOMPRESSED) : -1;
    timedByBytes =
        trackType == TRACK_TYPE_AUDIO
            && streamHeaderChunk.sampleSize > 0
            && streamHeaderChunk.scale > 0
            && streamHeaderChunk.rate > 0;
    firstIndexChunkOffset = C.INDEX_UNSET;
    keyFrameOffsets = new long[INITIAL_INDEX_SIZE];
    keyFrameIndices = new int[INITIAL_INDEX_SIZE];
    chunkOffsets = new long[INITIAL_INDEX_SIZE];
    chunkByteStarts = new long[timedByBytes ? INITIAL_INDEX_SIZE : 0];
    chunkCount = streamHeaderChunk.length;
    vopReorderer =
        reorderMpeg4Video && trackType == TRACK_TYPE_VIDEO
            ? new Mpeg4VopReorderer(this::writeReorderedSample)
            : null;
  }

  private void writeReorderedSample(
      byte[] data, int size, long presentationSlot, boolean keyFrame) {
    trackOutput.sampleData(new ParsableByteArray(data, size), size);
    trackOutput.sampleMetadata(
        getChunkTimestampUs((int) presentationSlot),
        keyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0,
        size,
        0,
        null);
  }

  /** The stream has been read to its end: frames held for reordering are written out. */
  public void onEndOfInput() {
    if (vopReorderer != null) {
      vopReorderer.flush();
    }
  }

  public void appendIndexChunk(long offset, int size, boolean isKeyFrame) {
    if (firstIndexChunkOffset == C.INDEX_UNSET) {
      firstIndexChunkOffset = offset;
    }
    if (isKeyFrame || allSamplesAreSyncSamples) {
      if (indexSize == keyFrameIndices.length) {
        keyFrameOffsets = Arrays.copyOf(keyFrameOffsets, keyFrameOffsets.length * 3 / 2);
        keyFrameIndices = Arrays.copyOf(keyFrameIndices, keyFrameIndices.length * 3 / 2);
      }
      keyFrameOffsets[indexSize] = offset;
      keyFrameIndices[indexSize] = indexChunkCount;
      indexSize++;
    }
    if (indexChunkCount == chunkOffsets.length) {
      chunkOffsets = Arrays.copyOf(chunkOffsets, chunkOffsets.length * 3 / 2);
      if (timedByBytes) {
        chunkByteStarts = Arrays.copyOf(chunkByteStarts, chunkOffsets.length);
      }
    }
    chunkOffsets[indexChunkCount] = offset;
    if (timedByBytes) {
      chunkByteStarts[indexChunkCount] = indexByteCount;
      indexByteCount += size;
    }
    indexChunkCount++;
  }

  public void advanceCurrentChunk() {
    currentChunkIndex++;
    currentChunkByteStart += currentChunkSize;
  }

  public long getCurrentChunkTimestampUs() {
    return timedByBytes
        ? getByteTimestampUs(currentChunkByteStart)
        : getChunkTimestampUs(currentChunkIndex);
  }

  public long getFrameDurationUs() {
    return getChunkTimestampUs(/* chunkIndex= */ 1);
  }

  public void commitIndex() {
    keyFrameOffsets = Arrays.copyOf(keyFrameOffsets, indexSize);
    keyFrameIndices = Arrays.copyOf(keyFrameIndices, indexSize);
    chunkOffsets = Arrays.copyOf(chunkOffsets, indexChunkCount);
    if (timedByBytes) {
      chunkByteStarts = Arrays.copyOf(chunkByteStarts, indexChunkCount);
    }
    if (allSamplesAreSyncSamples && streamHeaderChunk.sampleSize != 0 && indexSize > 0) {
      // In some files the AVI stream header chunk for audio has the number of bytes of audio in
      // dwLength instead of the number of chunks. Overwrite the chunk size to use the size of the
      // index, which should match the number of chunks because we only support formats where every
      // audio sample is a sync sample, and every sync sample should be in the index.
      chunkCount = indexSize;
    }
  }

  public boolean handlesChunkId(int chunkId) {
    return this.chunkId == chunkId || alternativeChunkId == chunkId;
  }

  public boolean isCurrentFrameAKeyFrame() {
    return allSamplesAreSyncSamples || Arrays.binarySearch(keyFrameIndices, currentChunkIndex) >= 0;
  }

  public boolean isVideo() {
    return (chunkId & CHUNK_TYPE_VIDEO_COMPRESSED) == CHUNK_TYPE_VIDEO_COMPRESSED;
  }

  public boolean isAudio() {
    return (chunkId & CHUNK_TYPE_AUDIO) == CHUNK_TYPE_AUDIO;
  }

  /** Prepares for parsing a chunk with the given {@code size}. */
  public void onChunkStart(int size) {
    currentChunkSize = size;
    bytesRemainingInCurrentChunk = size;
    if (vopReorderer != null && chunkData.length < size) {
      chunkData = new byte[size];
    }
  }

  /**
   * Provides data associated to the current chunk and returns whether the full chunk has been
   * parsed.
   */
  public boolean onChunkData(ExtractorInput input) throws IOException {
    if (vopReorderer != null) {
      int read =
          input.read(
              chunkData, currentChunkSize - bytesRemainingInCurrentChunk,
              bytesRemainingInCurrentChunk);
      if (read == C.RESULT_END_OF_INPUT) {
        throw new EOFException();
      }
      bytesRemainingInCurrentChunk -= read;
      boolean done = bytesRemainingInCurrentChunk == 0;
      if (done) {
        if (currentChunkSize > 0) {
          vopReorderer.onChunk(
              chunkData, currentChunkSize, currentChunkIndex, isCurrentFrameAKeyFrame());
        }
        advanceCurrentChunk();
      }
      return done;
    }
    bytesRemainingInCurrentChunk -=
        trackOutput.sampleData(input, bytesRemainingInCurrentChunk, false);
    boolean done = bytesRemainingInCurrentChunk == 0;
    if (done) {
      if (currentChunkSize > 0) {
        trackOutput.sampleMetadata(
            getCurrentChunkTimestampUs(),
            (isCurrentFrameAKeyFrame() ? C.BUFFER_FLAG_KEY_FRAME : 0),
            currentChunkSize,
            0,
            null);
      }
      advanceCurrentChunk();
    }
    return done;
  }

  /**
   * Resumes at the first chunk of this stream at or after {@code position}, which is the first one
   * the extractor will read from there.
   */
  public void seekToPosition(long position) {
    if (vopReorderer != null) {
      vopReorderer.reset();
    }
    if (indexChunkCount == 0) {
      currentChunkIndex = 0;
      currentChunkByteStart = 0;
    } else {
      int index =
          Util.binarySearchCeil(
              chunkOffsets, position, /* inclusive= */ true, /* stayInBounds= */ false);
      currentChunkIndex = index;
      if (timedByBytes) {
        currentChunkByteStart = index < indexChunkCount ? chunkByteStarts[index] : indexByteCount;
      }
    }
  }

  public SeekMap.SeekPoints getSeekPoints(long timeUs) {
    if (indexSize == 0) {
      // Return the offset of the first chunk as there are no keyframes in the index.
      return new SeekMap.SeekPoints(
          new SeekPoint(/* timeUs= */ 0, /* position= */ firstIndexChunkOffset));
    }
    if (timedByBytes && allSamplesAreSyncSamples && indexChunkCount > 0) {
      // Every chunk is a seek point, and its time is exact.
      int chunkIndex =
          Util.binarySearchFloor(
              chunkByteStarts, getTimestampBytes(timeUs), /* inclusive= */ true,
              /* stayInBounds= */ true);
      SeekPoint point = getChunkSeekPoint(chunkIndex);
      if (point.timeUs == timeUs || chunkIndex + 1 >= indexChunkCount) {
        return new SeekMap.SeekPoints(point);
      }
      return new SeekMap.SeekPoints(point, getChunkSeekPoint(chunkIndex + 1));
    }
    int targetFrameIndex = (int) (timeUs / getFrameDurationUs());
    int keyFrameIndex =
        Util.binarySearchFloor(
            keyFrameIndices, targetFrameIndex, /* inclusive= */ true, /* stayInBounds= */ true);
    if (keyFrameIndices[keyFrameIndex] == targetFrameIndex) {
      return new SeekMap.SeekPoints(getSeekPoint(keyFrameIndex));
    }
    // The target frame is not a key frame, we look for the two closest ones.
    SeekPoint precedingKeyFrameSeekPoint = getSeekPoint(keyFrameIndex);
    if (keyFrameIndex + 1 < keyFrameOffsets.length) {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint, getSeekPoint(keyFrameIndex + 1));
    } else {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint);
    }
  }

  private long getChunkTimestampUs(int chunkIndex) {
    return durationUs * chunkIndex / chunkCount;
  }

  /** dwRate / dwScale samples per second, dwSampleSize bytes per sample. */
  private long getByteTimestampUs(long bytes) {
    return Util.scaleLargeTimestamp(
        bytes / streamHeaderChunk.sampleSize,
        C.MICROS_PER_SECOND * streamHeaderChunk.scale,
        streamHeaderChunk.rate);
  }

  private long getTimestampBytes(long timeUs) {
    return Util.scaleLargeTimestamp(
            timeUs, streamHeaderChunk.rate, C.MICROS_PER_SECOND * streamHeaderChunk.scale)
        * streamHeaderChunk.sampleSize;
  }

  private SeekPoint getSeekPoint(int keyFrameIndex) {
    return new SeekPoint(
        keyFrameIndices[keyFrameIndex] * getFrameDurationUs(), keyFrameOffsets[keyFrameIndex]);
  }

  private SeekPoint getChunkSeekPoint(int chunkIndex) {
    return new SeekPoint(
        getByteTimestampUs(chunkByteStarts[chunkIndex]), chunkOffsets[chunkIndex]);
  }

  private static int getChunkIdFourCc(int streamId, @ChunkType int chunkType) {
    int tens = streamId / 10;
    int ones = streamId % 10;
    return (('0' + ones) << 8) | ('0' + tens) | chunkType;
  }
}
