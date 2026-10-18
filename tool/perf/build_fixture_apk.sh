#!/usr/bin/env bash
# Builds the performance-harness fixture APK from compiled JVM classes.
#
# Usage: build_fixture_apk.sh <manifest> <output.apk> <classes-dirs> <library-classpath>
#
# Normally run through `./gradlew :server:perfFixtureApk`. Requires an Android
# SDK with build-tools and a platform; set ANDROID_HOME (or ANDROID_SDK_ROOT)
# when the SDK is not in its default location. The resulting APK is committed
# so the harness itself never needs the SDK.
set -euo pipefail

manifest="$1"
output="$2"
classes_dirs="$3"
libraries="$4"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
build_tools="$(ls -d "$sdk"/build-tools/* | sort -V | tail -n 1)"
platform="$(ls -d "$sdk"/platforms/android-* | sort -V | tail -n 1)"
android_jar="$platform/android.jar"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Gather the fixture classes into one JAR for d8.
mkdir -p "$work/classes"
IFS=':' read -r -a dirs <<<"$classes_dirs"
for dir in "${dirs[@]}"; do
  cp -R "$dir"/. "$work/classes/"
done
(cd "$work/classes" && zip -qr "$work/classes.jar" .)

# min-api 24 keeps interface default methods (kotlinx.serialization uses them)
d8_args=(--release --min-api 24 --lib "$android_jar" --output "$work/dex")
IFS=':' read -r -a libs <<<"$libraries"
for lib in "${libs[@]}"; do
  [ -e "$lib" ] && d8_args+=(--classpath "$lib")
done
mkdir -p "$work/dex"
"$build_tools/d8" "${d8_args[@]}" "$work/classes.jar"

# Real extensions carry a resource table (at least their label); apk-parser
# cannot read an empty one.
res_dir="$(dirname "$manifest")/res"
"$build_tools/aapt2" compile --dir "$res_dir" -o "$work/res.zip"

"$build_tools/aapt2" link \
  -I "$android_jar" \
  "$work/res.zip" \
  --manifest "$manifest" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  -o "$work/fixture.apk"
(cd "$work/dex" && zip -q "$work/fixture.apk" classes*.dex)

mkdir -p "$(dirname "$output")"
cp "$work/fixture.apk" "$output"
echo "Wrote $(basename "$output") ($(wc -c <"$output" | tr -d ' ') bytes)"
