#!/usr/bin/env bash
# Collect the release artefacts: the APK under a stable name, and the
# manifest.json the Karoo reads to decide whether an update is available.
#
# The version is read back out of the built APK rather than restated here, so
# the manifest and the APK cannot disagree about what a release is.
#
# Run inside `nix develop` — it needs aapt2 from ANDROID_HOME.
#
#   tools/package-release.sh [path/to.apk]
set -euo pipefail

APK="${1:-$(find android/app/build/outputs/apk/release -name '*.apk' 2>/dev/null | head -1)}"
if [[ -z "$APK" || ! -f "$APK" ]]; then
    echo "no APK found; run 'gradle assembleRelease' first" >&2
    exit 1
fi

# A glob, not find: ANDROID_HOME/build-tools is a symlink into the nix store
# and find will not descend into a symlink given as its starting point.
: "${ANDROID_HOME:?ANDROID_HOME is not set}"
AAPT2=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort | tail -1)
if [[ -z "$AAPT2" ]]; then
    echo "no aapt2 under $ANDROID_HOME/build-tools" >&2
    exit 1
fi
# An unsigned APK cannot be installed. Without a keystore configured, Gradle
# happily emits app-release-unsigned.apk — publishing that would produce a
# release that silently fails for everyone who downloads it.
if [[ "$APK" == *unsigned* ]]; then
    echo "refusing to package $APK: it is unsigned" >&2
    echo "set MIEFQUIRL_KEYSTORE and the matching password/alias variables" >&2
    exit 1
fi

BADGING=$("$AAPT2" dump badging "$APK")

VERSION_NAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")
VERSION_CODE=$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$BADGING")
PACKAGE=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGING")

if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" || -z "$PACKAGE" ]]; then
    echo "could not read version information out of $APK" >&2
    exit 1
fi

REPOSITORY="${GITHUB_REPOSITORY:-hamann/karoo-miefquirl}"
RELEASE_NOTES="${GITHUB_REF_NAME:-v$VERSION_NAME}"
BASE="https://github.com/$REPOSITORY/releases/latest/download"

cp "$APK" miefquirl.apk

cat > manifest.json <<EOF
{
  "label": "Miefquirl",
  "packageName": "$PACKAGE",
  "latestApkUrl": "$BASE/miefquirl.apk",
  "latestVersion": "$VERSION_NAME",
  "latestVersionCode": $VERSION_CODE,
  "developer": "Holger Amann",
  "description": "Control a Wahoo KICKR Headwind fan from a data field on the Karoo.",
  "releaseNotes": "$RELEASE_NOTES",
  "tags": ["fan", "headwind", "indoor"]
}
EOF

echo "packaged $PACKAGE $VERSION_NAME ($VERSION_CODE) from $APK"
cat manifest.json
