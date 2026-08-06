# Changelog

All notable changes to the **PandaPools** native Android app. Newest first. Each release is a debug-signed APK
published to the [PandaApps catalog](https://github.com/eurobuddha/minima-core-apks) (`apks.json`) and tagged here.

The app shares one mainnet + `PANDAPOOLS` registry + 0.5% covenant with the [PandaPools MiniDapp](https://github.com/eurobuddha/pandapools-mds)
and the minimaCore Desktop "Pools" tab — the three trade the **same** live pools, so on-chain-affecting changes are
mirrored across all three.

> The repository was git-initialised at **0.8.9**; earlier source survives only as published APKs. Versions below are
> the tracked releases — see the [GitHub Releases](../../releases) for the canonical published list.

---

## [0.9.24] — stop the runaway owner-key hunt (bounded, remembered, provable)
- **Fixed** the owner-key hunt minting keys without limit. `OwnerKeyRecovery` re-mints a missing `$OPK` by re-issuing `newaddress` until the pubkey reappears — correct on the pool's own seed, **unwinnable on any other**: a backup restored from a different node (or a recipe carried across devices) carries an `$OPK` this seed can never derive, so every owner action (Withdraw, Migrate, Collect, restore) burned up to `MAX_NEW_KEYS = 256` fresh keys, forever — there is no key-delete command, and Minima keys are permanent wallet rows. Confirmed in the wild: a live node carrying 230 keys = the 64 defaults + 166 from one hunt interrupted mid-way by an IPC error. One foreign recipe also poisoned every Collect, which passes **all** recorded opks.
- **Added** a persistent **hunt ledger** (`HuntLedger`): at most 256 keys are ever minted hunting one `$OPK` on one seed, across all hunts — charged per mint, so an interrupted hunt resumes with only its remainder. The seed is identified by a fingerprint (the pubkey of the wallet's lowest-`modifier` key — the `modifier` IS the derivation index; there is no `default` field in `keys` output). Re-seeding re-opens the budget, deliberately: even the correct seed's restore rebuilds only the 64 defaults and still needs its hunt.
- **Added** `kidx` — the owner key's derivation index — to recipes (`OwnPoolStore`) and the backup (**format v3**), captured for free from `newaddress`'s `total` at create and from the key row at backup time. With it the hunt mints **exactly** to the key's index, and a wallet already past that index without holding the key **proves** a foreign seed with **zero** mints.
- Exhausted or proven-foreign keys are **reported, never retried**: Withdraw/Migrate abort with "this pool's owner key belongs to a different seed", Collect proceeds for the other pools and says how many were skipped, and restore says it instead of the misleading "could not restore the owner key's usage".
- Hunts are **serialised** behind a gate (like 0.9.22's signing gate, stall-watchdogged so a dropped callback can't wedge it) — overlapping callers can no longer interleave mints or double-charge the ledger.
- The rules live in a pure `HuntBudget` (21 new JVM tests; the JS mirrors carry byte-mirrored owner-key sections and the Java tests are their spec, plus 28 end-to-end mock-node simulations of the exact shipped JS). Existing junk keys can't be deleted — they're inert; this stops the growth.
- Hardened after three adversarial review passes: a failed `newaddress` (locked vault, denied permission) never charges the budget; an unreadable `keys` reply never hunts blind; kidx values from backups are strictly validated (a malformed `"kidx": null` must never brand a legitimate key foreign); a hard per-run mint backstop makes termination independent of the ledger; and a backup now backfills kidx into local recipes for pre-v3 pools.
- Released 3-way with MDS **0.6.18** + desktop **0.16.11**.

## [0.9.23] — carry the owner key's signature count through backup and restore
- **Fixed** the last key-reuse path. A pool's owner key (`$OPK`) is minted with `newaddress`, so a seed-only re-sync doesn't bring it back — only the 64 defaults are rebuilt. `OwnerKeyRecovery` re-mints it correctly, but the node inserts **every** new key at `uses = 0` (`Wallet.createNewKey`), so the next owner action re-signed leaves the pre-restore node had already spent. Signing one Winternitz leaf twice leaks its private key. Deterministic, not a race.
- **Added** `opkuses` + `atblock` to the backup (**format v2**): the owner key's real signature count, read from the node, and the height it was read at. On restore the target is `count + elapsed blocks ÷ REFRESH_BLOCKS + slack` — every term measured or derived, none invented.
- The counter is advanced by **burning leaves** (`sign` → `Wallet.signData` increments and persists `uses`). There is no command to set a counter, and the private key can't be fetched (`KeyRow.toJSON` has `privatekey` commented out) — so this is the one mechanism the node exposes, and it **works on any node**: no forked build, no new command.
- A pre-v2 backup, or a key the advance can't reach, is **reported** — never silently resumed at leaf 0.
- **No transaction-building code was touched.** `PoolManager`, `PoolTxn`, `TxPost` and `CmdChain` are byte-identical, so every command an existing pool emits is unchanged. Only `Recovery.java` and two argument-passing lines in `MyLpView` differ.
- Released 3-way with MDS **0.6.11** + desktop **0.16.5**; the MDS/desktop arithmetic is verified case-by-case against the Java implementation.

## [0.9.22] — SECURITY: never sign two transactions at once
- **Fixed** the app fanning out concurrent signing. `PoolRefresher` posted up to **8 refreshes in one loop** (`ReAnnouncer` and `sweepOwnerFunds` likewise), and `PoolManager.selectCoins` sorted largest-first and stopped at the first coin covering the amount needed — which for beacon dust is always the single largest wallet coin. So every parallel builder picked **the same coin, at the same address, owned by the same key**, and signed simultaneously.
- Minima signatures are stateful: the node picks the next one-time leaf by reading, incrementing and writing a per-key `uses` counter. Two transactions signing one key at once both read the same value and sign the **same leaf over different data** — a reused Winternitz signature, which leaks that leaf's private key. Confirmed in the wild: 7 of 64 default keys on a live node flagged `RE-USED ×2` by a witness-exact auditor.
- **Added** a process-wide **serial signing gate** in `TxPost`: only one build→sign→post chain from this app is ever in flight. Every fund path already funnels through `checkThenPost`. A watchdog longer than the node's write timeout releases the gate if a callback is ever lost, so it cannot wedge.
- **Added** `CoinLock`, a process-wide coin reservation. `PoolTxn` had this idea already but as an instance field on a class only the Swap tab builds, so it protected swaps from each other and nothing else; both it and `PoolManager` now share one store.
- **Fixed** `PoolManager.selectCoins` not excluding `$OADR`. Funding from an owner coin makes `txnsign auto` sign with `$OPK`, and `ownerSignPost` then signs with `$OPK` again — two key uses for one action. `PoolTxn.swap` already excluded it and explained why.
- The root cause was in the node and is fixed there too (`minima-core` `c400cf9`, shipped in minimaCore `1.6.7-ui-h2`): `Wallet.signData` was the only unsynchronized mutator on the wallet. These app-side changes stay regardless — our apps also run against nodes we don't control, and serialising was correct anyway, since 7 of those 8 parallel refreshes were always going to double-spend and fail.

## [0.9.21] — Wallet coin list: spendable / locked per coin, and the builds are back in `dist/`
- **Fixed** the coin list not saying which coins are actually spendable. It tagged `(pool)` and `(beacon)` but never marked spendable vs locked — so it named *some* of the gap between confirmed and sendable and left the rest unexplained. Each coin now carries `spendable` or `locked` straight from the node's own sendable set (`coins relevant:true sendable:true`), matching what the desktop app has shown since 0.16.3. If that query fails the marks are omitted rather than guessed.
- **Fixed** released APKs going only to `releases/`, which is **gitignored** — so from the repo nothing newer than 0.9.16 existed. `dist/` is tracked and is where builds belong; 0.9.17 through 0.9.21 are now backfilled into it.

## [0.9.20] — Wallet: AtomiX's four-figure balance breakdown
- **Changed** the Wallet tab to AtomiX's display logic (`atomix-mds/lib/ui.js:495` · `apks/atomix/…/MainActivity.java:678`): each token card keeps its headline **sendable** amount and gains a one-line breakdown — `confirmed X · locked ≈ Y · unconfirmed Z · N coins · updated Ns ago · tap for coins`.
- **Fixed** the real problem this solves: `locked` and `pending` were hidden when zero, so a node with everything committed to a pool showed a bare `0` and nothing explaining where the money went. Every figure now shows unconditionally, zeros included. `locked = confirmed − sendable` (`sendable` counts only simple-address coins; `confirmed` includes contract-locked ones — in this app, your pool reserves), shown with `≈` because it is derived, not node-supplied. Full precision, so the Wallet card and the My LP card agree about the same coins.
- **Added** a **Coins** section to the token detail dialog: every relevant coin, largest first, with its **full** coinid, tagged `(pool)` for covenant reserves and `(beacon)` for registry dust. Selectable, plus a *copy all coins* action. It reconciles — on the dev node, `locked ≈ 177135.89717784727` = the pool coin `177115.89717779927` + two 10-MINIMA coins + 48 beacon dust.
- The coin fetch omits `simplestate` and falls back to sendable-only on the node's over-256KB stub, saying so rather than silently showing a subset — an oversized broadcast reply is uncatchable and force-kills the app.
- 11 new tests (locked derivation, zero-visibility regression, full precision, freshness stamp, backward clock).
- Released **3-way** with MDS **0.6.9** + desktop **0.16.3**, which carry the same breakdown and the same uncapped, tagged coin list.

## [0.9.19] — pool statement replaces the accounting export + two real accounting bugs fixed
- **Fixed** a **routed swap being booked entirely against one pool**. A swap is ONE transaction spanning up to 6 pools (`PoolRouter.MAX_POOLS`), but 0.9.18 attributed the whole trade to the first pool address it matched. Each pool now gets its own share, measured directly as `Σ(outputs at pool) − Σ(inputs at pool)` — exact for create, deposit, swap, withdraw and routed multi-pool trades alike. The split is checked against the wallet's own movement per transaction; anything that doesn't tie is flagged in the file rather than mis-booked.
- **Fixed** **token amounts stored wrong in the history mirror**. `HistoryEntry` read a coin's `amount` where `tokenamount` was meant — and `optString(k, fallback)` only falls back when the key is *absent*, so every token coin was stored with the internal coloured-coin value. Present since the history store was added; it also mis-rendered the Activity tab's transaction detail. `HistoryDb` goes to v2 and re-syncs once in the background to rewrite the affected rows (the table is never dropped).
- **Changed** the export to a single, pool-scoped **statement**: what you put in, your own trades, what is in the pool now, and the profit. The whole-wallet ledger is gone — the node wallet also serves the other Minima apps on this device, which made that file unusable. Moved from the Activity tab to **My LP**, where the live reserves already are, so the file and the pool card cannot disagree.
- Two profit figures, each labelled: **pool profit (vs holding)** — fees minus impermanent loss, with MINIMA's own price move cancelled out — and **change in market value**, which includes it. Every figure comes from the on-chain history or the live scan; nothing is estimated, and where a value can't be obtained the file says so instead of printing one.
- Trade rows are **your** transactions only, stated in the file. Profit is unaffected by that scope: reserves are read live, so everyone else's trading is already in them.

## [0.9.18] — accounting export (full transaction history, P/L and balance reconciliation)
- **Added** an **Export** action on the Activity tab that writes a ZIP of four files: `pandapools-transactions.csv` (every pool create with **both** leg amounts, every trade with the price it actually executed at, deposits, withdrawals, maintenance), `wallet-ledger.csv` (every token movement across the whole wallet, oldest first, with a running balance), `lp-positions.csv` (per-pool round-trip totals, surviving a close), and `summary.txt` (reconciliation + provenance). Save via SAF or hand straight to another app via the share sheet.
- Everything is derived from the existing permanent `HistoryDb` mirror — the per-token `deltas` map summed chronologically **is** the ledger. No fiat oracle and no market feed: mxUSDT is the unit of account and every price comes from the transaction's own two legs, so an export is exact, offline and reproducible.
- A shortfall against the node's balance is disclosed as an **opening balance** rather than hidden, and an unfinished history backfill is stated up front — `HistorySync` reaches only as far back as the node still retains.
- Read-only with respect to funds: no covenant, transaction-construction, discovery or `HistorySync` page-size change.
- **Fixed** a stale assertion in `PoolCovenantTest` that still expected `SENTINEL_SCAN_DEPTH == 400` after 0.9.15 deliberately widened it to 1500. Added 24 tests (classification incl. the keep-fresh and migrate-vs-refresh traps, running balance, reconciliation, CSV injection safety).

## [0.9.17] — Pools tab: Individual | Combined view toggle
- **Added** a toggle on the Pools tab: keep the per-pool list, or fold every pool of a token into **one collective-pool card** (summed reserves + aggregate spot price + pool count + tradeable depth). Display-only — reuses `VirtualCurve`/`PoolRouter` aggregation, no covenant/txn/scan change. Released 3-way with MDS 0.6.8 + desktop 0.16.2.

## [0.9.16] — owner-key self-heal before Withdraw / Migrate / Close
- **Fixed** a seed-only restore that hadn't finished re-deriving the owner `newaddress` key could fail signing (`Public Key not found`) if you managed a pool too soon; the three owner actions now regenerate the key first (no-op when held). Glue-only; covenant untouched.

## [0.9.15] — discovery reliability tuning (flicker-free cross-device visibility)
- **Fixed** a beacon-lapse flicker (a pool vanished from non-owner nodes until re-announced): widened the sentinel discovery window `400 → 1500` blocks, added proactive re-announce (`1000`), and shortened keep-fresh (`REFRESH_BLOCKS 1200 → 900`). Hard invariant: discovery_depth > reannounce_depth + confirm-lag.

## [0.9.14] — fix crash-on-open (IPC broadcast overflow) + 52 unit tests
- **Fixed** the uncatchable Android IPC Binder overflow crash: `history max:8 → 1`, unbounded sentinel scan → `depth:400`, removed track-on-discovery so the `scripts` reply can't grow. Added 52 JVM unit tests (covenant determinism, token-grain conservation, router water-filling).

## [0.9.13] — Doze-proof keep-alive stack
- **Changed** the keep-fresh/re-announce scheduling to minimaSwap's Doze-proof foreground-service + exact-alarm + boot-receiver stack (a bare WorkManager was throttled overnight). Fund path unchanged.

## [0.9.12] — keep pools fresh in the cascade
- **Added** decentralized keep-fresh: the owner recreates its pool's reserve coins **in place** before they age out of the ~1700-block cascade, so light nodes keep discovering + trading them. No server, no `megammr`, zero burn.

## [0.9.3] – [0.9.8] — recovery layers + hardening
- **Added** the 5-layer pool recovery ("always retractable + rediscoverable, even on a wiped node"): own-pool recipe store + re-track-on-launch (0.9.5), coin-proof backup/restore (0.9.6), network beacon re-announce foreground + background (0.9.7).
- **Changed** the four Pools tabs to one shared single-flight scan (`PoolRepository`) — kills the 4×-per-block thundering herd (0.9.4).
- **Fixed** review findings: foreground-only block poll, main-Looper crash guard, cross-source dedup, view lifecycle (0.9.3); a full soup-to-nuts code-review pass (0.9.8).

## [0.9.0] – [0.9.1] — clean MegaMMR-free rebuild
- **Fixed** the crash-on-open on busy nodes by removing the MegaMMR discovery path and bounding the IPC surface (`megammr:false`, bounded windows); restored cross-pool discovery safely.
- **Fixed** discovery on a node with no pools of its own (was skipping the registry scan).
- **Changed** MY LP / keep-alive ownership to derive from the node wallet so it survives reinstall.

## [0.8.9] — discovery reset to the 8.7/8.8 command surface
- **Changed** removed the MegaMMR resync/backfill experiment and stripped `megammr` from discovery — reverted to the proven light-node command surface.

---

_Pre-0.8.9 (the original build-out to a working on-chain AMM: contract proof, native app, on-device mainnet
create/swap/close, and the GTC/parseok discovery fixes) predates this repo's git history and lives in the published
APKs + `apks.json` history._
