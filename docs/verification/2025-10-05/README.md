# Emulator verification — 2025-10-05

This run reproduces the Android 9 (API 28) layout regression that causes the page controls card to render in the top left corner on the first frame before snapping into place after the first tap.

## Build

```
./mvnw -pl android-app -am package -DskipTests
```

The build succeeds after provisioning the debug keystore at `~/.android/debug.keystore`.

## Emulator session

The app was installed on an API 28 x86_64 emulator that was launched with software rendering because KVM acceleration is unavailable in the container:

```
$ANDROID_HOME/emulator/emulator @api28 -no-snapshot -no-boot-anim -gpu swiftshader_indirect -noaudio -no-window -accel off
```

A cold start of `com.example.ttreader/.MainActivity` was captured together with a screenshot and verbose layout logs.

## Artifacts

The raw emulator artifacts (screenshot and `ReaderLayoutTrace` logcats) are large and were removed from version control. Re-run the session above to regenerate them if needed for comparison during future fixes.
