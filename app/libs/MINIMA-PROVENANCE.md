# Minima codecs for archive recovery

PandaPools uses the same Minima wire codecs as Pandamonium, from `apks/base/app/libs/minima.jar`.
The pinned jar is built from `_worktrees/minima-core-keets-android` (Keets Android node source), with
`megammrsync action:proofs` and `MegaArchiveProofs` added for this release. No embedded node is started
by standalone PandaPools. The archive reader requests only the public pool address from eurobuddha.com:9001.

SHA-256: `2fc973bcbf37ebc8581e25a2a2b0669ffe92d528fb28af47d5d5582265d59532`.

`ArchiveHardeningTest` checks the app adapter; `CoreArchiveProofTest` checks the packaged node command
and parser. `scripts/check-recovery-parity.py` compares the shared recovery engine against Pandamonium.
