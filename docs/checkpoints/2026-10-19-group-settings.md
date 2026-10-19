# Basic-group roles and confirmed native settings

Accepted on the API30 emulator on 2026-10-19. The full project remains in development.

The native basic-group model now gives backend administrators the supported change-info, delete-message, remove-member and invite rights. Only the owner receives the add-admins right. The native admin flags and serialization flag are set immediately, so both the live UI and a serialized/reloaded chat recognize the role.

The original ChatEditActivity uses a basic-group title request and closes only after a confirmed update. The request captures the account, user and session; another account/session cannot receive its result. A failed or uncertain save keeps the entered title and the previously confirmed cache, releases the spinner and allows explicit retry. Unsupported description/photo, supergroup/history/forum, permissions, invitation-link/request, reaction-policy, log, sticker, community and deletion controls are hidden on this screen. Supported administrator/member rows retain the native role-based visibility. This is a capability gate, not implementation of those missing features.

## Evidence

- `artifacts/android-group-settings-ui-api30.json`: **72 recorded checks / 50 distinct names**, passed at 22:52:53+03:30. Native registration fixture; admin rights and TL serialization; original editor, IME input and actual Done touch; server-confirmed rename and native cache; TTL and membership preservation; actual transport outage, retained input/no optimistic title, explicit reconnect/retry; demoted-member disabled/missing save controls; owner role-management right.
- `artifacts/android-reaction-read-api30.json`: **107 recorded checks**, passed at 22:54:14+03:30 on the same artifacts, including the original reaction chooser and offline reconciliation/retry regression.
- Main local-TLS APK SHA256: `268d0062bc28028ae3e577635188e1f08236c1c4f29bc59077263d6c8692f028`.
- Test APK SHA256: `2d8041cec11148a687df672c37ff812a24ee093940c659a062979a8d3a9fe8ea`.

The successful drivers printed PASS and wrote matching reports. Their actual workers finished; identity-verified childless RTK wrappers22872 and22904 were stopped afterward. A clean outer-wrapper exit is not asserted. The emulator was shut down after the sequential suites; local HTTPS readiness returned200.

The first build caught a missing fully-qualified ConnectionsManager reference. An initial run before emulator boot never reached instrumentation. The first instrumentation run exposed a null Done button in the new gate for an ordinary member; the gate now accepts its native absence. A subsequent test incorrectly expected administrator rows for members; the assertion now follows native visibility. Another run lost the app window to Home during fixture entry. The fixture now explicitly brings its activity forward, uses the existing transition/prompt handling and requires real window visibility/focus before interacting. These incomplete runs are not counted as acceptance or complete fixture cleanup. The final suite logs out its fixture owner in `finally`.

The successful full build took3m03s; final test-only rebuild38s. Builds used one Gradle worker, two JVM processors and a3GB heap with the emulator off; tests used one1536MB/two-core emulator. No physical phone was accessed.

## Boundaries and delivery

Editor entry and group creation/promotion/demotion are fixtures, not proof of full screen-driven group creation/profile/member management. Role changes while an editor is open, response loss after a committed rename, account/lifecycle races, group photos/description, granular permissions, links, leaving/deleting groups and physical devices remain unverified or unimplemented.

The existing downloadable ARM Debug prerelease predates these group-setting changes; these exact results apply to the local-TLS artifacts above. No normal ARM APK, VPS, backend, DNS, signing, TURN or offsite backup was changed in this checkpoint. The private GitHub repository and previous draft checkpoint's CI were independently read back through the connected GitHub API; the latter completed successfully. The desktop `gh` credential had expired, so source delivery uses the already connected GitHub account instead of requesting or printing a token.
