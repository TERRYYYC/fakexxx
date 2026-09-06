#!/usr/bin/env bash
# #13: prove the two install identities are separate APK artifacts and that the
# carrier direction metadata stays in flavor source sets, never src/main.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
legacy_apk="${1:?usage: check-cutover-identity-variants.sh LEGACY_DEBUG_APK PRODUCT_DEBUG_APK [LEGACY_GLM_APK PRODUCT_GLM_APK]}"
product_apk="${2:?usage: check-cutover-identity-variants.sh LEGACY_DEBUG_APK PRODUCT_DEBUG_APK [LEGACY_GLM_APK PRODUCT_GLM_APK]}"
legacy_glm_apk="${3:-}"
product_glm_apk="${4:-}"
app_dir="$repo_root/apps/cellrebel-auto/app"

if [ -n "$legacy_glm_apk" ] || [ -n "$product_glm_apk" ]; then
  [ -n "$legacy_glm_apk" ] && [ -n "$product_glm_apk" ] || {
    echo "glmbench APKs must be provided as a pair" >&2
    exit 2
  }
fi

for apk in "$legacy_apk" "$product_apk"; do
  [ -f "$apk" ] || { echo "missing APK: $apk" >&2; exit 2; }
done
for apk in "$legacy_glm_apk" "$product_glm_apk"; do
  [ -z "$apk" ] || [ -f "$apk" ] || { echo "missing APK: $apk" >&2; exit 2; }
done

[ -f "$app_dir/src/legacyId/AndroidManifest.xml" ] || { echo "missing legacyId manifest" >&2; exit 1; }
[ -f "$app_dir/src/productId/AndroidManifest.xml" ] || { echo "missing productId manifest" >&2; exit 1; }
grep -q 'legacy-export' "$app_dir/src/legacyId/AndroidManifest.xml" || { echo "legacyId direction missing" >&2; exit 1; }
grep -q 'product-import' "$app_dir/src/productId/AndroidManifest.xml" || { echo "productId direction missing" >&2; exit 1; }
if rg -q 'CARRIER_DIRECTION' "$app_dir/src/main"; then
  echo "carrier direction leaked into src/main" >&2
  exit 1
fi

find_aapt() {
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  [ -n "$sdk_root" ] || return 1
  find "$sdk_root/build-tools" -type f -name aapt -perm -111 2>/dev/null | sort -V | tail -1
}

aapt="$(find_aapt || true)"
[ -n "$aapt" ] && [ -x "$aapt" ] || { echo "aapt not found; set ANDROID_HOME" >&2; exit 2; }

package_name() {
  "$aapt" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1
}

[ "$(package_name "$legacy_apk")" = 'com.example.cellrebelauto' ] || {
  echo "legacyId APK package mismatch" >&2; exit 1;
}
[ "$(package_name "$product_apk")" = 'come.xx.fakeaauto' ] || {
  echo "productId APK package mismatch" >&2; exit 1;
}

if [ -n "$legacy_glm_apk" ]; then
  [ "$(package_name "$legacy_glm_apk")" = 'com.example.cellrebelauto.glmbench' ] || {
    echo "legacyId glmbench APK package mismatch" >&2; exit 1;
  }
  [ "$(package_name "$product_glm_apk")" = 'come.xx.fakeaauto.glmbench' ] || {
    echo "productId glmbench APK package mismatch" >&2; exit 1;
  }
fi

echo "cutover identity APK contract holds"
