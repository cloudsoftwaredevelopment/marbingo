#!/usr/bin/env bash
# Builds the staging APK and publishes it as an over-the-air update for Marbingo Field.
#   bash scripts/release-apk.sh "What changed"            # optional update (tablets show a banner)
#   bash scripts/release-apk.sh --required "What changed" # tablets below this version must update
# Bump versionCode/versionName in android/app/build.gradle.kts first. Tablets poll GET /api/app/latest,
# which serves downloads/latest.json written here. Signed with /root/keystores/marbingo-staging.keystore —
# never change that key, or installed tablets can't take updates.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REQUIRED=0
if [ "${1:-}" = "--required" ]; then REQUIRED=1; shift; fi
NOTES="${1:-}"
GRADLE="$ROOT/android/app/build.gradle.kts"
CODE=$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' "$GRADLE" | head -1)
NAME=$(sed -n 's/^ *versionName = "\([^"]*\)".*/\1/p' "$GRADLE" | head -1)
OUT="$ROOT/downloads/marbingo-field-v$NAME.apk"
LATEST="$ROOT/downloads/latest.json"

if [ -f "$LATEST" ]; then
  PREV=$(node -e "console.log(require('$LATEST').versionCode)")
  if [ "$CODE" -le "$PREV" ]; then echo "versionCode $CODE is not above the published $PREV — bump it in $GRADLE" >&2; exit 1; fi
fi

cd "$ROOT/android"
source /etc/profile.d/android-sdk.sh
./gradlew -q :app:assembleStaging
cp app/build/outputs/apk/staging/app-staging.apk "$OUT"

SHA=$(sha256sum "$OUT" | cut -d' ' -f1)
SIZE=$(stat -c %s "$OUT")
MIN=0; [ "$REQUIRED" = 1 ] && MIN=$CODE
# Keep an earlier "required" floor unless this release raises it.
if [ -f "$LATEST" ]; then OLDMIN=$(node -e "console.log(require('$LATEST').minVersionCode||0)"); [ "$OLDMIN" -gt "$MIN" ] && MIN=$OLDMIN; fi
node -e '
const [file, code, name, sha, size, min, notes] = process.argv.slice(1);
require("fs").writeFileSync(file, JSON.stringify({
  versionCode: +code, versionName: name, url: "/downloads/marbingo-field-v" + name + ".apk",
  sha256: sha, size: +size, minVersionCode: +min, notes, publishedAt: new Date().toISOString(),
}, null, 2) + "\n");' "$LATEST" "$CODE" "$NAME" "$SHA" "$SIZE" "$MIN" "$NOTES"
node "$ROOT/scripts/refresh-downloads.js"
echo "Published v$NAME (code $CODE, min $MIN): $OUT"
