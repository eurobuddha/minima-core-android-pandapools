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
