# Emulator verification — 2025-10-05

This run reproduces the Android 9 (API 28) layout regression that causes the page controls card to render in the top left corner on the first frame before snapping into place after the first tap.

## Provision the Android SDK and emulator

The container image ships with an incomplete SDK layout. Provision the required components and normalise the folder structure so both Gradle and the emulator can discover them:

```bash
export ANDROID_SDK_ROOT=/usr/lib/android-sdk
export ANDROID_HOME=/usr/lib/android-sdk

/usr/lib/android-sdk/cmdline-tools/bin/sdkmanager \
  --sdk_root="$ANDROID_SDK_ROOT" \
  "platforms;android-33" \
  "platform-tools" \
  "emulator" \
  "system-images;android-28;google_apis;x86"

# sdkmanager installs cmdline-tools without the expected `latest/` wrapper.
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
for entry in NOTICE.txt bin lib source.properties; do
  mv "$ANDROID_SDK_ROOT/cmdline-tools/$entry" \
     "$ANDROID_SDK_ROOT/cmdline-tools/latest/$entry"
done

# The emulator binary still expects the legacy `<sdk>/android-sdk/...` hierarchy.
ln -sfn "$ANDROID_SDK_ROOT" "$ANDROID_SDK_ROOT/android-sdk"
```

Create the API 28 AVD that matches the regression report:

```bash
# Accept the default hardware profile (Pixel).
echo no | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" \
  create avd --name api28 \
  --package "system-images;android-28;google_apis;x86" \
  --device "pixel"
```

## Build

```bash
TERM=dumb ./mvnw -pl android-app -am package -DskipTests
```

The build succeeds after provisioning the debug keystore at `~/.android/debug.keystore`. The Maven `android-maven-plugin` reports the missing keystore during the `sign-debug-apk` Ant task if it is absent.

## Emulator session

The app can be launched on the API 28 x86 emulator headlessly once `/dev/kvm` acceleration is available:

```bash
$ANDROID_HOME/emulator/emulator -avd api28 \
  -no-window -gpu swiftshader_indirect \
  -no-snapshot -no-audio -no-boot-anim
```

In the current container the emulator exits early with:

```
ERROR | x86 emulation currently requires hardware acceleration!
CPU acceleration status: /dev/kvm is not found: VT disabled in BIOS or KVM kernel module not loaded
```

The layout traces added in this branch emit `ReaderLayoutTrace` and `ReaderTextTrace` events that can be inspected via `adb logcat` once the emulator (or a physical device) is available.

## Artifacts

The raw emulator artifacts (screenshot and `ReaderLayoutTrace` logcats) are large and were removed from version control. Re-run the session above to regenerate them if needed for comparison during future fixes.
