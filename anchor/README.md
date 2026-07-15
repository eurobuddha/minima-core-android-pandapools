# PandaPools cloud anchor — beacon re-announcer

Keeps every funded pool's registry beacon fresh 24/7 so cross-node discovery never lapses.

## Why
A pool's registry beacon is unspendable dust at the sentinel `0x50414E4441504F4F4C53`, which MMR-prunes ~a day
after it's posted. The app's own re-announce was (1) **reactive** (only re-announced a beacon *after* it had already
pruned — a guaranteed dark window) and (2) unreliable in the background (Samsung Doze throttling the 6h WorkManager).
So when all phones sat idle overnight, beacons pruned with no replenishment and each phone fell back to seeing only
its own pools. Diagnosed 2026-07-15 from live-chain data: the 6 pools each had exactly ONE (create-time) beacon and
the newest beacon on-chain was ~18h old — nothing had re-announced in 18h.

## What this does (proactive, reliable, always-on)
- Runs on an **always-on node** (the eurobuddha megammr node, RPC `127.0.0.1:9005`) on a `systemd` timer (every 2.5h).
- `coins address:<sentinel> megammr:true` → all beacons + full state → group by pool `opk|tok|kmin`, newest `created`.
- For each: reconstruct the **0.5% covenant** (byte-identical to `PoolCovenant.TEMPLATE`), `runscript` (`parseok` gate)
  → derive address → `coins address:<addr> megammr:true` → funded (largest coin per leg)?
- If funded **and** its newest beacon is older than `PP_STALE_BLOCKS` (~900 blk / ~12h — *before* the ~24h prune),
  relay a fresh beacon: `send 0.000000001` to the sentinel carrying the **same state map** (verbatim).

## Fund-safety
Read-only except a dust `send` to the *unspendable* sentinel, funded from the node wallet, change back. Never spends
a covenant/pool coin, never signs an owner key, only re-announces pools that are on-chain funded + whose covenant
`parseok`s. Identical trust model to `PoolManager.reannounce` / the MDS `poolmgr.reannounce`.

## Funding
`getaddress` → co-located faucet (`https://eurobuddha.com/faucet/api/request?address=<addr>`); auto-tops-up when
`sendable < PP_MIN_SENDABLE`. Each beacon costs ~1e-9 MINIMA → a fraction of a MINIMA lasts years.

## Deploy
```
scp reannouncer.py root@eurobuddha.com:/root/pandapools-anchor/
scp pandapools-anchor.{service,timer} root@eurobuddha.com:/etc/systemd/system/
ssh root@eurobuddha.com 'systemctl daemon-reload && systemctl enable --now pandapools-anchor.timer'
```
Env (in the .service): `PP_RPC`, `PP_FAUCET`, `PP_STALE_BLOCKS`, `PP_MIN_SENDABLE`, `PP_MAX_PER_RUN`. `--dry-run` / `PP_DRY=1` logs what it would post without sending.
