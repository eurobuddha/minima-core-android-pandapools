# Plan — on-chain pool recovery, retiring the backup file as a requirement

**Status:** planned, not started. Native is 0.9.57, MDS 0.6.23, desktop 0.16.92.
**Written:** 2026-09-15, immediately after the MxUSD recovery incident.
**Why this doc exists:** the research below was expensive to derive and is not obvious from the code.
Read the *Verified constraints* section before changing anything here — three of the four obvious designs
are wrong for reasons that only show up in core.

---

## 1. The problem, precisely

Pool `MxG081BJGW3KQ0PBPHCS841CC5RA7YKRVEQUE93Z353MT4FBTJ2101S1423Z1ET` closed correctly on 2026-09-14 and
paid both legs to its owner payout address. Recovering the funds afterwards took a day, not because the
pool was hard to find — it was fully visible on chain throughout — but because three facts about the
**owner key** existed only on a dead phone and in a 34K export file.

### What is already on chain, and where

The covenant carries **no state at all** (`PoolCovenant.java:11` — "Reserves are the two pool coins'
amounts (no state)"). Pool identity lives entirely in the **announce beacon**: dust at the sentinel
address `0x50414E4441504F4F4C53` ("PANDAPOOLS", 10 bytes, unspendable), with parameters in state ports
0–5, written by `PoolManager.addAnnounceState:543-551` and re-posted every `REANNOUNCE_DEPTH` = 1000
blocks by `ReAnnouncer`.

| port | value | read by |
|---|---|---|
| 0 | `$OPK` (duplicate of 4, labelled "reclaim key") | nothing |
| 1 | `0x505031` = `"PP1"` version | **nothing** — write-only, no version gate exists anywhere |
| 2 | `$TOK` paired tokenid | `PoolBook.gatherRegistry` |
| 3 | `$OADR` owner payout address | `PoolBook.gatherRegistry` |
| 4 | `$OPK` owner public key | `PoolBook.gatherRegistry` |
| 5 | `$KMIN` product floor | `PoolBook.gatherRegistry` |

Ports 2–5 are the four covenant literals, which regenerate the script → the address → the live coins.
**For a pool with a live beacon the export file is already redundant.**

### What is missing

1. **`kidx`** — which wallet key index `$OPK` is. `$OPK` is minted with `newaddress`
   (`PoolManager.java:62`), i.e. index ≥ 64, and a seed restore runs `initDefaultKeys` which rebuilds
   **only the 64 defaults** (`Wallet.java:417-463`). So the key is simply absent after a seed restore:
   `keys action:list publickey:<$OPK>` returns 0 rows and `checkaddress` reports `relevant: false`.
   `kidx` is recorded in `OwnPoolStore` (`"kidx"`), in backup v3 entries (`Recovery.java:114`), and in
   `Pool.kidx` — never on chain.
2. **The Winternitz use counter.** Not derivable from a seed by *any* mechanism, on chain or off: a seed
   reproduces the key, it cannot know how many one-time leaves were spent. It must be recorded. Today it
   is recorded only at **backup** time (`Recovery.java:101-109`), so a pool created and never backed up
   carries `minimumOwnerUses = -1` — the exact "no regression signal" state `RestoreExit.java:62` and
   `ExitTicket.java:75` refuse to act on.
3. **Nothing else.** `tokDecimals` comes from token metadata; reserves come from the address.

The MMR **coin proofs** in the export cannot live in a coin: a proof is only valid against a recent MMR
root, so it expires. That is what a MegaMMR is for.

---

## 2. Verified constraints — read this before designing

### A. `coins state:` makes all-time discovery possible

`coins state:<value>` wildcard-matches **any** state port on a coin — core
`Coin.checkForStateVariable:211-224`, invoked with `zWildCardState = true` from `coins.java:176` via
`TxPoWSearcher.searchCoins:118,180`. So a single query against a MegaMMR:

```
coins address:0x50414E4441504F4F4C53 state:<wallet tag> megammr:true
```

returns **every beacon that wallet ever posted**, with no depth window at all. Publish a
seed-reproducible wallet tag on the beacon and that is complete seed-only pool discovery — subject to B.

### B. megaprune discards the whole registry, and cannot be detected from `status`

`MegaMMR.isPrunable:130-154`:

```java
if(GeneralParams.MEGAMMR_MEGAPRUNE) {
    if(zCoin.getAddress().getLength() != 32) return true;          // <-- the sentinel is 10 bytes
    if(GeneralParams.MEGAMMR_MEGAPRUNE_STATE   && zCoin.getState().size() > 0) return true;
    if(GeneralParams.MEGAMMR_MEGAPRUNE_TOKENS  && !zCoin.getTokenID().isEqual(Token.TOKENID_MINIMA)) return true;
}
```

- The sentinel `0x50414E4441504F4F4C53` is **10 bytes**, so **plain `-megaprune` alone discards every
  PandaPools beacon.** `-megaprunestate` would catch it too, since the beacon is the only stateful coin
  we post.
- `status` reports `megammr` (`status.java:97`) but **not** the prune flags. So a pruning source answers
  an all-time sentinel query with silence indistinguishable from "you have no pools". Designing against
  that misreading is the highest-value item in this plan.
- Also relevant to today's tier-3 proof fetch: `-megaprunetokens` prunes non-MINIMA coins, so such a node
  can serve the MINIMA reserve leg's proof and **never** the token leg.

**Structural limit, stated once:** only a stateless coin at a 32-byte address survives all three prune
modes, and a stateless coin cannot carry data — so **no coin-based registry can be made prune-proof.**
Pruning affects only a MegaMMR's archived unspent set, never the live chaintree, so within the
`SENTINEL_SCAN_DEPTH` = 1500 window every node has the beacons. On-chain recovery is therefore complete
for a pool kept alive, and needs a *non-pruning* MegaMMR for an abandoned one.

### C. The three known MegaMMR hosts are P2P resync peers, not HTTP archives

`megammr.minima.global:9001`, `eurobuddha.com:9001`, `spartacusrex.com:9001`: TCP connects on 9001 but
nothing answers HTTP. `megammrsync action:resync host:ip:port` speaks the **P2P NIO** protocol
(`megammrsync.java:36-37`, and its help: *"The host you connect to MUST be running with -megammr"*).
`ArchiveNode` (`ArchiveNode.java:49-60`) does an HTTP GET against an RPC endpoint, so it can never use
them. The correct route is to resync the user's **own** node from one of them; the local node then *is* a
MegaMMR and answers the sentinel query locally.

Two further `ArchiveNode` facts that explain why nobody has an archive configured:
- `validEndpoint:33-40` requires scheme **`https`** — the hosts above are plain on :9001.
- `allowedCommand:42-48` requires a **64-hex** address, so the 20-hex sentinel can never be queried.

**Prune status of the known hosts.** These cannot be read remotely (no HTTP on 9001, and `status` does
not expose the flags even where it is reachable).

| host | `-megaprune`? | basis |
|---|---|---|
| `eurobuddha.com:9001` | **no — safe, serves the registry** | operator's own statement, 2026-09-15 |
| `megammr.minima.global:9001` | unknown | not tested |
| `spartacusrex.com:9001` | unknown | not tested |

So there is at least one known-good source, which unblocks §4. Two caveats that do not go away:

1. **Confirm it empirically once, cheaply.** After any resync from it, `coins address:0x50414E4441504F4F4C53 megammr:true`
   must return beacons. Zero would mean the flag changed, and the capability probe in 0.9.61 exists so
   that answer is never silently misread as "you have no pools".
2. **Shipping it as the app's suggested host makes PandaPools' recovery story depend on infrastructure the
   author personally runs.** Acceptable for the author's own recovery; a deliberate decision for other
   users. Present it as one suggestion among several with its role stated, not as a silent built-in, and
   keep the field user-editable.

### D. There is no per-key uses setter in any fork

Checked `core/Minima`, `core/minima-core` (all branches), `core/minima-core-meg`, and the fork's own
`UPSTREAM_CHANGES.md` — which documents the fork as exactly four changes: `Wallet.signData`
synchronisation, MegaMMR heap handling, `removeScript` cache invalidation, txpow table indexes.

The SQL exists — `UPDATE keys SET uses=? WHERE publickey=?` (`Wallet.java:152`) — but `updateUses:875` is
private and only `signData` calls it. The only commands taking both `publickey` and `keyuses` are
`txnsign` and `keys`, and `keys`' `keyuses` feeds only `createallkeys`, which is the wallet-wide blind SET
(`Main.java:1319-1338` → `updateAllKeyUses`).

`keys action:list modifier:<N>` **does** work (`keys.java:63,100-118`), and `KeyRow.toJSON` exposes
`modifier` but deliberately **not** `privatekey` (`KeyRow.java:77`, commented out).

`kidx` from `newaddress` is exact, not a heuristic: `createNewKey` sets `modifier = mAllKeys.size()`
*before* insert (`Wallet.java:504-506`) and `newaddress` reports `total = size()` *after*
(`newaddress.java:36-41`), so `total - 1` is the modifier.

---

## 3. Design — three new beacon ports

Written in `PoolManager.addAnnounceState`, the single choke point for create / migrate / refresh /
re-announce. No covenant change, no script-address change, no consensus implication. Because
`PoolBook.gatherRegistry` reads only ports 2–5, **older clients ignore new ports**. And because
`OwnPoolStore` already holds `kidx` for every live pool, **every existing pool retrofits itself on its
next re-announce** — no user action, no migration.

| port | name | value | purpose |
|---|---|---|---|
| 6 | `kidx` | `$OPK`'s wallet modifier, decimal | re-derive the owner key: `privseed = hash(seed, modifier)` |
| 7 | `kuse` | owner-key `uses` **after** this transaction's owner signatures | a proven, owner-signed, timestamped signing floor |
| 8 | `wtag` | public key at modifier **0** of the creating wallet | seed-reproducible handle for the `coins state:` query |

Port 7's increment per operation, from the transaction builders: create **+0** (create signs no covenant
coin — `buildCreate` signs `publickey:auto` only), refresh **+1**, migrate **+1**, re-announce **+0**
(`reannounce` signs only funding coins). So **re-announce republishes an updated floor without spending a
leaf.** Recovery takes `max()` across every beacon found, then adds a margin for signatures no beacon can
see — the close, and `$OADR` forwards.

