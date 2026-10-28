#!/bin/sh
# Synthetic packaging tests only; no models, device data, or research inputs.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fixture_root=$(mktemp -d "${TMPDIR:-/tmp}/forestix-onnx-minimum-tests.XXXXXX")
# Keep these small fixtures for inspection; do not delete unrelated temp files.
framework="$fixture_root/Build With Spaces/Forestix.app/Frameworks/onnxruntime.framework"
mkdir -p "$framework"
export TARGET_BUILD_DIR="$fixture_root/Build With Spaces"
export FRAMEWORKS_FOLDER_PATH="Forestix.app/Frameworks"
export PLATFORM_NAME=iphoneos IPHONEOS_DEPLOYMENT_TARGET=18.2 CODE_SIGNING_ALLOWED=NO
fix="$script_dir/fix_onnxruntime_minimum_os.sh"
plist="$framework/Info.plist"
binary="$framework/onnxruntime"
sdk=$(/usr/bin/xcrun --sdk iphoneos --show-sdk-path)
/usr/bin/xcrun clang -dynamiclib -x c /dev/null -isysroot "$sdk" \
    -target arm64-apple-ios18.2 -o "$binary"

reset_plist() {
    /usr/bin/plutil -create xml1 "$plist"
    /usr/bin/plutil -insert CFBundleIdentifier -string com.microsoft.onnxruntime "$plist"
    /usr/bin/plutil -insert CFBundleExecutable -string onnxruntime "$plist"
    /usr/bin/plutil -insert CFBundlePackageType -string FMWK "$plist"
    /usr/bin/plutil -insert MinimumOSVersion -string 15.1 "$plist"
}
expect_minimum() {
    actual=$(/usr/bin/plutil -extract MinimumOSVersion raw -o - "$plist")
    [ "$actual" = "$1" ] || { echo "Unexpected minimum: $actual" >&2; exit 1; }
}

reset_plist
binary_before=$(/usr/bin/shasum -a 256 "$binary")
/bin/sh "$fix"
expect_minimum 18.2
[ "$(/usr/bin/shasum -a 256 "$binary")" = "$binary_before" ]
/bin/sh "$fix"
expect_minimum 18.2
echo 'PASS: mismatched metadata corrected, binary unchanged, repeat run safe'

reset_plist
if IPHONEOS_DEPLOYMENT_TARGET=18.1 /bin/sh "$fix"; then exit 1; fi
expect_minimum 15.1
echo 'PASS: incompatible binary rejected before any plist change'

if IPHONEOS_DEPLOYMENT_TARGET=invalid /bin/sh "$fix"; then exit 1; fi
expect_minimum 15.1
echo 'PASS: invalid deployment target rejected'

/usr/bin/plutil -replace MinimumOSVersion -string 19.0 "$plist"
if /bin/sh "$fix"; then exit 1; fi
expect_minimum 19.0
echo 'PASS: newer declared minimum never lowered'

reset_plist
IPHONEOS_DEPLOYMENT_TARGET=18.10 /bin/sh "$fix"
expect_minimum 18.10
echo 'PASS: multi-digit version components compared numerically'

reset_plist
PLATFORM_NAME=iphonesimulator /bin/sh "$fix"
expect_minimum 15.1
echo 'PASS: simulator skipped'

if CODE_SIGNING_ALLOWED=YES EXPANDED_CODE_SIGN_IDENTITY= /bin/sh "$fix"; then exit 1; fi
expect_minimum 15.1
echo 'PASS: missing signing identity rejected before any plist change'

CODE_SIGNING_ALLOWED=YES EXPANDED_CODE_SIGN_IDENTITY=- /bin/sh "$fix"
expect_minimum 18.2
/usr/bin/codesign --verify --strict "$framework"
echo 'PASS: framework re-signed successfully after metadata change'
echo "Fixtures: $fixture_root"
