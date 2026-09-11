# Verified pool recovery and signing state

Expired proof snapshots and a light node's empty coin lookup previously left owners without an actionable recovery path. Restore could report success after failed imports and regenerate keys using an estimated signing counter. The updated flow preserves the covenant recipe, finds and validates current reserves, reports only verified recovery, and requires explicit confirmation of current complete wallet signing state before owner signing resumes.

Reuse: the native recovery/key/funding helpers; MDS and Desktop Covenant, Decimal, Curve, Store and PoolMgr; Desktop's existing bounded netfetch and atomic SQL image writer. The shared reserve coordinator is byte-identical across MDS and Desktop. Background upkeep now uses the oldest verified leg, a KMIN completeness check, and current ownership before tracking cleanup. Legacy records are held on upgrade; ordinary rediscovery preserves an already confirmed state.

Validation completed on 2026-09-09:

- Native: 205 tests in each Debug and Release variant, no failures; both lint checks and release APK build passed. The APK certificate matches the family release signer.
- JavaScript: 22 exact-source recovery/UI tests and two Desktop lifecycle tests. Coverage includes stale/wrong/missing proofs, import/archive failure, oversized archive streams, crowded addresses, moving backup reserves, key-state races, disk failures, legacy migration, delayed cleanup and unresolved cards.
- Existing activity suite: 18 regressions passed; 12 shared files pass byte parity.
- Packaged MDS: dapp.conf is first; service bytes equal the reviewed concatenation. Minima's Rhino 1.7.14 executes the bundle without browser timers. H2 2.4.240 validates migration and monotonic merge SQL, including legacy-held and new-trusted rows.
- Independent Android, MDS and Desktop adversarial reviews all approved after fixes and repeat review. Concurrent Desktop Casino edits were preserved by a three-way rebase against hash-verified originals.

Limits: public pool recipes contain no private keys or current signing state. Recovery requires the matching complete wallet state and available chain proofs. No default public archive is assumed. The optional MDS HTTPS archive must allow browser CORS requests; its streaming reader is capped and cancellable. Native/Desktop use their bounded HTTP clients. The app cannot keep pools fresh while both it and the node are offline.

Visual QA is unverified: browser security policy rejected the local-file preview. Source-level UI checks passed. No new build was installed on a live wallet during validation.

Publication verified on 2026-09-11:

- Android [0.9.47](https://github.com/eurobuddha/minima-core-android-pandapools/releases/tag/v0.9.47), source commit `d2e9a062d1ccea92c2c5506878e3bfe329674eb9`, is pushed and live in PandaApps. The downloaded APK matches the catalog and release SHA-256 `ea748704194fa789ad41c5dab3c52279edda10c7f2a163b9039b99e943a1587e`.
- MiniDapp [0.6.23](https://github.com/eurobuddha/pandapools-mds/releases/tag/v0.6.23) is live in both the primary and mirror PandaDapps catalogs. The downloaded ZIP matches their SHA-256 `97630a77e184546d925b0a7026c0d2a967cd5789ec9047d0844bb1c192e79d86`.
- Both PandaPools versions and hashes are present in IPFS snapshot `bafybeifi2b3garek2q2st6okzb4uigvwdlvbdqk3gpp3uwdnbeclcpxtre`.
- Desktop recovery shipped in [0.16.54](https://github.com/eurobuddha/minimacore-desktop/releases/tag/v0.16.54). The current 0.16.79 tag descends from that release commit; its Mac, Windows and Linux update-feed entries match the current PandaApps catalog URLs and hashes. No older desktop version was republished over the current release.

This publication check does not change the validation limits above.