Port 8 is a deliberate privacy trade: it links a wallet's pools to each other under one public key. They
are already linked through the funding coins that pay for each beacon, and the beacon already publishes
`$OPK` and `$OADR` — but it is a real disclosure and belongs in the create dialog's text.

---

## 4. Work, in shipping order

Native lands and is tested first, then mirrors to the MDS donor and desktop. One logical change = one
version = one commit, per `CLAUDE.md`.

### 0.9.58 — write the new ports, fix the floor at creation
- `PoolManager.addAnnounceState` — add ports 6/7/8. `uses` must be read before the command list is built,
  so `createPool` / `refresh` / `migrate` / `reannounce` each read the owner key first
  (`KeyUses.extractUses` on `keys action:list publickey:<opk>`) and pass the value in. **A read failure
  must omit ports 6–8 entirely rather than publish a wrong number.**
- `PoolManager.createPool:87` — set `p.minimumOwnerUses = 0` at create (currently left `-1`). This alone
  removes the "no regression signal" state for every new pool.
- `Pool.java` — add `wtag`; `kidx` and `minimumOwnerUses` already exist.
- Tests: extend `PoolBookStateTest` (pins exactly six ports today); add a port-arithmetic test asserting
  create +0, refresh +1, migrate +1, re-announce +0.

