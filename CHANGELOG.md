# Changelog

All notable changes to the **PandaPools** native Android app. Newest first. Each release is a debug-signed APK
published to the [PandaApps catalog](https://github.com/eurobuddha/minima-core-apks) (`apks.json`) and tagged here.

The app shares one mainnet + `PANDAPOOLS` registry + 0.5% covenant with the [PandaPools MiniDapp](https://github.com/eurobuddha/pandapools-mds)
and the minimaCore Desktop "Pools" tab — the three trade the **same** live pools, so on-chain-affecting changes are
mirrored across all three.

> The repository was git-initialised at **0.8.9**; earlier source survives only as published APKs. Versions below are
> the tracked releases — see the [GitHub Releases](../../releases) for the canonical published list.

---

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
