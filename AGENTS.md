# Repository Guidelines

## Android installation script usage

- To build and deploy the Android app to a connected emulator or device, prefer the helper script `./tools/codex-install-apk.sh`.
- Run the script from the repository root. It will invoke the Maven wrapper (`./mvnw`) to build the `android-app` module with the `codex-android-sdk,codex-signing` profiles unless you set `CODEX_INSTALL_SKIP_BUILD=1`.
- The script automatically locates the newest APK in `android-app/target/` and attempts a streaming `adb install`, falling back to pushing the APK and running `pm install` if the first attempt times out.
- When no device is connected, the script tries to launch an emulator using the Codex-managed SDK under `.codex/android-sdk`. You can override the AVD name with `CODEX_INSTALL_AVD`.
- Control timeouts and behaviour with environment variables:
  - `CODEX_INSTALL_TIMEOUT_SECONDS` adjusts the install timeout (default 120s).
  - `CODEX_INSTALL_ANDROID_MODULE`, `CODEX_INSTALL_MAVEN_PROFILES`, `CODEX_INSTALL_MAVEN_GOAL`, and `CODEX_INSTALL_EXTRA_MAVEN_GOALS` customize the build.
  - `CODEX_INSTALL_SKIP_BUILD=1` skips the Maven build step when an up-to-date APK already exists.
- Ensure the Codex SDK bundle has been provisioned under `.codex/android-sdk` so that `adb`, `emulator`, and signing keys are available. The script will update `PATH` automatically when that directory exists.
- Logs from auto-launched emulators are stored under `.codex/logs/` with timestamps for later inspection.