### 0.9.59 — read them, make the signing guard chain-backed
- `PoolBook.gatherRegistry` — read 6/7/8 into the `Pool`; `max()` the floor across beacons. Ports absent
  ⇒ legacy pool, not a rejection.
- `OwnerKeyRecovery.classify:120-128` — `COUNTER_REGRESSED` becomes provable from chain: compare the
  node's `uses` against `max(local floor, chain-published floor)`. **This is the change that would have
  caught the incident on any device, with no file.**
- `MyLpView.mine(p)` (`:134`) — a pool whose `wtag` is in `myKeys` is mine even when `$OPK` is not held.
  Render as "owned, owner key not on this device" with a recovery action, instead of vanishing (which is
  why MY LP showed nothing during the incident).
- Two defects found while mapping, fix here:
  - `Pool.signingStateUnverified` defaults `false` on the field (`Pool.java:29`) but `true` when read from
    the store (`OwnPoolStore.java:121`), so `PoolRefresher.refreshAging`'s `!p.signingStateUnverified`
    filter is a **no-op on scanned pools**. Make the field default `true`.
  - `OwnerKeyRecovery.ensure`'s `kidxByOpk` parameter is accepted and never referenced (dead since the
    0.9.48 hunt deletion), which makes `Recovery.java:240-249` dead work. Wire it up in 0.9.60.
  - Lower priority, same area: `ExitTicket.eligible()` has no production caller — `RestoreExit.refusal()`
    re-implements the same five checks. They agree today but are two copies of one rule.

