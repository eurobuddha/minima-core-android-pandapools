# Code Review — 0.9.43

## Summary
This update restores the node's standard consolidation and fixes live confirmation display/reconciliation. It follows `../utxo/app/src/main/java/com/eurobuddha/utxo/WalletTools.java` and the cached resolver workflow in that app's `HistoryDb.java`. Transaction matching uses exact immutable transaction IDs; inclusion and depth come from `txpow onchain`, checked against the decompiled stock MinimaCore 1.2.6 command. The node remains unchanged.

## Findings addressed
- **Major:** `WalletTools.java` added `maxsigs:1` and rejected previews outside 3–8 inputs. The standard node can validly choose fewer inputs after its signature limit. Removed this preview and custom merge; use the user-requested command unchanged, behind `TxPost`'s signing guard.
- **Major:** confirmation results repainted Activity but not Swap's recent-activity strip. Both repaint after node checks now. Verification also runs when the displayed chain height stays unchanged.
- **Major:** the cached history schema discarded transaction identity. A transaction fetched before its receipt could remain unmatched indefinitely. Schema v3 preserves identity and verification evidence; the existing non-destructive repair/backfill workflow fills retained history.
- **Minor:** confirmed receipts hid their numeric depth; the displayed value is now the actual node-returned count, with no three-confirmation cap.

## Remaining limits
- Early prototype receipts may contain only a pre-mining TxPoW ID and human text. If the node cannot find that ID, their identity cannot safely be recovered from amount/time alone. They remain receipts needing matching; actual mined consolidation/history rows are separately verified.
- History lookup is still one full TxPoW per IPC request. Serial IPC avoids this app's simultaneous reply bursts, but a count of one is not a byte-size guarantee on stock MinimaCore's inline transport.
- The wider review's MDS/desktop parity and public-release gates in `REVIEW-0.9.42.md` remain outstanding.

## Validation
JVM tests and release lint run before packaging. Device upgrade and node-evidence results will be recorded after installation from the committed, pushed source. No mainnet transaction is submitted by the reviewer as part of this update.

## Verdict
Request changes for the full public release until the wider review and parity gates are complete. This is a native corrective checkpoint for the user's S23 validation.
