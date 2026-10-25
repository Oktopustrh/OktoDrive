#!/usr/bin/env bash
set -euo pipefail
mkdir -p publish previous
if [ "$CHANNEL" = dev ]; then
  TAG=dev-latest
  APK=app/build/outputs/apk/debug/app-debug.apk
  PREVIOUS_TAG=dev-latest
else
  VERSION=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)
  test -n "$VERSION"
  TAG="v$VERSION"
  APK=app/build/outputs/apk/release/app-release.apk
  PREVIOUS_TAG=$(gh api "repos/$GITHUB_REPOSITORY/releases" --jq '[.[] | select(.draft == false and .prerelease == false)][0].tag_name // ""')
fi
# Fail closed if listing fails; never skip version/signing checks on network errors.
EXISTING=$(gh api "repos/$GITHUB_REPOSITORY/releases" --jq '.[].tag_name')
if [ -n "$PREVIOUS_TAG" ] && printf '%s\n' "$EXISTING" | grep -Fxq "$PREVIOUS_TAG"; then
  ASSETS=$(gh release view "$PREVIOUS_TAG" --json assets --jq '.assets[].name')
  if printf '%s\n' "$ASSETS" | grep -Fxq "update-$CHANNEL.json"; then
    gh release download "$PREVIOUS_TAG" -p "update-$CHANNEL.json" -D previous
  fi
fi
cp "$APK" "publish/OktoDrive-$CHANNEL.apk"
python3 tools/generate_update_manifest.py "publish/OktoDrive-$CHANNEL.apk" "$CHANNEL" "$TAG" --output "publish/update-$CHANNEL.json" --previous "previous/update-$CHANNEL.json"
printf 'Signed %s update from commit %s. Install over the same app variant and signing key.\n' "$CHANNEL" "$GITHUB_SHA" > publish/notes.txt
if [ "$CHANNEL" = dev ] && printf '%s\n' "$EXISTING" | grep -Fxq "$TAG"; then
  gh release upload "$TAG" publish/OktoDrive-dev.apk publish/update-dev.json --clobber
  gh release edit "$TAG" --title 'OktoDrive development update' --notes-file publish/notes.txt --prerelease
elif [ "$CHANNEL" = dev ]; then
  gh release create "$TAG" publish/OktoDrive-dev.apk publish/update-dev.json --title 'OktoDrive development update' --notes-file publish/notes.txt --prerelease --target "$GITHUB_SHA"
else
  gh release create "$TAG" publish/OktoDrive-stable.apk publish/update-stable.json --title "OktoDrive $TAG" --notes-file publish/notes.txt --target "$GITHUB_SHA" --latest
fi
