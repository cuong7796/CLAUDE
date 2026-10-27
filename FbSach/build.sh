#!/usr/bin/env bash
# Build FB Sạch APK không cần Gradle / Android Studio.
#
# Cần: JDK 11+, aapt, dalvik-exchange (dx), zipalign, apksigner
#   (Ubuntu: apt install aapt dalvik-exchange zipalign apksigner)
# và android.jar API 34 (đặt ANDROID_JAR, hoặc script tự tải từ Sable/android-platforms).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$HERE/AndroidManifest.xml")"
OUT_APK="${OUT_APK:-$HERE/../FbSach_v${VERSION}.apk}"
BUILD="${BUILD_DIR:-$HERE/build}"
ANDROID_JAR="${ANDROID_JAR:-$BUILD/android-34.jar}"
KEYSTORE="$HERE/keystore/fbsach.jks"
KS_PASS="fbsach123"

rm -rf "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD"/*.apk
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

if [ ! -f "$ANDROID_JAR" ]; then
  echo ">> Tải android.jar API 34"
  curl -fsSL -o "$ANDROID_JAR" \
    https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
fi

echo ">> Icon"
python3 "$HERE/tools/make_icon.py" "$HERE/res"

echo ">> aapt: sinh R.java"
aapt package -f -m -J "$BUILD/gen" -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -I "$ANDROID_JAR"

echo ">> javac"
javac --release 8 -nowarn -encoding UTF-8 -Xlint:-options \
  -classpath "$ANDROID_JAR" -d "$BUILD/classes" \
  $(find "$HERE/src" "$BUILD/gen" -name '*.java')

echo ">> dx"
dalvik-exchange --dex --min-sdk-version=24 --output="$BUILD/dex/classes.dex" "$BUILD/classes"

echo ">> aapt: đóng gói"
aapt package -f -M "$HERE/AndroidManifest.xml" -S "$HERE/res" -A "$HERE/assets" \
  -I "$ANDROID_JAR" -F "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && aapt add "$BUILD/unsigned.apk" classes.dex >/dev/null)

echo ">> zipalign"
zipalign -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo ">> Tạo keystore ký app"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias fbsach -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=FB Sach, O=Cuong, C=VN" 2>/dev/null
fi

echo ">> apksigner"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 24 --v4-signing-enabled false --out "$OUT_APK" "$BUILD/aligned.apk"
apksigner verify --verbose "$OUT_APK" | head -5

echo ">> Xong: $OUT_APK"
