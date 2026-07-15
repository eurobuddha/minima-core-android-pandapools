#!/usr/bin/env python3
"""
PandaPools cloud ANCHOR re-announcer.

Keeps every funded pool's registry beacon fresh so cross-node discovery never lapses. Runs on an always-on node
(the eurobuddha megammr node @ 127.0.0.1:9005) on a timer. The phones' discovery only finds OTHER creators' pools
via live beacons in the recent sentinel window, and a beacon is unspendable dust that MMR-prunes after ~a day —
so if nobody re-announces, the mesh collapses (each phone falls back to only its own tracked pools). This process
is the anchor the app's ReAnnouncer comment envisions: as long as it runs, every funded pool stays discoverable.

PROACTIVE (not reactive): re-announces a pool whose newest beacon is older than STALE_BLOCKS (~12h), i.e. BEFORE
the ~24h prune — no dark window.

FUND-SAFETY: read-only except a dust `send` of 0.000000001 MINIMA to the UNSPENDABLE sentinel, funded from the
node wallet, change back. It NEVER spends a covenant/pool coin and NEVER signs an owner key (the beacon just
carries the pool's public params, exactly like PoolManager.reannounce / the MDS poolmgr.reannounce). It re-posts
ONLY pools that are (a) on-chain funded and (b) whose 0.5% covenant `parseok`s — a dead/closed/unparseable pool is
never re-announced.

Config via env: PP_RPC (default http://127.0.0.1:9005/), PP_FAUCET, PP_STALE_BLOCKS, PP_MIN_SENDABLE,
PP_MAX_PER_RUN, PP_DRY=1. CLI: --dry-run (log what it WOULD post, no send).
"""
import json, urllib.request, urllib.parse, sys, os, time
from decimal import Decimal

RPC          = os.environ.get("PP_RPC", "http://127.0.0.1:9005/")
FAUCET       = os.environ.get("PP_FAUCET", "https://eurobuddha.com/faucet/api/request")
SENTINEL     = "0x50414E4441504F4F4C53"                 # "PANDAPOOLS"
MXUSDT       = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90"
DUST         = "0.000000001"
STALE_BLOCKS = int(os.environ.get("PP_STALE_BLOCKS", "900"))       # ~12h @ ~50s/block — refresh before the ~24h prune
MIN_SENDABLE = Decimal(os.environ.get("PP_MIN_SENDABLE", "0.001")) # below this, top up from the faucet
MAX_PER_RUN  = int(os.environ.get("PP_MAX_PER_RUN", "40"))
DRY          = ("--dry-run" in sys.argv) or (os.environ.get("PP_DRY") == "1")

# The LIVE 0.5% covenant — byte-identical to PoolCovenant.TEMPLATE (native) and covenant.js (MDS). NOT the 0.3%
# harness template: address = hash(script), so a 0.3% reconstruction would derive an empty address and every real
# pool would look "unfunded". The KMIN literal is taken verbatim from the beacon (already canonical).
TEMPLATE = (
 "IF SIGNEDBY($OPK) THEN "
 "IF VERIFYOUT(@INPUT $OADR @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF "
 "RETURN GETOUTADDR(@INPUT) EQ @ADDRESS AND GETOUTTOK(@INPUT) EQ @TOKENID AND GETOUTAMT(@INPUT) GTE @AMOUNT "
 "ENDIF "
 "IF @TOKENID EQ 0x00 THEN "
 "ASSERT @INPUT % 2 EQ 0 LET s=@INPUT+1 "
 "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ $TOK "
 "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ $TOK "
 "LET x=@AMOUNT LET y=GETINAMT(s) LET nx=GETOUTAMT(@INPUT) LET ny=GETOUTAMT(s) "
 "ASSERT VERIFYOUT(@INPUT @ADDRESS nx 0x00 FALSE) "
 "ELSE "
 "ASSERT @TOKENID EQ $TOK AND @INPUT % 2 EQ 1 LET s=@INPUT-1 "
 "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ 0x00 "
 "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ 0x00 "
 "LET y=@AMOUNT LET x=GETINAMT(s) LET ny=GETOUTAMT(@INPUT) LET nx=GETOUTAMT(s) "
 "ASSERT VERIFYOUT(@INPUT @ADDRESS ny $TOK FALSE) "
 "ENDIF "
 "LET dx=nx-x LET dy=ny-y LET fx=MAX(dx 0)*5/1000 LET fy=MAX(dy 0)*5/1000 "
 "RETURN (nx-fx)*(ny-fy) GTE MAX(x*y $KMIN)")


def rpc(cmd, timeout=180):
    with urllib.request.urlopen(RPC + urllib.parse.quote(cmd, safe=""), timeout=timeout) as r:
        return json.load(r)


def resp_list(r):
    x = r.get("response")
    return x if isinstance(x, list) else []


def block():
    return int(rpc("block")["response"]["block"])


def smap(coin):
    """simplestate map {port: value} for a coin (already simplestate:true)."""
    s = coin.get("state")
    return s if isinstance(s, dict) else {}


def pool_script(opk, oadr, tok, kmin):
    return TEMPLATE.replace("$OPK", opk).replace("$OADR", oadr).replace("$TOK", tok).replace("$KMIN", kmin)


