# Notices

VYRN is an independent project and is not affiliated with Telegram.

The Android UI is derived directly from [DrKLO/Telegram](https://github.com/DrKLO/Telegram), pinned at `f2908b14133bbffbf7ab04f641ecb5bfaf533242`. Copyright notices in upstream sources must be preserved. The GPL v2 text is reproduced in `LICENSES/GPL-2.0.txt`; individual source files and dependencies may specify additional terms or later-version options.

VYRN modifications introduce independent branding, email authentication, backend compatibility, disabled Telegram transport and build configuration. Original Telegram product marks must not be used to present this app as official. The current VYRN wordmark is a **temporary placeholder**, not the final supplied logo.

Android dependency notices, native library notices and source/build instructions must accompany distributed builds. A complete source distribution and license review are release gates. The repository presently has no public source remote.

Backend dependencies and their license files are available from the exact versions in `backend/go.mod` and `backend/go.sum`. The updater uses standard Ed25519. Password hashing uses Argon2id from `golang.org/x/crypto`; administrator TOTP uses `github.com/pquerna/otp`. No private signing or infrastructure credentials belong in this repository.
