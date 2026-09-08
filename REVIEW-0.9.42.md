# Code Review — PandaPools 0.9.42 candidate

Review date: 8 September 2026. Scope implemented here: native Android PandaPools, with the S23's stock MinimaCore connection as the compatibility target. Version 0.9.42 / code 942 is the source checkpoint for this review; full device validation and public release remain pending.

## Summary

The review started from `astra.md`, the native and family graphs, the local Minima references, the existing transaction builders and their tests. The most consequential fixes protect signing state, preserve uncertainty after lost replies, validate complete pool contracts, and distinguish real chain confirmation from a local submission. Funding now uses proactive small coin queries and offers reviewed consolidation. The existing covenant-generation bytes and transaction layouts remain the basis of the implementation.

The review is **not a release approval**. In particular, the stock node's inline IPC transport can still reject an individually oversized reply. Small coin counts reduce exposure but cannot prove a byte bound. The user confirmed that they approved the observed consolidation, and reported using Terminal and PandaPools for approximately three consolidations. The wallet subsequently reported zero unconfirmed balance; exact transaction-by-transaction reconciliation remains pending.

Versioning correction: several distinct local APKs were incorrectly built and installed using 0.9.41 / code 941 before a source commit. That violated the repository rule. Those installations must not be treated as one reproducible build. The complete current source is checkpointed as 0.9.42 / code 942; subsequent source changes require another version increment before building/installing.

## Plan and progress

1. Read the other agent's plan, graph and named node/UTXO sources; compare their claims with actual code and device logs — completed.
2. Harden funding, signing serialization, input reservations and ambiguous-write handling — implemented and unit checked.
3. Review recovery, contract recognition, history, metadata fetching and novice-facing transaction messages — fixes implemented and unit checked where applicable.
4. Build and exercise the native candidate against stock MinimaCore — initial install and read-only connection/wallet checks completed; latest changes require installation and device verification.
5. Reconcile the consolidation event, then verify creation, swap, owner lifecycle, interrupted writes and confirmations on the S23 — pending. No further fund-moving device action is authorized by a preview alone.
6. Review and port applicable behavior to MDS/desktop, including the existing signing-lock parity gap described by `astra.md` — pending; no parity or cross-surface release claim is made here.
7. Commit and push each versioned source checkpoint before installation. Finish device/adversarial checks and applicable family work before public release; a source checkpoint is not release approval.

## Findings and fixes

### 🔴 CRITICAL — Uncertain signing could overlap a subsequent chain

**Files:** `TxPost.java`, `NodeApi.java`, `CmdChain.java`.

**Problem:** A whole-chain watchdog could release the signing queue while the node was still signing. Lost write replies and process death did not durably prevent a later signing request.

**Fix implemented:** Removed the elapsed-time release, made completion idempotent, and persist a write marker before dispatch. Missing, oversized or incomplete replies retain the marker and pause new signing. Cleanup avoids deleting a transaction whose write outcome is unknown. Existing chains can finish their callbacks across Activity destruction. This coordinates this app's process; it does not lock other apps using the same node.

### 🔴 CRITICAL — Recipes and block estimates cannot restore current WOTS state

**Files:** `OwnerKeyRecovery.java`, `Recovery.java`, `OwnPoolStore.java`, `MyLpView.java`.

**Problem:** Regenerating owner keys and advancing counters from historical recipe/block estimates cannot establish the latest one-time-signature usage.

**Fix implemented:** Owner-key checks are read-only and fail closed when unavailable. Recipe restore never regenerates signing keys or guesses usage counters. Guidance requires the matching, current MinimaCore wallet backup. Create/migrate require a durable recipe write before funding. Restore validates fields, the complete contract and the node-derived address before registration/import. A successful recipe import is not described as complete wallet recovery.

### 🔴 CRITICAL — Contract snippets were treated as pool recognition

**Files:** `PoolBook.java`, `PoolCovenant.java`, `Recovery.java`.

**Problem:** An unrelated tracked contract could contain the two recognised snippets. Backup metadata could also disagree with the supplied script.

