# PandaPools 0.9.45 Activity review

## Findings addressed

- **Major — confirmed rows appeared missing:** ActivityView grouped every unconfirmed legacy receipt before all confirmed receipts, whereas Swap used newest receipts directly. ActivityTimeline now joins exact mined IDs and sorts by transaction time (or explicitly labeled submission time when no matching history exists). No receipt is removed because of age or status.
- **Major — device observation time misrepresented as transaction time:** GlobalFeed stamped reserve differences/disappearances with System.currentTimeMillis(). ActivityView displayed that as the withdrawal/trade time. The same old closed pool could therefore appear hours apart on two phones. New public events come from HistoryEntry inputs/outputs and header timemilli. Snapshot ingestion is no longer called. Legacy observations are retained but explicitly unverified, with their observation time labeled and transaction time unknown.
- **Major — unreachable checks/rows:** ActivityLog only rotated through the newest 150 history entries. It now pages through the whole store and additionally prioritizes displayed IDs. The UI loads the stored chronology and renders a widening 60-row window. A transport error stops that batch and is shown visibly; the next poll retries.

## Reuse

Read the project graph, ActivityView, ActivityLog, GlobalFeed, HistoryDb, HistorySync, HistoryEntry, PandaAddrBook, TxClassifier and TxClassifierTest. Inspected sibling UTXO HistoryView and its stored resolver/Show more flow, and the stock txpow command. The public pool event adapter directly uses TxClassifier.poolFlows; the exact-ID timeline joins the existing receipt and HistoryEntry models without adding another store. Confirmation remains the existing independent stock-node txpow onchain query. Different timestamps/amounts alone never match transaction identities.

## Validation

180 tests pass in each debug and release variant, zero failures/errors; lintRelease passes. New tests cover chronological placement, exact-ID joining, nonmatching equal amounts/times, preservation of failures and more than 150 rows, identical transaction timestamps despite different device sync times, third-party swaps, create/add/maintenance distinctions, and no invented inclusion proof.

First and only APK for this version: releases/pandapools-0.9.45.apk.
SHA-256: d95db1b5c4b7199aa9db968dc28f41ae28fa2edcea39f7e165b46bce77e7d8f4.

## Verdict and limits

Approve for on-device validation. Node history coverage can differ by device; the app must state uncertainty when the node lacks a transaction. This change does not fetch archived July headers. Pool event amounts/times require stored transaction evidence and confirmations require a node lookup. Older snapshot observations do not gain transaction identity merely because similar amounts exist in history. No fund-spending command was submitted during development. Source is to be committed and pushed before installing this APK on either phone.
