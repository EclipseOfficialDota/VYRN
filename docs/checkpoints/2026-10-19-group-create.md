# Native group creation, invitations and refreshed Debug APKs

Accepted on the API30 emulator on 2026-10-19. The full project remains in development.

The original add-member confirmation enabled forwarding100 earlier messages by default. VYRN explicitly rejects that unsupported request. The preparation overlay now omits that checkbox, so the normal Add confirmation submits the supported zero-forwarding request. The group-creation photo picker/camera affordance is also disabled while group-photo APIs are absent; the native placeholder remains. Personal profile-photo support is unchanged.

## Screen and server evidence

`artifacts/android-group-create-ui-api30.json` passes **82 recorded checks / 62 distinct names** at23:14:43+03:30. Registration and initial picker entry are fixtures. From there, original IME search performs real public-name discovery, an actual result touch selects the member, Next opens the native name screen, and an actual Create touch creates exactly one server group with its original title, owner and selected member. The selected account can load that group. An original chat-header touch opens Profile; the real Add Member row opens the picker. Cancel preserves membership and the selection screen. Reopening and confirming Add admits exactly the intended new member, preserves the title and updates the original Profile membership. The unsupported checkbox is absent from the real dialog.

`artifacts/android-group-settings-ui-api30.json` then passes **72 recorded checks** at23:15:30+03:30 on the same artifacts, covering administrator/member roles, confirmed title saving, actual outage, retained input/cache and explicit retry. Both suites completed sequentially. Creation's driver exited0. Settings printed PASS and wrote its matching report; its actual worker ended, and identity-verified childless RTK wrapper26192 was stopped afterward, so a clean outer-wrapper exit is not asserted.

Local-TLS main APK SHA256: `3bb91565269cca2ca825969fd12f57625d7d564f31656a8bbee19d5a6528ac04`.
Test APK SHA256: `11c7a17dcad22c6f517bd2b3666bb19fd2786204518317f9078c09690da17304`.

The first harness run stayed on the name screen after the Create touch. Its screenshot showed the ordinary form without a busy state; this alone does not establish a product/API failure. The harness now waits for visible/enabled controls and stable screen geometry for600ms before actual touches, reusing the existing send-test approach. Its test-only rebuild took42s and the final complete suite passed. The full local-TLS build took3m07s. No passing result is attributed to the incomplete run.

## Current normal APKs

After the emulator was shut down, one normal ARM64+ARMv7 Debug build completed in3m42s. `scripts/verify-apks.ps1` verified all three signatures, package names, both expected native ABIs, API26 minimum, system-only TLS policy, no local test CA, actual Signal JNI and absence of desktop/test-only Signal libraries. The Universal DEX also contains the public API endpoint and confirmed group-save implementation from the previous checkpoint. These are ordinary Debug builds, not final signed production Release artifacts, and were not installed on a physical ARM device.

| Local file under artifacts/ | Bytes | SHA256 |
|---|---:|---|
| VyrnApp-dev-universal-debug.apk |158736298|`6667eb52cbceee0f8ebe705af4f0333a382ce73878b14c8ed706f5ae05217cae`|
| VyrnApp-dev-arm64-v8a-debug.apk |122510801|`15fd2faa8adbd20a9cf03a236b7115f87bd0eb163ddc3323e5c8d8c5cc0d6fe6`|
| VyrnApp-dev-armeabi-v7a-debug.apk |108224187|`3ca378cd10b548db97c53ffa7eb39a3fc20510bfd675025471e1835476b76d51`|

The Universal package is about159MB and includes ARM64 and ARMv7, meeting the requested150–250MB range. The existing private GitHub Debug prerelease still contains the earlier snapshot; it has not been replaced with these local files. Its published checksum continues to describe that earlier asset.

Builds used one Gradle worker, two JVM processors and a3GB heap with the emulator off; tests used one1536MB/two-core emulator. No Java/emulator workers remained after verification. No phone, VPS, backend, DNS, final signing, TURN or external backup was changed.

## Remaining scope

This establishes native selection/creation/profile/invitation in the observed emulator scenario. Initial entry through the main New Group menu, interrupted/uncertain creation, persistence across process death, invitation failure/retry, history visibility, granular permissions and complete role-management screens, group photos, self-removal/deletion, lifecycle races, two-device interaction and physical-device acceptance remain unproven or unfinished. Selecting zero forwarded messages is not a claim about which existing messages a newly admitted member can subsequently read under the server's history policy.
