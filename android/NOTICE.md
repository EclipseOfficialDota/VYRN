# Android source and license notices

Telegram Android is pinned in `upstream.lock.json`. Preserve its original GPL license, copyright headers and dependency notices. Its core source headers permit GPL version2 or later; GPL version3 is the applicable option for the combination with libsignal.

libsignal0.103.0, copyright Signal Messenger, LLC, is pinned in `libsignal.lock.json` and licensed AGPL-3.0-only. Its license is reproduced in `LICENSE.libsignal`. The combined Android distribution must satisfy GPLv3 and the AGPLv3 section13 requirements. This does not remove or replace the licenses of either upstream project or their dependencies. See the [GNU compatibility explanation](https://www.gnu.org/licenses/license-compatibility.en.html) and [AGPLv3 section13](https://www.gnu.org/licenses/agpl-3.0.en.html#section13).

VYRN's SignalStore implementation is licensed AGPL-3.0-only. No private keys, account credentials, private app data or signing keystores are corresponding-source inputs. Before distributing a binary, provide its complete corresponding source, reproducible overlays/build instructions and third-party notices. A complete audit and source export remain release requirements.