**Fix implemented:** Compare the complete existing covenant template, with its recorded fractional fee on both legs; then derive the address from the actual script. Validate public parameters before constructing commands. Preserve the actual tracked script for later recovery. Reuse `ReAnnouncer.key` so pool identity includes the owner payout address. Malformed or state-bearing reserve coins are skipped, and token amounts must be human token amounts.

### 🟠 MAJOR — Funding replies were unbounded and errors could look like no funds

**Files:** `FundingCoins.java`, `PoolManager.java`, `PoolTxn.java`, `WalletView.java`, `WalletTools.java`.

**Fix implemented:** Read token coin counts before enumeration. Fetch token/address groups of at most eight reported coins, recheck address counts, select the largest available stateless coins, and cap complete transactions at twenty inputs. Oversized address groups are skipped with an explanation. Unknown or failed counts are errors, not empty wallets. Small consolidation previews use `consolidate ... burn:0 dryrun:true`; confirmation builds those exact inputs through the shared signing/check/post path. The count thresholds are conservative application policy, **not** Minima consensus limits or proof of Binder safety.

### 🟠 MAJOR — Queued inputs could lose their reservations

**Files:** `CoinLock.java`, `TxPost.java`.

**Fix implemented:** Claim the complete input set atomically before queuing, reject duplicate/already-queued inputs, normalize identifier case, and retain claims until completion rather than allowing selection TTL expiry to free them. Restart the short reservation period after completion. Failed second-leg selection releases its first-leg funding reservations.

### 🟠 MAJOR — Submitted transactions could be labelled confirmed or failed without evidence

**Files:** `ActivityLog.java`, `HistorySync.java`, `ActivityView.java`, `SwapView.java`, `MyLpView.java`.

**Fix implemented:** Require `txpow onchain` evidence and node-reported confirmation depth. Match an asynchronously mined TxPoW to its submission using the immutable transaction ID, rather than a pre-mining TxPoW ID or a partial input/output match. Unknown outcomes stay unknown. Historical receipts lacking that transaction ID cannot be retroactively proven by this change. Swap confirmation shows the full transaction amounts; positive MINIMA change is returned even below the former dust threshold.

### 🟠 MAJOR — Metadata redirects could access private hosts

**Files:** `NetFetch.java`, `ImageLoader.java`, `WebValidate.java`.

**Fix implemented:** Reuse UTXO's bounded HTTP fetcher, revalidating every redirect and all resolved host addresses. Enforce protocol, byte and hop limits. The donor's DNS check/connect race remains a known limitation; this is not a claim of complete DNS-rebinding protection.

## Reuse record

| Source inspected | Reuse / necessary adaptation |
|---|---|
| `../utxo/app/src/main/java/com/eurobuddha/utxo/CoinLoader.java` | Token/address query tiers, moved before the first large reply; native funding excludes contract and owner payout addresses. |
| `../utxo/app/src/main/java/com/eurobuddha/utxo/WalletTools.java` | Consolidation command and exact-input construction; adapted to PandaPools' `TxPost`, dialog themes and status-false callback behavior. |
| `../pandadex/app/src/main/java/com/eurobuddha/pandadex/DexTxn.java` | Largest-first, stateless, mempool-aware funding; retained PandaPools' shared reservations and smaller mobile limits. |
| `../pandadex/app/src/main/java/com/eurobuddha/pandadex/SignGate.java` and tests | Compared queue/idempotent completion behavior. Its timed release is unsuitable while a node signing operation may still be active. |
| `../utxo/app/src/main/java/com/eurobuddha/utxo/NetFetch.java` and callers | Copied the fetcher with package/user-agent adaptation. |
| UTXO `HistoryDb.resolve`, `TxnUtil`, and Minima `Transaction.toJSON` | Reused the submission-to-mined-history workflow; exact transaction identity avoids the donor's partial-match ambiguity. |
| Native `CoinLock`, `PoolCovenant`, `ReAnnouncer`, transaction builders and tests | Extended the existing blocks instead of replacing covenant generation, layouts or pool math. |

