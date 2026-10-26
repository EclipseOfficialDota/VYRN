# Android development and testing

The build workflow uses PowerShell7, Git, JDK21, Android SDK platform36/build-tools36.0.0 and NDK27.2.12479018. Windows and Linux entry points are provided. The normal app supports API26+. Do not install test fixtures on a real phone or production instance without explicit authorization.

## Linux VPS build

On an x86_64 Ubuntu 24.04 host, from the repository checkout:

The terminal panel also provides **7 — Build Android APK** in every installation state. Choose existing tools or opt into installing tools on Ubuntu 24.04. It prompts for the API hostname (defaulting to the configured instance), confirms the build and displays the output directory. This builds a Debug APK; it does not change server settings.

```bash
git pull --ff-only origin main
sudo bash scripts/build-android-linux.sh --install-tools -ApiUrl https://api.your-domain.com
```

The opt-in flag installs build packages, including JDK21 and PowerShell7 via the [Microsoft Ubuntu repository](https://learn.microsoft.com/en-us/powershell/scripting/install/install-ubuntu). The script downloads [Google command-line tools](https://developer.android.com/studio#command-line-tools-only), verifies the pinned SHA-256, asks you to review SDK licenses, installs the pinned SDK/NDK/CMake packages and fetches the pinned Telegram sources/submodules. It does not deploy or restart server services. Subsequent builds omit `--install-tools`. On other Linux distributions, install the prerequisites manually and set `JAVA_HOME` and `ANDROID_HOME` before running it. Native Android SDK/NDK tools require an x86_64 build host here.

Outputs are in `artifacts/*.apk`; the build log is `.local/android-build.log`. Download the APK using SFTP on your SSH port. Builds default to Debug, use one Gradle worker, no emulator and no persistent Gradle daemon. Allow several GiB RAM plus native compiler overhead and substantial disk space for the SDK, source and build cache; a small server capable of running Docker Lite may not have enough memory to build Android. This is a Linux build entry point, not evidence of a completed Linux APK build; the lightweight orchestration check is `python3 scripts/test-android-linux.py`. Final Release builds still require all four signing variables; do not put the keystore in the public checkout.

### Local SDK tools archive

If the build host cannot download Google's command-line tools, download the original `commandlinetools-linux-13114758_latest.zip` from Google on a machine with access and transfer it privately via SFTP into `.local/android/` in your checkout. The build menu and Linux script automatically prefer that local file and verify SHA-256 `7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee` before extraction. A corrupt local file fails closed without downloading a replacement. `VYRN_ANDROID_TOOLS_ZIP` can select another local file. The archive remains ignored by Git; SDK licenses still require acceptance. Do not republish the SDK archive as a project release: Google's SDK license restricts redistribution. This only supplies the command-line tools, not the SDK platforms, NDK, Gradle or Maven dependencies; those still require network access or separately prepared local caches.

The preferred local filename is now `.local/android/vyrn-android-tools.zip`; the original filename remains supported. Renaming does not change the file contents, checksum or license.

### Private offline build kit

On a connected x86_64 Linux host with JDK21, PowerShell7, Git, Python3.12+, native build tools and sufficient disk space, run:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
bash scripts/prepare-android-offline.sh
```

This builds Debug APKs to populate the actual SDK/NDK/CMake, Gradle distribution and Maven caches, then repeats the build inside a network namespace with no external network. Only after that succeeds does it create `.local/offline/vyrn-android-offline.tar.gz` and its `.sha256`. It includes pinned upstream source and dependency caches; it excludes server settings, signing keystores, APK outputs and personal Gradle properties. Treat this as private owner-controlled build tooling, not a public redistribution archive.

Alternatively copy the source into your own **private repository** and run **Actions → Prepare private Android offline kit**, accepting the SDK licenses for your own cache. It uses GitHub's Linux runner instead of compiling on your computer. The workflow refuses to generate SDK cache artifacts in a public repository: authenticated-only artifact downloads on a public repository are not owner-only access. Private build-cache artifacts expire after one day. No SDK archive or APK is added to public Releases. A failed run produces no offline-ready kit.

Transfer both files privately into `.local/offline/` of a **fresh checkout of the exact same VYRN commit** on the VPS. System prerequisites must already be installed. Select **7 → 3 — Build offline from a prepared private kit**, or:

```bash
bash scripts/build-android-linux.sh --offline -ApiUrl https://api.your-domain.com
```

When the local archive is present, the normal build command and menu option 1 automatically use offline mode; explicitly choosing `--install-tools` still requests online system-package installation. The kit is restored automatically only when `android/upstream` is absent, with checksum, source-revision, archive-path and disk-space checks. Existing source checkouts are preserved. SDK package installation and Git fetch are skipped; Gradle receives `--offline`. The NDK toolchain files and pinned Gradle distribution must already be present. Missing caches cause an error rather than an online repair. A kit is specific to Linux/x86_64 and its prepared source revision, ABI list and build variant. Keep matching source and all system tools before connectivity is lost; a source-only Git clone is not sufficient. The verification run reuses native compilation outputs on the preparing machine, so it is not a substitute for a fresh-machine offline acceptance test after transfer.

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
