# OktoDrive

Android Mumble client project for radio-style communication.

## Goals
- Reliable Mumble voice connection first
- Push-to-talk and channel switching
- Configurable P1–P4 hardware buttons
- Dual-watch listening and transmit behavior, after core audio is stable
- Automated builds and tests via GitHub Actions

## Status
Initial project scaffold. No working audio client yet.

## Development
Open in Android Studio with JDK 17 and Android SDK 35, or build in GitHub Actions.

Do not commit server passwords, credentials or signing keys.

## Local PTT/audio TLS test
This build is based on feature/opus-audio-prep (ed9a383), which contains microphone
capture, Opus encoding, Mumble TCP-tunnel voice transmission, Opus reception and
playback, channel/user lists and connection protection. The old main branch did
not contain these audio features.

The temporary exact certificate pin for ts.veiensvenner.no:64738 is carried over
from the TLS-only test. Other endpoints require platform CA and hostname checks.
The persistent Android client identity is retained, without forcing TLS 1.2.
TODO: remove the pin exception when the server has a public CA certificate.

Speaker mute now controls playback. PTT stops when the activity pauses; granting
microphone permission requires a fresh press. Rapid restart waits for the previous
capture worker to release its recorder.

Build/test: gradle :app:testDebugUnitTest :app:assembleDebug
Set OKTODRIVE_LIVE_TLS_TEST=1 to also run the live server TLS comparison.
These JVM tests do not validate Android client identity, microphone, speaker or
a conversation between two devices. Test these on Android before deployment.
The debug variant uses no.oktodrive.app.dev and the local debug signing key.

## TM7 / Android hardware buttons and updates
Use the menu "Konfigurer radioknapper" to learn PTT and speaker mute. Press and
release the desired physical key. Bindings are stored per app installation.
Only explicitly learned keyCode/scanCode pairs are consumed. No vendor codes or
broadcast actions are guessed. This supports normal Android KeyEvents while the
activity is focused; vendor-only broadcasts and screen-off PTT require device
diagnostics. PTT stops on key-up, focus loss, pause and disconnection.

The update button uses update-dev.json from the dev-latest prerelease for the
development app, and update-stable.json from the latest stable release otherwise.
It checks actual APK versionCode, package, Android requirement, signing certificate
and a repository-scoped HTTPS URL. It opens the APK in a browser for Android's
normal user-confirmed installation. This is not silent installation.

Publish using the "Publish signed OktoDrive update" manual workflow on the branch
containing these changes. Select dev for radio testing. Four repository secrets
are required: OKTODRIVE_KEYSTORE_BASE64, OKTODRIVE_STORE_PASSWORD,
OKTODRIVE_KEY_ALIAS, OKTODRIVE_KEY_PASSWORD. Keep the same existing signing key.
The publisher reads metadata from the signed APK and rejects a changed key or
non-increasing versionCode compared with the previous update manifest. Increase
versionCode for every published update. The dev-latest release is a rolling asset
channel; sourceCommit in its manifest records the exact source revision.

The GitHub build-143 APK has a different signer from this computer's debug key.
The build-143 GitHub log confirms no persistent signing secret was configured; its temporary private signing key is not available here. A new persistent key requires a one-time migration, not an in-place update of build 143.
The old build-143 update button cannot discover the new dev channel itself; this
first upgrade must be downloaded manually. Later versions can use the new button.

Source references: Android Activity.dispatchKeyEvent and KeyEvent documentation:
https://developer.android.com/reference/android/app/Activity#dispatchKeyEvent(android.view.KeyEvent)
https://developer.android.com/reference/android/view/KeyEvent
Signing information:
https://developer.android.com/reference/android/content/pm/SigningInfo