## Two different size failures

The retained S23 system logs show `TransactionTooLargeException`, parcel size **272,932 bytes**, delivering `org.minimarex.minimacore.RESPONSE` to PandaPools, followed by an undelivered-broadcast process kill. The logs alone do not identify the originating node command; the create/funding attribution comes from `astra.md` and the source path, not a logged command identity.

No `TxPoW size too large` message was found in those retained logs. That separate rejection is checked in `../../core/minima-core/src/org/minima/system/commands/txn/txnpost.java`, before posting to the asynchronous miner, against the current chain limit. The stock APK's decompiled `txnpost` has the same check. The candidate preserves the node's exact measured size/limit when this error occurs. `txncheck` passes and coin-count limits do not replace the final signed-TxPoW size check.

Stock MinimaCore 1.2.6 returned `Command not found` to `magic` through this connection. The candidate therefore falls back to a compact chain-tip connection check and honestly reports that the separate size query is unavailable.

## Verification evidence

- S23 Ultra **SM-S918B**, MinimaCore **1.2.6 / code 20**. The node APK/configuration was not changed.
- An earlier 0.9.41 candidate installed successfully over 0.9.40 with the family signing certificate and opened against the running node. Wallet reads showed **81 MINIMA coins** and **118 USDT coins** before consolidation. This does not prove every transaction workflow.
- At **22:33:40 London**, a preview `consolidate` completed. Logs subsequently show `txnsign` at **22:33:46** and successful `txnpost` reply at **22:33:56**. The user confirmed approving the consolidation. Subsequent wallet reads showed 59 MINIMA coins and zero unconfirmed balance. Exact on-chain matching of each consolidation remains pending.
- A further undelivered-broadcast kill occurred at **22:50:06**, with a **205,820-byte** parcel, following a burst of pool-coin queries while history was pending. Added a process-wide serial IPC queue, adapted from the existing TxPost queue mechanics, and reply-character-count diagnostics. This removes app-generated reply bursts without claiming a per-reply byte cap. Startup/history/wallet reads completed with the scheduler in the final local 0.9.41 prototype; that prototype is superseded by the uniquely versioned 0.9.42 checkpoint.
- `./gradlew test lintRelease assembleRelease`: passing before the version-only correction. **166 tests per build variant**, no failures/errors/skips; lint has existing/nonfatal warnings, not a clean zero-warning report.
- Tests cover funding boundaries/errors, state and token amounts, consolidation input validation, signing completion, queued input conflicts, strict transaction checks, uncertain outcomes, contract/recipe rejection, transaction identity, confirmation evidence, and blocked network destinations, alongside the existing math/router/history suite.
- Full lifecycle verification on stock MinimaCore and MDS/desktop parity remain pending.

## Remaining release gates

### 🟠 MAJOR — Stock IPC still has unbounded individual replies

**Files:** `HistorySync.java`, `PoolBook.java`, `NodeApi.java` and the stock node's response transport.

**Problem:** A history page of one, a script list, a crowded registry/pool address or unusually large token metadata can still exceed the legacy inline transport. A before/after count check also cannot provide a transactional snapshot. An app cannot catch a reply which the OS fails to deliver.

**Required follow-up:** Validate the latest build on the target wallet and retain command-stage/exit diagnostics. Broader guarantees require a node-supported byte-bounded response transport or compact/paged command support; this review has not changed stock MinimaCore or claimed that guarantee.

### 🟠 MAJOR — Device outcome and family parity are unfinished

**Required follow-up:** Match the individual consolidation results to node history. Install the uniquely versioned candidate, verify the complete native lifecycle and interruption behavior, then complete applicable MDS/desktop fixes and their own validation. Unit tests and a successful install do not establish funds safety or cross-surface parity.

## Verdict

**🔁 Request changes before public release.** The native candidate addresses substantial source-level defects and passes its local checks. The remaining device evidence, legacy transport exposure and family parity must remain visible rather than being presented as completed validation.
