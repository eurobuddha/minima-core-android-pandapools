# Changelog

All notable changes to the **PandaPools** native Android app. Newest first. Each release is a debug-signed APK
published to the [PandaApps catalog](https://github.com/eurobuddha/minima-core-apks) (`apks.json`) and tagged here.

The app shares one mainnet + `PANDAPOOLS` registry + 0.5% covenant with the [PandaPools MiniDapp](https://github.com/eurobuddha/pandapools-mds)
and the minimaCore Desktop "Pools" tab — the three trade the **same** live pools, so on-chain-affecting changes are
mirrored across all three.

> The repository was git-initialised at **0.8.9**; earlier source survives only as published APKs. Versions below are
> the tracked releases — see the [GitHub Releases](../../releases) for the canonical published list.

---

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
