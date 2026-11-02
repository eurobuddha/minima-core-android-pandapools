# PandaPools session fixes — 2 November 2026

Implemented and built locally, then committed, pushed and published after explicit user authorization. See [the publication record](2026-11-02-session-publication.md). No installation, live trade, order cancellation, node reset, or wallet restore was performed during this fix pass. The Fold investigation used read-only UI and public chain evidence. The earlier Sirus withdrawal was reported successful by the user; this pass did not repeat it.

## Changes

- **Combined backups across Android PandaPools, Pandamonium, PandaPools MiniDapp and desktop:** one password-encrypted `.ppbackup` contains pool recipes and the native encrypted node backup. Uses the existing MVLT authenticated-encryption format and native backup command. Password required; exported bytes must be read back and verified before recording coverage. Wrong password, corruption, truncation, unsupported metadata, missing identity, and signing-state changes fail explicitly.
- **Backup after creation or migration:** prompts immediately and keeps a persistent requirement until the saved backup is verified. Another create/migrate is blocked while coverage is missing. Withdrawal remains available. A cancelled save, empty file, old recipe-only export, new pool, or failed preferences/database write cannot satisfy the requirement.
- **Reserve double-spends:** PandaPools, PandaDEX and Dexicon pipelines now persist input claims before broadcast, check exact unspent inputs including the node mempool before signing, and retain claims across restart. Submitted inputs do not become reusable merely because a timer or callback completed. Integrated Pandamonium tools also re-read their shared claim store at the posting boundary.
- **Dexicon auto-cross:** a durable pending receipt prevents another cross before settlement. Maker enablement and the relevant live orders are checked again before posting. Cancelled orders or disabled trading stop an unposted cross. Unknown broadcast outcomes remain paused across restart.
- **Truthful activity and history:** failed/unconfirmed Dexicon rows show quoted amounts rather than asserting receipt. Native History distinguishes direction from inclusion; a TxPoW creation height is not confirmation. Details query actual on-chain inclusion, and CSV labels the creation height and unverified inclusion explicitly.
- **Recovery:** retained and hardened the existing wallet/key/proof checks and bounded recovery journal. Pandamonium can fetch archived proofs from the user's designated `eurobuddha.com:9001` service. Missing proof or current signing-state evidence produces a specific stop; seed possession does not bypass these checks.

## Proven sources reused

Paths below are relative to `/Users/eurobuddha/Projects/minima/`.

- Existing native vault implementation and `apks/base` vault/backup transport were the source for `apks/pandapools/app/src/main/java/com/eurobuddha/pandapools/BackupVault.java`, `PoolBackupBundle.java`, `NodeBackupCapture.java`, and `CombinedBackupUi.java`. The bundle adds a versioned manifest and native-backup integrity checks around that encryption format.
- Existing `CoinLock`, `TxPost`, `DexTxn`, `FundingCoins`, and transaction-chain callbacks were extended in place in `apks/pandapools`, `apks/pandadex`, `apks/dexicon`, and their integrated base modules. Existing Pandamonium account-session checks and recovery-before-post hooks remain in place.
- Existing `apks/base/integrated/utxo` chain-inclusion checks informed `HistoryChainStatus` in standalone/integrated History.
- Existing `OwnerKeyRecovery`, `ReserveRecovery`, `RestoreExit`, `ExitStore`, and confirmed-collection handling remain the recovery building blocks in `apks/base/integrated/pandapools`.
- `mds/pandadex-mds` remains the donor for Dexicon's resting-order layer. Its updated shared files passed vendor parity. Shared PandaPools JavaScript backup/claim code is mirrored into `desktop/minimacore-desktop/main/pandapools` and checked for parity.

## Validation

All reported suites completed without failures. Counts below are the relevant debug suites, not sums of duplicate debug/release reports.

| Suite | Tests |
| --- | ---: |
| Standalone Android PandaPools | 336 |
| Integrated Pandamonium PandaPools | 406 |
| Native Dexicon | 642 |
| Native PandaDEX | 718 |
| Integrated PandaDEX | 762 |
| Standalone History | 25 |
| Integrated History | 19 |
| PandaPools MiniDapp recovery/backup | 38 |
| Desktop PandaPools | 35 |
| Actual JavaScript transaction engine input-claim tests | 2 |

Also passed: Dexicon MiniDapp full suite and vendor parity; PandaDEX MiniDapp suite; 27 real Rhino/H2 assertions; Java-to-JavaScript and JavaScript-to-Java encrypted-backup interoperability, including a Unicode password; relevant Android release lint and release builds; APK version and update-certificate checks; ZIP integrity and manifest/version checks; desktop source parity; scoped Pandamonium recovery/backup adapter replay; repository whitespace checks.

The Mac application and DMG are signed, notarized, stapled, and accepted by Gatekeeper. Test evidence is under `build/swap-investigation/`; crypto interoperability fixtures use dummy bytes, never a user's wallet.

## Packages

