#!/usr/bin/env bash
# Builds libffmpegvideoJNI.so - the software video decoder behind FfmpegVideoRenderer - for the two
# ABIs the release APK ships, and copies it into app/src/main/jniLibs.
#
# Runs on Linux (WSL is fine). Needs only the Android NDK and git: the NDK carries its own clang and
# make, so nothing has to be installed system-wide. Not even a host compiler: configure only
# compile-checks it, and nothing host-side is built without --enable-hardcoded-tables, so the
# cross compiler stands in for it (a WSL without build-essential has no gcc and no glibc headers).
#
#   NDK=~/android-ndk-r29 WORK=~/ffmpeg-syntex ./build.sh            # both steps
#   NDK=... WORK=... ./build.sh ffmpeg                                 # only the FFmpeg libraries
#   NDK=... WORK=... ./build.sh jni                                    # only relink the JNI part
#
# FFmpeg is LGPL-2.1 and built without --enable-gpl and without --enable-nonfree. It is linked
# statically; this script, the JNI source next to it and the FFmpeg tag below are everything needed
# to rebuild and relink it.
set -euo pipefail

FFMPEG_TAG=n7.1.5
API=23
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../app/src/main/jniLibs"
: "${NDK:?set NDK to the Android NDK root}"
: "${WORK:?set WORK to a scratch directory}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
MAKE="$NDK/prebuilt/linux-x86_64/bin/make"
JOBS="$(nproc)"

# Formats that live in AVI, MKV and MP4 files and that TV boxes' hardware decoders are known to
# refuse or lack: MPEG-4 Part 2 (XviD/DivX, GMC and qpel included), its H.263 relatives and the
# Microsoft variants, WMV/VC-1, MPEG-1/2 and Motion JPEG. H.264, HEVC, VP9 and AV1 stay with the
# hardware (and dav1d) - a software path for them is a different question.
DECODERS=(mpeg4 h263 h263p flv msmpeg4v1 msmpeg4v2 msmpeg4v3 wmv1 wmv2 wmv3 vc1 mpeg1video
          mpeg2video mjpeg)

fetch() {
  if [ ! -d "$WORK/ffmpeg" ]; then
    git clone -q --depth 1 --branch "$FFMPEG_TAG" https://git.ffmpeg.org/ffmpeg.git "$WORK/ffmpeg"
  fi
}

build_ffmpeg() {  # abi arch cpu triple
  local abi=$1 arch=$2 cpu=$3 triple=$4
  local build="$WORK/build-$abi" prefix="$WORK/prefix-$abi"
  local enable=()
  for d in "${DECODERS[@]}"; do enable+=("--enable-decoder=$d"); done
  rm -rf "$build" "$prefix"
  mkdir -p "$build"
  (cd "$build" && "$WORK/ffmpeg/configure" \
      --prefix="$prefix" \
      --target-os=android --arch="$arch" --cpu="$cpu" --enable-cross-compile \
      --cc="$TC/bin/$triple$API-clang" --cxx="$TC/bin/$triple$API-clang++" \
      --host-cc="$TC/bin/$triple$API-clang" \
      --nm="$TC/bin/llvm-nm" --ar="$TC/bin/llvm-ar" --ranlib="$TC/bin/llvm-ranlib" \
      --strip="$TC/bin/llvm-strip" --sysroot="$TC/sysroot" \
      --extra-cflags="-O3 -fPIC" \
      --enable-static --disable-shared --enable-pic \
      --disable-autodetect --disable-everything --disable-programs --disable-doc \
      --disable-avdevice --disable-avformat --disable-avfilter --disable-swresample \
      --disable-network --disable-debug \
      --enable-avcodec --enable-avutil --enable-swscale \
      "${enable[@]}" >"$build/configure.log" 2>&1) \
    || { tail -30 "$build/configure.log"; exit 1; }
  "$MAKE" -C "$build" -j"$JOBS" install >"$build/make.log" 2>&1 || { tail -30 "$build/make.log"; exit 1; }
}

build_jni() {  # abi triple
  local abi=$1 triple=$2
  local prefix="$WORK/prefix-$abi"
  mkdir -p "$OUT/$abi"
  "$TC/bin/$triple$API-clang" -shared -fPIC -O2 -Wall \
      -I"$prefix/include" "$HERE/ffmpeg_video_jni.c" \
      -o "$OUT/$abi/libffmpegvideoJNI.so" \
      "$prefix/lib/libswscale.a" "$prefix/lib/libavcodec.a" "$prefix/lib/libavutil.a" \
      -lm -landroid -llog \
      -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--build-id=none
  "$TC/bin/llvm-strip" --strip-unneeded "$OUT/$abi/libffmpegvideoJNI.so"
  ls -l "$OUT/$abi/libffmpegvideoJNI.so"
}

ABIS=("arm64-v8a aarch64 armv8-a aarch64-linux-android"
      "armeabi-v7a arm armv7-a armv7a-linux-androideabi")

STEP="${1:-all}"
if [ "$STEP" != "jni" ]; then
  fetch
  for spec in "${ABIS[@]}"; do set -- $spec; build_ffmpeg "$1" "$2" "$3" "$4"; done
fi
if [ "$STEP" != "ffmpeg" ]; then
  for spec in "${ABIS[@]}"; do set -- $spec; build_jni "$1" "$4"; done
fi
