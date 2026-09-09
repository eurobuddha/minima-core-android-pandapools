# PandaPools 0.9.46 public activity follow-up

## Device findings from 0.9.45

Installed the committed/pushed 0.9.45 APK on both S23 R3CW30FN1FM and Fold RFCY71KW3LX. My Activity showed green node-confirmed records in chronological order on both. On S23 it included the pool creation, two app consolidations, the Terminal consolidation, and the 175157.93892489164 MINIMA / 784.52331493 USDT withdrawal. The withdrawal's transaction header time was 8 September 2026 at 20:45:29 BST; node inclusion block 2304390 and 840 confirmations at tip 2305230. The old 9-hour/22-minute values were local observation ages, not transaction timestamps.

All Pools on the Fold still lacked the S23's September 8 transactions: HistorySync uses wallet-relevant history, which differs between wallets. This is a separate coverage gap, exposed by comparing the actual devices after 0.9.45 installation.

## Reuse and implementation

Read Block Explorer MainActivity.java searchAddress/searchTxpowThenAddress (lines 475–555) and its NodeApi, Casino's pool transaction lookup, core txpow.java and TxPoWSearcher.searchTxPoWAddress. Reuse the native Block Explorer command `txpow address:ADDRESS` via PandaPools' existing private IPC transport. It finds spends as well as outputs, so closed pools remain searchable within the node's retained chain.

PoolHistorySync queries up to four known addresses per batch, serially with a delay and a two-minute per-address cooldown. Candidates include live pools, preserved observation addresses and own pool records. Each result must contain the exact requested address in transaction inputs or outputs. Reuse HistoryEntry.from, the existing HistoryDb row codec, PandaAddrBook aliases and PoolActivity/TxClassifier reserve-flow accounting.

Schema v5 adds public_pool_tx without deleting or replacing the wallet table. Public entries never enter wallet queries or accounting exports. Exact transaction IDs deduplicate the All Pools view. Independent stock-node `txpow onchain` checks update both stores and rotate beyond their first page. Original header time, never local fetch time, labels the transaction. Lookup errors remain visible; empty-success is accepted only for a valid node array response.

## Validation and artifact

183 tests pass in both debug and release, zero failures/errors. Release lint and git diff --check pass. New tests cover spent-input-only withdrawal lookup, exact address membership, malformed/unrelated results, original header timestamps, absence of invented wallet delta and absence of invented confirmation proof. Reviewed lifecycle guards, serial callbacks, schema migration and confirmation preservation on refresh.

First and only APK: releases/pandapools-0.9.46.apk, versionCode 946.

SHA-256: 7dc9c08819bdcee196fc4ac2f30582684d2e14827c29b2479f21a5adb9e9d1df.

Verified family signing certificate SHA-256: eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f.

## Limits and device gate

Approved for read-only device validation after commit and verified GitHub push. Address lookup covers the stock node's retained chain, not a complete historical archive; old July headers missing from the S23 cannot be fabricated. The node's address reply is not byte-bounded by a max parameter. Existing private IPC-process containment remains in place and errors must be surfaced. This does not certify every possible node payload or the broader application's funds safety. No funds were spent, signed or consolidated by the agent.