Each file has an adjacent SHA-256 checksum file. APKs use the existing family signing certificate, SHA-256 `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.

| Package | File |
| --- | --- |
| Pandamonium 2.2.7 / code 277 | [APK](/Users/eurobuddha/Projects/minima/apks/base/dist/minima-core-Pandamonium-2.2.7.apk) |
| PandaPools Android 0.9.74 / code 974 | [APK](/Users/eurobuddha/Projects/minima/apks/pandapools/dist/pandapools-0.9.74.apk) |
| Dexicon Android 0.1.57 / code 157 | [APK](/Users/eurobuddha/Projects/minima/apks/dexicon/dist/dexicon-0.1.57.apk) |
| PandaDEX Android 0.4.25 / code 425 | [APK](/Users/eurobuddha/Projects/minima/apks/pandadex/dist/pandadex-0.4.25.apk) |
| History Android 0.3.16 / code 316 | [APK](/Users/eurobuddha/Projects/minima/apks/history/dist/history-0.3.16.apk) |
| PandaPools MiniDapp 0.6.34 | [ZIP](/Users/eurobuddha/Projects/minima/mds/pandapools-mds/PandaPools_0.6.34.mds.zip) |
| Dexicon MiniDapp 0.0.50 | [ZIP](/Users/eurobuddha/Projects/minima/mds/dexicon/Dexicon-0.0.50.mds.zip) |
| PandaDEX MiniDapp 0.4.21 | [ZIP](/Users/eurobuddha/Projects/minima/mds/pandadex-mds/PandaDEX_0.4.21.mds.zip) |
| minimaCore desktop 0.18.32, Apple silicon | [DMG](/Users/eurobuddha/Projects/minima/desktop/minimacore-desktop/dist/minimaCore-0.18.32-arm64.dmg) |

## Limits and deployment status

- The new builds have not undergone an end-to-end physical-device combined backup and restore rehearsal. Automated tests and compilation do not establish that a particular user's wallet is recoverable. No real funds were moved to test this release.
- Restoring a bundled node backup deliberately hands off to the native node restore UI. It does not silently replace a running wallet. The extracted native backup remains password-encrypted.
- Automatic raw TCP archive-proof retrieval is implemented in **Pandamonium**, not every frontend. Browser MiniDapps cannot directly use that TCP service; they still need suitable node proofs, a configured compatible HTTPS proof service, or node resync. No alternative archive host was substituted.
- A restored backup can still lack current signing state. Classic one-time-signature counters and block-mode state are checked; neither a seed nor an old encrypted backup proves that signing is safe now.
- A submitted input with an uncertain outcome stays held. There is no new automatic timeout/retry or general manual claim-resolution UI. This deliberately trades availability for avoiding a second signature or double-spend.
- The full pre-existing Pandamonium importer still has source drift in `adapt-pandamonium-safety.py` (`boolean reset = false` anchor missing), before these adapters run. The affected recovery/backup layers were replayed in isolation and exactly reproduced their integrated sources; the full clean import is **not** claimed to pass.
- Existing unrelated working-tree changes, including graphify output, were preserved. Commits, pushes and publication were subsequently authorized and completed; see the publication record.

## Code Review

### Summary

The changes reuse the existing transaction, wallet, and recovery boundaries and add durable evidence before permitting subsequent actions. Review found and corrected storage-commit failures that could leave an in-memory backup-success flag, a detached Rhino command binding, missing post-create UI wiring, and a shared native input-claim race. Regression coverage exercises the actual transaction pipeline and persistence failures rather than only helper return values.

### Findings

No unresolved critical or major findings were identified in the reviewed changes. The following additional finding was fixed before delivery:

#### 🟡 MINOR — Fixed: writer could emit unsupported KDF parameters

**File:** `app/src/main/java/com/eurobuddha/pandapools/BackupVault.java`

**Problem:** The donor's generic byte-array encryption overload accepted a custom iteration count, while the backup reader intentionally accepted only the supported 210,000-round format. Combined-backup callers used the supported value, but a future caller could create an unreadable backup.

**Fix:** The writer now rejects unsupported iteration counts before deriving a key. Regression tests cover zero, low, adjacent, and excessive values. The integrated copy and canonical import source match.

### Verdict

✅ **Approve** for the implemented fixes and local build artifacts. Live-device recovery acceptance and the full-importer limitation remain explicit; this review is not a claim that every missing-state recovery can be automated.

## Artifact SHA-256 manifest

```text
d76e92392e322420ce59a688ebfaa0f0075a93b4f2a8640aa3b935a49f89f677  pandapools-0.9.74.apk
b4a320062db6063297bd4a3429c117c5d255e7f7bd3e3fdcec02baeac106245e  minima-core-Pandamonium-2.2.7.apk
1e603d604d7f8d09a5ee7a93165d4c96e572656cec7d3c803d730b42ba19e0cc  dexicon-0.1.57.apk
5df269e86145dbcfb1aed5261462f2197f71bc070dba6ddf01f749f3911ce0bf  pandadex-0.4.25.apk
47356ed592604e22f233ca385120254db71f76cc434d190f36a3ed6e8459903f  history-0.3.16.apk
9588125919e7ffdf8f7de1c8423d7154dd509b111664a61dc7620596d8bea83f  PandaPools_0.6.34.mds.zip
528bdc27c2e3273d6395a30a4d73a84a46044fc0359f6f5f661692d88be44320  Dexicon-0.0.50.mds.zip
bf8780ec133acc8c334c08da9b44cc3fe5e337c6de29e85b83aaa89a23b63be8  PandaDEX_0.4.21.mds.zip
72b869924e11e130198736ce5696343acdfcc558d6fd0fea385d6190a23338c8  minimaCore-0.18.32-arm64.dmg
```
