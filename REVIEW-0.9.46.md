# PandaPools 0.9.46 public activity follow-up

## Device findings from 0.9.45

Installed the committed/pushed 0.9.45 APK on both S23 R3CW30FN1FM and Fold RFCY71KW3LX. My Activity showed green node-confirmed records in chronological order on both. On S23 it included the pool creation, two app consolidations, the Terminal consolidation, and the 175157.93892489164 MINIMA / 784.52331493 USDT withdrawal. The withdrawal's transaction header time was 8 September 2026 at 20:45:29 BST; the exact inclusion block is established by the 0.9.46 node diagnostics below (the transaction header block must not be used as its inclusion block). The old 9-hour/22-minute values were local observation ages, not transaction timestamps.

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


## 0.9.46 S23 validation — 9 September 2026, 08:50–08:52 BST

GitHub branch review/0.8.9 was verified at source commit 364788316c89e9d1df56c9cf7dac4b106d1bf960 before installation. S23 package metadata confirms version 0.9.46/code 946, update time 08:49:56. Stock MinimaCore remains 1.2.6/code 20.

Both My Activity and All Pools show the 784.52331493 USDT withdrawal at 8 September 20:45:29 GMT+01:00. Full TxPoW: 0x000112EF02925EEFBA48266A1BBD0BEA6BE93DFFDEAA0437E928CDDA79188705. Independent node replies logged found=true, inclusion block **2304392**, and successive confirmations/tips **851/2305243**, **852/2305244**, **853/2305245**. This establishes actively advancing checks rather than a static green label. The earlier header block is not the inclusion block.

Public txpow lookups returned successful 44,339- and 19,945-character replies. The completed All Pools screen has no lookup-incomplete banner and displays 853 withdrawal confirmations and 685 pool-creation confirmations. Main process 15819 and private IPC process 15903 remained running; the inspected application diagnostics contained no fatal exception. Screen evidence: /private/tmp/pandapools-046-activity.xml and /private/tmp/pandapools-046-global-complete.xml.

Fold RFCY71KW3LX disconnected from ADB before the 0.9.46 installation and remains on the previously verified 0.9.45. Its 0.9.46 installation and cross-wallet All Pools validation are pending reconnection; they are not claimed complete.
