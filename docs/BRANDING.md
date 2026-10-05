# Vyrn branding

The owner supplied `assets/vyrn-logo-original.jpg` on 2026-10-04. Its original bytes are preserved. This replaces the temporary VYRN text mark. No original SVG was supplied; the JPG is the authoritative artwork.

`assets/vyrn-logo-transparent-1024.png` is the original image's white emblem rendered with an alpha mask by Android `VyrnBrand.renderLogo`, without redesigning its silhouette. Two generated cutout attempts were rejected for artifacts and are not packaged. `drawable-nodpi/vyrn_mark.png` is the packaged copy. Adaptive foreground and monochrome use this mask with safe insets; the launcher background is black.

Login uses Telegram's actual OutlineTextContainerView, EditTextBoldCursor, TransformableLoginButtonView, typography and theme colors. Its fields remain email/password and invite registration, matching VYRN Core authentication. The welcome button routes to this authentication fragment. Keyboard-visible forms reserve space for the continuation button.

The upstream preparation script rebrands localized display text and runtime localization results to Vyrn, preserving resource keys, technical package IDs, real URLs and GPL attribution. It replaces native logo/notification/splash/launcher resource IDs, icon previews and QR marks. Static original artwork is also embedded in the two native logo animation containers. Existing native UI callers keep their resource IDs.

Build the public-endpoint dev APK and test package with `scripts/build-android.ps1 -Abis x86_64 -Instrumentation`. `scripts/test-branding.ps1` runs only on the disposable `vyrn_api35` emulator, clears its test app, checks localized names/identifier preservation/transparent mask export/activity launch and captures `artifacts/vyrn-login.png`. Review the screenshot; the checks do not prove full authentication or every upstream surface.

`scripts/test-public-android.ps1` is a separate read-only check of public DNS, platform-trusted HTTPS and access boundaries. On 2026-10-05 all eight checks passed after the runner launched the actual app before network probes. Android had blocked the background instrumentation UID; the foreground launch clears that restriction. DNS, TLS and access controls remain enabled. Android ARM64/ARMv7/Universal builds and signature/ABI checks are separate evidence; physical device installation is still required. This foreground check does not establish background sync or full authentication.
