#!/bin/bash -euo pipefail

ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
SRC_AAR="${SRC_AAR:-$ROOT/app/libs/mpv-android-lib-v0.0.1-arm64.aar}"
MPV_ANDROID_ROOT="${MPV_ANDROID_ROOT:-/home/comrade/termux_build/android-apps/mpv-android}"
NATIVE_SRC_DIR="${NATIVE_SRC_DIR:-$MPV_ANDROID_ROOT/app/src/main/libs/arm64-v8a}"
OUT_AAR="${OUT_AAR:-$ROOT/app/libs/mpv-android-lib-v0.0.1-arm64-rebuilt.aar}"

if [ ! -f "$SRC_AAR" ]; then
	echo "Source AAR not found: $SRC_AAR" >&2
	exit 1
fi

if [ ! -d "$NATIVE_SRC_DIR" ]; then
	echo "Native source dir not found: $NATIVE_SRC_DIR" >&2
	exit 1
fi

tmpdir="$(mktemp -d)"
cleanup() {
	rm -rf "$tmpdir"
}
trap cleanup EXIT

cp "$SRC_AAR" "$tmpdir/source.aar"
cd "$tmpdir"
unzip -q source.aar -d unpacked

mkdir -p unpacked/jni/arm64-v8a
for so in "$NATIVE_SRC_DIR"/*.so; do
	name="$(basename "$so")"
	if [ "$name" = "libplayer.so" ]; then
		continue
	fi
	cp "$so" "unpacked/jni/arm64-v8a/$name"
done

(
	cd unpacked
	zip -qr "$OUT_AAR" .
)

echo "Wrote rebuilt arm64 AAR to: $OUT_AAR"
