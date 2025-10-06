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

If the API 33 platform files have not been installed yet, Maven aborts during dependency resolution with an error similar to:

```
[ERROR] Failed to execute goal on project uqureader: Could not resolve dependencies for project com.example:uqureader:apk:1.1.0
[ERROR] dependency: android:android:jar:33 (system)
[ERROR]         Could not find artifact android:android:jar:33 at specified path /usr/lib/android-sdk/platforms/android-33/android.jar
```

Maven emits the message with 120-column word wrapping, so the trailing `/android.jar` segment may appear on the next line in the console output. Installing the platform with the `sdkmanager` command from the previous section resolves the error.

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

### Troubleshooting "offline" emulators

Without `/dev/kvm` access the x86 system image falls back to pure software emulation.
The emulator boots, but `adb devices` keeps reporting the instance as `offline` because
the TCG backend that replaces KVM cannot provide the AVX and F16C CPU instructions that
the Android 9 x86 userspace expects. The `adb` transport never finishes handshaking, so
`adb logcat` and `adb install` are effectively blocked.

Installing the ARM64 system image is not a viable fallback either: QEMU2 on an x86_64
host aborts immediately with `PANIC: Avd's CPU Architecture 'arm64' is not supported`.

To obtain the layout traces in this repository you need to run the emulator (or attach a
physical API 28 device) on a machine that exposes hardware virtualization (KVM on Linux,
HVF on macOS, or WHPX/Hyper-V on Windows). Once the emulator reports the device as
`device` instead of `offline`, follow the steps below to capture the logs.

## Capture layout and text traces

Once the emulator (or a physical device) is available, install the freshly built APK and gather the specialised layout traces that confirm the yellow card, pagination controls, and text frame remain stable across page changes:

```bash
# Install the debug build produced by the Maven build step above.
adb install -r android-app/target/com.example.ttreader-android.apk

# Launch the app (replace with the actual launcher intent if needed).
adb shell monkey -p com.example.ttreader 1

# Collect the layout and text instrumentation added by the code changes.
adb logcat -s ReaderLayoutTrace ReaderTextTrace
```

Expect to see the following patterns in the log output while you flip through pages:

* `ReaderLayoutTrace` entries for `pageControls`, `pageNumber`, and `readerPageContainer` with identical `bounds=` values on every page turn.
* `ReaderLayoutTrace` entries for the yellow card container reporting a consistent `size=` and `screen=` location from the very first frame after launch.
* `ReaderTextTrace` entries whose `bounds=`, `padding=`, and `layout=` metrics stay constant as `applyPage` messages fire for each new page.

Any deviation indicates that the persisted layout cache is not being honoured and warrants further investigation before considering the regression fixed.

## Artifacts

The raw emulator artifacts (screenshot and `ReaderLayoutTrace` logcats) are large and were removed from version control. Re-run the session above to regenerate them if needed for comparison during future fixes.