def derive(opk, oadr, tok, kmin):
    """runscript the reconstructed 0.5% covenant → (parseok, address). json.dumps quotes the script and does NOT
       escape '/', so '*5/1000' stays a real slash (the '\\/' bug that strands funds is impossible here)."""
    try:
        r = rpc("runscript script:" + json.dumps(pool_script(opk, oadr, tok, kmin)))
        resp = r.get("response") or {}
        pj = resp.get("parseok")
        parseok = (pj is True) or (str(pj).lower() == "true")
        addr = ((resp.get("script") or {}).get("address")) if parseok else None
        return parseok, addr
    except Exception as e:
        return False, None


def reserves(address, tok):
    """Largest coin per leg = the true reserve (dust can't masquerade). Returns (reserveM, reserveT).
       megammr:true so an anchor that doesn't TRACK the pool covenant still sees its reserves (bounded: ~2 coins
       at this one address, not the sentinel pile — safe on a server)."""
    rM = rT = Decimal(0)
    for c in resp_list(rpc("coins address:" + address + " megammr:true")):
        if c.get("spent"):
            continue
        tid = c.get("tokenid", "")
        if tid == "0x00":
            a = Decimal(str(c.get("amount", "0")))
            if a > rM: rM = a
        elif tid.lower() == tok.lower():
            a = Decimal(str(c.get("tokenamount", c.get("amount", "0"))))
            if a > rT: rT = a
    return rM, rT


def sendable_minima():
    try:
        b = rpc("balance tokenid:0x00")["response"]
        row = b[0] if isinstance(b, list) and b else {}
        return Decimal(str(row.get("sendable", "0")))
    except Exception:
        return Decimal(0)


def faucet_topup():
    """Best-effort: request a drip from the co-located faucet so the anchor self-funds. Never raises."""
    try:
        addr = rpc("getaddress")["response"].get("address", "")
        if not addr:
            return
        url = FAUCET + "?address=" + urllib.parse.quote(addr, safe="")
        with urllib.request.urlopen(url, timeout=30) as r:
            body = r.read(2048).decode("utf-8", "replace")
        print("  [fund] faucet requested for", addr[:14], "->", body[:120])
    except Exception as e:
        print("  [fund] faucet top-up failed:", e)


def reannounce(state_map):
    """Relay a fresh beacon: dust send to the sentinel carrying the SAME state map (verbatim, no reconstruction)."""
    st = json.dumps({k: state_map[k] for k in sorted(state_map, key=lambda x: int(x))}, separators=(",", ":"))
    cmd = "send amount:%s address:%s tokenid:0x00 state:%s" % (DUST, SENTINEL, st)
    r = rpc(cmd)
    if not (r.get("status") is True or r.get("pending") is True):
        raise RuntimeError(str(r.get("error") or r.get("response"))[:160])
    return (r.get("response") or {}).get("txpowid", "")


def main():
    tip = block()
    beacons = resp_list(rpc("coins simplestate:true address:%s megammr:true" % SENTINEL))
    print("run @ tip %d | beacons on chain: %d | dry_run=%s | stale>%d blk (~%.0fh)" %
          (tip, len(beacons), DRY, STALE_BLOCKS, STALE_BLOCKS * 50 / 3600))

    # group by pool key opk|tok|kmin, keep the newest beacon's state + created block
    pools = {}
    for c in beacons:
        s = smap(c)
        tok, oadr, opk, kmin = s.get("2"), s.get("3"), s.get("4"), s.get("5")
        if not (tok and oadr and opk and kmin):
            continue
        created = int(c.get("created", "0") or 0)
        key = (opk + "|" + tok + "|" + kmin).lower()
        cur = pools.get(key)
        if cur is None or created > cur["created"]:
            pools[key] = {"opk": opk, "oadr": oadr, "tok": tok, "kmin": kmin, "created": created, "state": s}

    scanned = len(pools)
    funded = faded = posted = 0
    to_post = []
    for p in pools.values():
        age = tip - p["created"]
        parseok, addr = derive(p["opk"], p["oadr"], p["tok"], p["kmin"])
        if not parseok or not addr:
            continue
        rM, rT = reserves(addr, p["tok"])
        is_funded = rM > 0 and rT > 0
        if is_funded:
            funded += 1
        stale = age > STALE_BLOCKS
        tag = "FUNDED" if is_funded else "empty "
        print("  %s age %5d blk (~%4.1fh) %s M=%s T=%s  %s" %
              (tag, age, age * 50 / 3600, addr[:14] + "…", rM, rT,
               ("-> RE-ANNOUNCE" if (is_funded and stale) else ("fresh" if is_funded else "skip"))))
        if is_funded and stale:
            faded += 1
            to_post.append(p)

    to_post = to_post[:MAX_PER_RUN]
    if to_post and not DRY:
        if sendable_minima() < MIN_SENDABLE:
            faucet_topup()
            time.sleep(2)
        for i, p in enumerate(to_post):
            if i:
                time.sleep(3)   # small gap so consecutive sends pick DISTINCT coins (avoid same-UTXO race)
            try:
                tx = reannounce(p["state"])
                posted += 1
                print("  [post] re-announced %s… tx %s" % (p["state"].get("4", "?")[:14], tx[:16]))
            except Exception as e:
                print("  [post] FAILED %s… : %s" % (p["state"].get("4", "?")[:14], e))
                if "funds" in str(e).lower() or "coin" in str(e).lower():
                    faucet_topup(); time.sleep(2)

    print("SUMMARY scanned=%d funded=%d faded=%d posted=%d%s" %
          (scanned, funded, faded, posted, " (DRY-RUN, nothing sent)" if DRY else ""))


if __name__ == "__main__":
    main()
