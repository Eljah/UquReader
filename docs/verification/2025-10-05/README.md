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
[ERROR]         Could not find artifact android:android:jar:33 at specified path /workspace/UquReader/.codex/android-sdk/platforms/android-33/android.jar
```

The build now normalises the Android SDK location inside Codex containers to avoid spurious `/usr/lib/android-sdk` fallbacks and emits the missing-file path on a single line. Installing the platform with the `sdkmanager` command from the previous section resolves the error.

## Emulator session

Launch the API 28 emulator headlessly. Hardware virtualization is unavailable inside the
Codex container, so run the AVD in pure software mode by disabling acceleration and
reducing the boot animation overhead. The device will take several minutes to become
fully responsive, but eventually transitions to the `device` state:

```bash
./.codex/android-sdk/emulator/emulator \
  -avd codex-28 \
  -no-window -gpu swiftshader_indirect \
  -no-snapshot -accel off -no-boot-anim -no-audio
```

If you see repeated `Waiting for service package_native...` messages in `logcat`, keep the
emulator running—`tools/codex-install-apk.sh` now waits for the package manager to come up
before attempting to sideload the APK.

## Capture layout and text traces

Once the emulator (or a physical device) is available, install the freshly built APK and gather the specialised layout traces that confirm the yellow card, pagination controls, and text frame remain stable across page changes. The repository ships with a helper script that now auto-detects the Codex SDK, waits for Android to finish booting, and aborts with a diagnostic snippet if installation still fails:

```bash
# Install the freshest APK produced by Maven.
./tools/codex-install-apk.sh

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

### Codex container installation log (2025-10-06)

Running the emulator with `-accel off` takes roughly five minutes to reach a stable
state, after which `tools/codex-install-apk.sh` succeeds end-to-end:

```
[codex-install] Selected APK: /workspace/UquReader/android-app/target/uqureader-1.1.0.apk
[codex-install] Installing /workspace/UquReader/android-app/target/uqureader-1.1.0.apk to emulator-5554
[codex-install] Waiting for Android package manager to be ready...
Performing Streamed Install
Success
[codex-install] Installation completed successfully.
```

The helper script now discovers the bundled `.codex/android-sdk` platform-tools, polls the
`sys.boot_completed`, `dev.bootcomplete`, and `init.svc.bootanim` properties, and only calls
`adb install` once the package manager responds to `pm path android`. This makes the flow
repeatable inside Codex despite the missing hardware virtualization.

## Artifacts

The raw emulator artifacts (screenshot and `ReaderLayoutTrace` logcats) are large and were removed from version control. Re-run the session above to regenerate them if needed for comparison during future fixes.
