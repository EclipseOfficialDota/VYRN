# Android development and testing

The build workflow uses Windows/PowerShell7, Git, JDK21, Android SDK platform36/build-tools36.0.0 and NDK27.2.12479018. The normal app supports API26+. Do not install test fixtures on a real phone or production instance without explicit authorization.

## Normal build

```powershell
./android/fetch-upstream.ps1
./scripts/setup-jdk.ps1
./scripts/build-android.ps1 -Flavor dev -BuildType debug -ApiUrl https://api.vyrn.example.com
./scripts/verify-apks.ps1
```

Use your own HTTPS hostname. Without `-ApiUrl`, the script targets the project's configured API. Build outputs and verification reports live in ignored `artifacts/`; ARM64, ARMv7 and Universal are separate files. A single-ABI APK must match the device. Debug is for evaluation. See [final signing](RELEASE_SIGNING.md) for production builds; keep the endpoint override for your server.

The fetch/prepare scripts pin Telegram and submodules, apply overlays and disable Telegram transport. The generated upstream directory is not committed. See [compliance](GPL_COMPLIANCE.md) before binary distribution.

Install [Android SDK command-line tools](https://developer.android.com/tools) under `.tools/android-sdk/cmdline-tools/latest/`. After `setup-jdk.ps1`, use PowerShell to install the pinned packages and review/accept the SDK licenses when prompted:

```powershell
$env:JAVA_HOME = (Resolve-Path .tools/jdk21/jdk-21.0.12.1+1).Path
$env:PATH = "$env:JAVA_HOME/bin;" + $env:PATH
& ./.tools/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat --sdk_root="$PWD/.tools/android-sdk" "platforms;android-36" "build-tools;36.0.0" "platform-tools" "ndk;27.2.12479018" "cmake;3.22.1"
& ./.tools/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat --sdk_root="$PWD/.tools/android-sdk" --licenses
```

Then run the build command above. The upstream wrapper downloads its pinned Gradle version.

## Disposable local backend

Install PowerShell7, Git and Docker Desktop. The container builds the pinned Go toolchain. From the repository root:

```powershell
./scripts/init-local.ps1
function dc { & docker compose --project-name vyrn-local --env-file .local/dev.env -f deployment/compose.yaml -f deployment/compose.local.yaml @args }
dc build core
dc up -d --wait postgres
./scripts/with-local.ps1 -Command docker -Arguments @('compose','--project-name','vyrn-local','--env-file','.local/dev.env','-f','deployment/compose.yaml','-f','deployment/compose.local.yaml','run','--rm','-e','VYRN_BOOTSTRAP_EMAIL','-e','VYRN_BOOTSTRAP_PASSWORD','-e','VYRN_BOOTSTRAP_INVITE','core','bootstrap')
# Continue only after bootstrap completes successfully.
dc up -d --wait core
```

Bootstrap only on the new empty database. Existing data must be preserved; a failed login is not a reason to wipe it. Local credentials are in ignored `.local/dev.env`. Control is at `http://127.0.0.1:18080`; HTTP is allowed only for this loopback development route.

In a separate console, with Core running:

```powershell
./scripts/setup-dev-tls.ps1
# Stop owned emulators while compiling.
./scripts/build-android.ps1 -Abis x86_64 -LocalTest -Instrumentation
./scripts/start-emulator.ps1 -Serial emulator-5564 -GpuMode software -ColdBoot -Monitor
```

The last command monitors the project API30 emulator; leave that console open. The TLS helper must retain the same local CA embedded in the test APK. Do not regenerate it between build and execution. Normal APKs must never include this CA.

## Focused checks

In another console, run suites sequentially:

```powershell
./scripts/test-android-integration.ps1 -Serial emulator-5564 -Phases auth-ui
./scripts/test-android-integration.ps1 -Serial emulator-5564 -Phases first
./scripts/test-signal.ps1 -Serial emulator-5564 -Network
./scripts/test-android-integration.ps1 -Serial emulator-5564 -Phases sessions-network
```

Consult each script's parameters before combining phases. Cold draft acceptance requires the exact ordered triple:

```powershell
./scripts/test-android-integration.ps1 -Serial emulator-5564 -Phases @('draft-cold-prepare','draft-cold-recover','draft-cold-confirm')
```

For genuine two-device messaging, stop API30 first, then use the two project API35 AVDs:

```powershell
./scripts/test-two-client-messaging.ps1 -Edit -Delete -Offline
```

This scenario registers disposable accounts and checks native controls plus server state. It needs two emulators; it is not a physical-device test. On2026-10-20 both attempted runs were blocked by host emulator crashes. Do not reuse an older report as a pass for a failed current run.

## Resources and results

Builds use one Gradle worker, no persistent daemon/parallel projects, a3GiB heap and two reported processors. One test emulator requests1536MiB guest RAM and two CPUs; host rendering overhead is additional. Avoid concurrent builds/emulators and stop only owned VYRN services. Use two emulators only for real two-client scenarios.

Reports are ignored JSON under `artifacts/`; record exact APK hashes, phase, time, results and failures in [TEST_REPORT](TEST_REPORT.md). A fixture assertion is not full interactive acceptance. Verify report freshness, suite exit and actual process state; some Windows RTK wrappers can outlive completed child processes. No broad process termination, device wipe or physical-phone testing is part of the default workflow.
