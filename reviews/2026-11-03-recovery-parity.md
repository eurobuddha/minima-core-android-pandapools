# Recovery parity and MDS requirements — 3 November 2026

## Delivered changes

Standalone PandaPools 0.9.75 reuses 16 Pandamonium recovery engine files byte-for-byte: pinned archive
proof retrieval, existing-wallet recovery, bounded seed recovery, durable close/collection attempts,
exact transaction identity and fresh chain confirmation before completion. Its IPC transport remains
standalone. Interrupted-write acknowledgment also pins the reviewed marker.

MDS PandaPools 0.6.36 requires WRITE trust and the public block-as-key-uses probe, failing closed for
unknown/pending/error responses. Checks cover UI startup, background service and writes. A required
hosting acknowledgment explains continuous power/connectivity, Android background operation, backup
requirements and offline recovery risk.

MDS guided recovery restores recipes, retrieves missing proofs through the node, and offers
Recover & withdraw for an explicitly attested preserved wallet. It retains normal signing quarantine;
only recovery's owner/payout inputs can pass the bounded signing path. Durable primary-key claims
prevent repeated close/collection signatures, including interrupted replies. Completion requires the
exact immutable transaction hashes to match mined transactions, fresh confirmation and an empty
payout address. Keep the recovery screen open; after interruption reopen the same backup and attest
the preserved wallet to reconcile the journal. Missing signing history is never guessed.

Pandamonium 2.2.8 adds `megammrsync action:proofs address:<pool>` using the same bounded archive reader.
The host is pinned to eurobuddha.com:9001; this command never installs chain data, peers or wallet state.
The new command is also compiled against desktop's own node source/API; all unrelated jar entries were
verified unchanged. Full resync is a guided, explicit fallback, never triggered by opening a backup.

Desktop 0.18.33 carries 19 byte-identical shared MDS engine files and the proof-only node command.
MiniHub-only mode policy is isolated in the desktop shim; its existing node-key-mode policy remains.

## Verification

- Standalone: 387 JVM tests, zero failures; release APK built and signed with the existing family key.
- Integrated PandaPools: 406 JVM tests, zero failures; Pandamonium release build passed Keets ABI checks.
- MDS: complete suite passed, including lost-reply recovery, failed durable claims, confirmation,
  READ/locked/unknown mode, mode changes, actual recovery signing inputs, and archive identity checks.
- Desktop: recovery/signing suite passed; all 19 shared engine files matched the MDS source.
- MDS READ-only startup was inspected in Chrome against a mock node; app content remained hidden.
- Tests used synthetic fixtures. No live transactions, wallet restores, node resets or device installs.

## Code review

The review caught and fixed: obsolete manual-archive assumptions; loss of detailed errors in the
recovery UI; malformed journal acceptance; duplicate collection claims across contexts; early payout
collection; callback/fixture drift; and desktop's older node API incompatibility. All normal restored
signing holds remain enforced outside the explicitly accepted recovery path.

The pre-existing full Pandamonium importer has an unrelated missing anchor in
`adapt-pandamonium-safety.py` (`boolean reset = false`), documented in the prior review. No full clean
import is claimed. The shipped shared recovery engine is checked directly by the new parity script.

## Limits

Automated recovery cannot manufacture missing keys, unknown historical signing state or valid proofs.
The MDS flow supports the usual preserved-wallet case; seed-only situations stop with instructions.
A full resync interrupts all applications and must preserve the correct wallet/key mode. Runtime proof
requests require the newly packaged node support; older nodes get an actionable error.

## Publication — 2026-11-03

Published releases and verified GitHub asset SHA-256 digests against the tested local files:

- PandaPools 0.9.75 — commit `00ded0c`; APK SHA-256
  `2faf660ff9dcab8958c381a2b0c239f5d3e8a281eed6c9bdb4aed5b4b7b4c45e`.
- PandaPools MDS 0.6.36 — commit `d7adf101`; ZIP SHA-256
  `b2318f30a9a3ee4512fc166ebf79206f9ecde320c17d2bad09ef64953ce1c69d`.
- Pandamonium 2.2.8 — commit `3bb59e2d`; APK SHA-256
  `25b5160b88c2c5d8f76a1fbf9897847ef7d21d3844c522e5011bcf1d50a17f97`.
- Android node source `4b9d5853e3` pushed to fork/keets-android;
  desktop node source `4954c4cee7` pushed to fork/keets.

Android catalog commit `9256fd1` passed all 58 entries / 57 binary checks and was deployed with a
compare-and-swap hash guard. MDS store commit `7e9a81a` was likewise deployed; origin and Sally mirror
both verified PandaPools 0.6.36. Unrelated graph/cache/worktree changes were not included.

Desktop 0.18.33 source commit `ad3d845` and tag were pushed. GitHub Actions run `41616329505` passed
all three platforms; CI published Windows and Linux assets. The local signed Mac build stopped at
notarization: `No Keychain password item found for profile: minimadesk`. No unsigned Mac installer
was substituted. The updater feed deliberately remains at the complete 0.18.32 release. Restore the
existing Apple notary profile, rerun `npm run dist:mac:signed`, then use the existing
`scripts/release-desktop.sh 0.18.33` procedure and verify all three feed rows before updating catalogs.

User-facing MDS route: update the node to Pandamonium 2.2.8 (or equivalent proof-command support),
install MDS 0.6.36 with WRITE trust, pass the block-as-key-uses/startup checks, import the backup,
confirm the existing matching wallet's current signing state, and choose **Recover & withdraw**.
Keep the screen open through confirmation/collection. After interruption, reopening the same backup
resumes the durable journal. Classic funded wallets must use native existing-wallet recovery; do not
change their key mode merely to pass the MDS gate.

IPFS publication completed and the immutable public catalogs were checked for all three versions and
matching SHA-256 values at CID
`bafybeidryx37xkgpkf76azmqzuj7dagg2yh233s3cskwlqxhqjg2mjnidm`.
The server completion marker was updated after IPNS publication at 2026-11-03 10:18:18 UTC.
