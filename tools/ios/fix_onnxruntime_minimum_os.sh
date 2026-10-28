#!/bin/sh
# Xcode can embed a stub for ONNX Runtime's statically linked framework.
# The stub inherits the app's deployment target, but its copied Info.plist
# still advertises iOS 15.1. App Store Connect rejects that mismatch (90208).
# Run after the framework copy and before the app's final code signature.
# Never modify the package cache, an existing archive, or the Mach-O binary.
set -eu

fail() {
    echo "error: ONNX Runtime minimum OS: $*" >&2
    exit 1
}

# This workaround concerns device archives, not the simulator or macOS tests.
[ "${PLATFORM_NAME:-}" = "iphoneos" ] || exit 0

framework="${TARGET_BUILD_DIR:?}/${FRAMEWORKS_FOLDER_PATH:?}/onnxruntime.framework"
plist="$framework/Info.plist"
binary="$framework/onnxruntime"
minimum="${IPHONEOS_DEPLOYMENT_TARGET:?}"

[ -f "$plist" ] && [ -f "$binary" ] || fail "embedded framework is missing"
previous=$(/usr/bin/plutil -extract MinimumOSVersion raw -o - "$plist") || fail "missing MinimumOSVersion"

# Check every architecture before changing anything. Do not hide a future
# runtime upgrade that actually requires a newer OS than the app supports.
build_info=$(/usr/bin/xcrun vtool -show-build "$binary") || fail "cannot inspect Mach-O"
if ! printf '%s\n' "$build_info" | /usr/bin/awk -v target="$minimum" -v declared="$previous" '
    function valid(v) { return v ~ /^[0-9]+\.[0-9]+(\.[0-9]+)?$/ }
    function newer(v, limit, a, b, i) {
        split(v, a, "."); split(limit, b, ".")
        for (i = 1; i <= 3; i++) {
            if (a[i] + 0 > b[i] + 0) return 1
            if (a[i] + 0 < b[i] + 0) return 0
        }
        return 0
    }
    $1 == "platform" { if ($2 != "IOS") bad = 1 }
    $1 == "minos" {
        count++
        if (!valid($2) || newer($2, target)) bad = 1
    }
    END { exit (!valid(target) || !valid(declared) || newer(declared, target) || count == 0 || bad) }
'; then
    fail "binary is incompatible with iOS $minimum; refusing to rewrite metadata"
fi

[ "$previous" != "$minimum" ] || exit 0

# A changed plist invalidates the framework signature. Require a signing
# identity up front for signed builds; Xcode signs the containing app later.
if [ "${CODE_SIGNING_ALLOWED:-YES}" != "NO" ]; then
    [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ] || fail "missing code-signing identity"
fi

/usr/bin/plutil -replace MinimumOSVersion -string "$minimum" "$plist"
if [ "${CODE_SIGNING_ALLOWED:-YES}" != "NO" ]; then
    /usr/bin/codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" \
        --preserve-metadata=identifier,entitlements --timestamp=none "$framework"
fi
echo "ONNX Runtime MinimumOSVersion: $previous -> $minimum (Mach-O compatibility verified)"