### 0.9.60 — the seed-only recovery flow
- New `SeedRecovery`: given a chain-discovered pool with `kidx`, mint `newaddress` until the wallet holds
  `kidx + 1` keys, then verify `keys action:list modifier:<kidx>` yields exactly `$OPK` **and**
  `checkaddress address:<$OADR>` reports `relevant: true`. Deterministic and bounded; minting never signs.
  A mismatch means a different seed — stop and say so.
- Then **refuse to sign** and display the exact command, every value in full:

  ```
  megammrsync action:resync host:<non-pruning megammr host:9001> phrase:"<your seed phrase>" keys:<kidx+1> keyuses:<N>
  ```

  with `N = max(chain floor over all your pools, this wallet's current keys maxuses) + 256`. State plainly
  that `keyuses:` is a **blind SET across every key** (`Wallet.updateAllKeyUses:891`), which is why the
  wallet's own `maxuses` — available in the `keys action:list` reply — must be inside the max.
  For reference: `restoresync` has **no** `keyuses` parameter; `megammrsync`, `reset`, `archive` and
  `vault` do.
- Legacy pools (no port 6) keep the existing owner-attestation dialog; they retrofit themselves the next
  time the owning device re-announces.

### 0.9.61 — make MegaMMR sources trustworthy, never misread silence
- **Capability probe before belief.** Before concluding anything from an all-time sentinel query, ask the
  source for *any* beacon at all. Zero beacons ever ⇒ the source prunes the registry ⇒ say exactly that
  ("this MegaMMR prunes unspendable coins, so it cannot see the pool registry — resync from a non-pruning
  host"), never "no pools found". The alternative is telling a user their pool is gone when it is not.
- Same probe for the existing tier-3 proof fetch: a source returning the MINIMA leg but not the token leg
  is a `-megaprunetokens` node and must be reported as such, not as a missing coin.
- `ArchiveNode` stays an HTTP RPC tier with **no defaults** — the three known hosts are P2P resync peers
  (constraint C). Surface them instead as the suggested `host:` values in 0.9.60's resync command, ordered
  `eurobuddha.com:9001` first (the one known not to prune), then the other two marked unverified. The
  field stays user-editable and the ordering carries a one-line reason, not a bare list.
- `ArchiveNode.allowedCommand` — add exactly two shapes, the sentinel **literal** only, never a wildcard:
  `coins address:0x50414E4441504F4F4C53 state:0x<64 hex> megammr:true`, and the same with `depth:`.
  Relax `validEndpoint` to accept `http` on an explicit port, keeping the userinfo/query/fragment
  rejections, so a user who does run an RPC-exposed MegaMMR can point at it.
- **Measure every new query's reply size on a busy node before release.** An unbounded sentinel scan once
  returned ~312 KB and overflowed the IPC broadcast, force-killing the app uncatchably. The `state:`
  filter should narrow it to one wallet's pools — that must be shown, not assumed.

### 0.9.62 — the export becomes the abandoned-pool tool
Constraint B means the file keeps real value. This is a demotion and a re-aiming, not a removal.
- Add a **recipe card**: `$OPK`, `$OADR`, `$TOK`, `$KMIN`, `dec`, `kidx`, `opkuses` — ~250 characters,
  QR-able, no script (reconstruct via `OwnPoolStore.reconstruct:222-224`), no proofs.
- Keep the existing full file with its two `coinexport` proofs, relabelled as the no-network /
  abandoned-pool option. **Do not strip the proofs**: `ReserveRecovery` uses a three-tier ladder — live
  coins at the address (`:37-48`), then the embedded snapshot (`:50-56`), then MegaMMR/archive
  (`:58-103`) — and the proofs are the only aged-out route when no MegaMMR is reachable.
- Re-aim the messaging rather than softening it. Today it says "if this phone is lost there is nothing to
  recover from" (`MyLpView.java:1500-1517`, `:414-431`), which after 0.9.59 is false for a live pool and
  still true for an abandoned one. Say *that*: a pool you keep alive is recoverable from your seed alone;
  a pool you stop refreshing for more than ~1500 blocks needs this file or a non-pruning MegaMMR.

### Optional, to propose upstream — not depended on
Two one-line core changes would remove the manual counter step entirely:
- Honour the already-declared `modifier:` param in `keys action:genkey` — `keys.java:63` declares it,
  `:167` hardcodes `new MiniData(new BigInteger("0"))`. That yields the private key for any index from a
  seed.
- With it, `txnsign publickey:custom privatekey:<privseed> keyuses:<floor>` (`txnsign.java:147-165`)
  signs at an exact leaf with **no wallet write at all** — no minting, no blind SET, no risk to any other
  key's counter. Strictly better than exposing `updateUses` as a per-key setter, because it never touches
  the wallet DB.

Worth a PR alongside the existing `UPSTREAM_CHANGES.md` items. PandaPools must keep working on a stock
node without them.

---

## 5. Mirroring to MDS 0.6.24 and desktop

B (`mds/pandapools-mds/`) and C (`desktop/minimacore-desktop/main/pandapools/`) share a **byte-identical**
14-file engine; B is the donor. Workflow: edit B only → bump `dapp.conf` **and** `index.html:137
PANDAPOOLS_VERSION` → `CHANGELOG.md` entry → commit B → copy changed engine files verbatim into C →
`node scripts/pandapools-parity-manifest.cjs --update` → commit C's bytes + manifest together.

- **A state port means three edits in JS**, not one: `poolmgr.js:404 addAnnounceState`, plus two
  open-coded copies in `service.js:634` (`reannounceSvc`) and `service.js:724` (`refreshSvc`).
- Also: `book.js:80 gatherRegistry`; `reserve-recovery.js:33 pool()` / `:34 entry()` / `:21
  validRecipe`; `store.js ownRecord`/`ownAll` plus a column in `ensureRecoveryColumns:50`; desktop
  `main/pandapools.js:190 serializePool` / `:204 serializeMyPool`.
- `poolmgr.js:882 ensureOwnerKeys` already performs the kidx-driven re-mint the Java side deleted in
  0.9.48 — point it at the chain-published `kidx` rather than only the `pp_kv` ledger.
- **Delete the dead leaf-burning path from the donor engine:** `poolmgr.js` `restoreTarget:1005`,
  `advanceKeyUses:1014`, `burnTo:1027`, `BURN_DATA:975`, `MAX_BURN:977`. It signs junk data via
  `sign publickey:` to advance a counter — exactly what `KeyUses.java:25-45` removed in 0.9.48 and
  forbids ("Do not reintroduce a burn path"). Currently unexported and uncalled, so this removes a latent
  footgun rather than changing behaviour.
- Desktop's ownership test is address-from-saved-recipe (`main/pandapools.js:261 myPools`), not key-based
  like native (`MyLpView.java:134`) and MDS (`index.html:346`). It must learn `wtag` or desktop stays
  blind to a chain-only pool.
- **A-only gaps**, flagged so they are not mistaken for parity: `StrandingWatch`, `PendingCollect`,
  `CollectSweeper.decide`, `BackupState`, `BackupCheck`, `RestoreExit`, and
  `OwnerKeyRecovery.Reason`/`Blocked` user-facing messages have no B/C counterpart. 0.9.57's per-attempt
  forward fix also still needs mirroring — tracked separately.

---

## 6. Verification

Unit, per surface (native `./gradlew test`; MDS/desktop `npm run test:pandapools`):
- Port arithmetic: create +0, refresh +1, migrate +1, re-announce +0; a failed `uses` read omits 6–8.
- `PoolBookStateTest` extended to nine ports, and a legacy six-port beacon still parses.
- `classify()` matrix with a chain floor above, equal to, and below the node's counter.
- `mine()` true via `wtag` with `$OPK` absent; false for another wallet's `wtag`.
- Prune probe: an empty sentinel result from a probed-**pruning** source yields the prune message; from a
  probed-**healthy** source yields "no pools". These must be different outcomes, asserted separately.
- `ArchiveNode.allowedCommand` accepts the two new sentinel shapes and rejects every other address,
  including a 64-hex one carrying `state:`.

On chain, against a pool created for the test:
1. Create, then `coins address:0x50414E4441504F4F4C53 depth:20 simplestate:true` — ports 6/7/8 present,
   `kidx` matching `keys action:list publickey:<$OPK>`'s `modifier`, `kuse` 0.
2. Force a keep-fresh refresh; the new beacon's `kuse` is exactly 1 higher and `keys action:list` agrees.
3. **Confirm the host table in §2C.** `eurobuddha.com:9001` is reported non-pruning by its operator;
   verify once by resyncing a scratch node from it and running
   `coins address:0x50414E4441504F4F4C53 megammr:true` — beacons present. Test the other two the same way
   and fill in the table. Not a blocker any more, but the table should not stay half-empty.
4. The end-to-end proof, on a spare device or second node directory: restore **seed only, no file**. MY LP
   must show the pool as owned-but-unsignable; `SeedRecovery` mints to `kidx`, matches `$OPK`, gets
   `checkaddress` `relevant: true`, then refuses to sign and prints the `megammrsync` line with the
   computed numbers. Run it, then withdraw. That is the whole claim, tested.

---

## 7. Limits, stated plainly

- **No coin-based registry can be made prune-proof** (constraint B). On-chain recovery is complete for a
  pool kept alive; an abandoned pool needs a non-pruning MegaMMR or the exported file.
- **The counter cannot be repaired by the app.** Publishing the floor turns a guess into a proof and
  supplies exact numbers, but the final command stays the user's to run unless the two optional core
  changes land.
- Port 8 discloses a wallet-linking public key.
- `eurobuddha.com:9001` is the one known non-pruning source, on its operator's word. Recommending it in a
  shipped app makes recovery depend on infrastructure the author runs — fine for the author, a stated
  choice for everyone else. The other two hosts remain untested.
- Discovery reply size has force-killed this app before. Measure before release.

---

## 8. Open questions

- ~~Which of the three hosts run without `-megaprune`?~~ **Answered for `eurobuddha.com:9001`: it does
  not prune** (operator, 2026-09-15), so a known-good source exists. `megammr.minima.global:9001` and
  `spartacusrex.com:9001` are still untested — worth knowing so users are not steered at a host that
  cannot see the registry.
- Should port 1's `PP1` version tag finally get a **reader**, so a future schema change has a
  compatibility hook? It is written on every beacon and read by nothing.
- Ports 0 and 4 are the same value. Port 0 could be repurposed, but only with a version gate, so it needs
  the previous question answered first.
