# Source and license compliance

The Android build includes pinned libsignal0.103.0 (AGPL-3.0-only). Telegram core file headers allow GPLv2 or later, so the combination uses the GPLv3 option for those sources and must also satisfy AGPLv3 section13. Preserve both upstream licenses and inspect remaining third-party/submodule terms before distribution. See [Android notices](../android/NOTICE.md), [libsignal license](../android/LICENSE.libsignal) and the [GNU compatibility explanation](https://www.gnu.org/licenses/license-compatibility.en.html). This integration does not establish that the full release license/source audit has passed.

Pinned source: https://github.com/DrKLO/Telegram/commit/f2908b14133bbffbf7ab04f641ecb5bfaf533242 . Preserve the original copyright and license headers, LICENSE text, submodule pins and bundled third-party notices. Source file headers specify their applicable GPL terms; inspect those terms when introducing additional copyleft libraries.

The VYRN adapter does not replace or redraw Telegram's UI: it compiles the original UI and modifies branding, the entry flow and transport boundary. Preserve notices inside generated source packages even where product branding is removed from the application.

Before distributing any APK, supply corresponding source including the exact upstream checkout and submodules, all VYRN overlays/preparation scripts, dependency lock files, build instructions and required non-secret assets. A pin URL alone is not the complete source delivery for a modified binary. The current repository has no remote, so a public source release is still a delivery gate.

Exclude `.local`, `.tools`, caches, private credentials, actual `.env` files, private media, backups and keystores from release archives. Upstream includes public dummy credentials; those must not become the VYRN release identity. The VYRN build bypasses upstream signing configuration and disables the Google Services plugin.

Audit the compiled app for Telegram names/logos in product-facing surfaces, URLs, notification resources and alternate launch icons. Current branding covers the primary app label and launcher, but the full upstream surface audit is unfinished. Keep attribution in notices and license screens. Publish only after source completeness, artifact/source correspondence and license checks pass.
