# utxoWallet — Native Android clone: figma-style mapping & build blueprint

Source of truth: `/Users/eurobuddha/Projects/utxoWallet/index.html` (v1.0.52). This document maps
every token, component, screen, flow and configurable so the APK can be recreated **exactly**, with
a runtime toggle between the original design language and the current one. **Cut no corners** — if a
field/behaviour exists in the dapp it exists here.

---

## 0. Design languages (runtime toggle)

Two switchable "design languages", chosen in CFG (Settings) and applied app-wide (persisted; activity
`recreate()` on change). All view code reads a central `Design` token object — never hard-coded colors.

- **ORIGINAL** — brutalist/terminal, faithful to the dapp. Monospace everywhere, hard 1px borders,
  **0 radius**, uppercase tracked micro-labels, tabular-nums. Has **light** (default) and **dark**
  (photo-negative) sub-modes; accent `#ff5a1f` constant across both.
- **CURRENT** — the existing native look: dark `#0A0A0F` bg, `#15151F` cards, accent `#F7931A`,
  rounded Material buttons, sans-serif.

The dapp's own nav **theme toggle** (light/dark) applies within ORIGINAL. CURRENT is dark-only.

---

## 1. Design tokens

### 1.1 ORIGINAL — light (`:root`, default)
```
bg #ffffff · surface #f4f4f2 · surface2 #e8e8e5 · surface3 #ddddd9
border #0a0a0a · border2 #c9c9c6
accent #ff5a1f · accent2 #d8430f · accentGlow #fff4ef · accentSoft #ffe9e0
text #1c1c1c · dim #5f5f5f · dim2 #8a8a8a · heading #0a0a0a
red #cc0000 · redSoft #ffe5e5 · amber #9a6b00 · amberSoft #fbf0d6 · blue #1c46e0 · blueSoft #e4e9ff
```
### 1.2 ORIGINAL — dark (`:root[data-theme="dark"]`)
```
bg #000000 · surface #0d0d0f · surface2 #18181b · surface3 #242428
border #f0f0f0 · border2 #383840
accent #ff5a1f · accent2 #ff7847 · accentGlow #1f0f06 · accentSoft #2e1408
text #d6d6d6 · dim #8e8e8e · dim2 #5a5a5a · heading #f5f5f5
red #ff5b5b · redSoft #2a0d0d · amber #e0a93a · amberSoft #2a2008 · blue #6f9bff · blueSoft #11193a
```
### 1.3 CURRENT (existing native dark)
```
bg #0A0A0F · card #15151F · card2 #1F1F2B · accent #F7931A · text #FFFFFF · subtext #9A9AA8
success #2ECC71 · error #E74C3C · pending #E6A23C · divider #2A2A38
```

### 1.4 Type & metrics (ORIGINAL)
- Font: **monospace** (`Typeface.MONOSPACE`). Base 13sp. No global letter-spacing.
- Roles (size/weight/tracking/color): nav-brand 15/700/0.5px/heading; nav-sub 9/—/1.5px/dim UPPER;
  tab 11/700/1.5px UPPER (active = heading + 3px accent underline); modal-title 12/700/2px UPPER;
  field-label 10/700/1.5px/dim UPPER; utxo-amt 12/700 tabular/heading; utxo-tok 9/700/0.8px/dim UPPER;
  sb-total 15/700 tabular; bal-amount 17/700 tabular; bal-symbol 13/700/1.5px UPPER; row-amount 14/700;
  status-pill 9/700/1.2px UPPER; input 13; note 10/0.4px.
- **0 radius everywhere.** Modal hard shadow `4px 4px 0 border`. Toasts `3px 3px 0 border`.
- Spacing: btn 8×14 (small 5×10); input pad 9×10; card pad 12; gaps 6/8/10/16; nav height 52.
- CURRENT keeps current sizes but Material rounded buttons + sans-serif.

### 1.5 Component note colors (`.field-note`, `.toast`, pills)
note ok=accent "[✓]" · info=dim "[i]" · warn=amber "[!]" · error=red "[x]". Status pills (history):
sending=blue/blueSoft · action_needed/repost=amber/amberSoft · confirmed=heading/border/bg · failed=red/redSoft.
dir-badge: out=red, in=accent, self=dim.

---

## 2. Component catalog (ORIGINAL; CURRENT = Material equivalents)

- **Button** `.btn`: bg, 1px border, heading text, mono 11/700/1.2px UPPER, pad 8×14, **0 radius**;
  hover = invert (bg↔heading). `.primary` = accent bg / black text. `.ghost` = dim text. `.small` = 10/1px 5×10.
  disabled opacity .35.
- **Input/textarea** `.field`: full-width, 1px border, bg, heading text, mono; focus = inset 2px accent.
  label row = UPPER dim label (left) + optional accent "action" link (right, e.g. `max`, `next`).
- **Card** `.card`: 1px border, bg, 0 radius. `.contract` = 3px amber left border.
- **Address card**: header row (caret ▾, mono address, `copy` inline button) bg=surface, tappable to
  collapse; meta row (total Minima · N tokens · badge). Collapsed hides rows + meta.
- **UTXO row** grid `16px | amount(1fr) | token | coinid | copy 26px | status`: custom 14px checkbox
  (accent when checked), amount (tabular heading), token (UPPER dim), coinid `…last8` (cursor help),
  `copy` (hover), status pill. selected = accentSoft bg; disabled = .36 opacity.
- **Status pill** (coin): watch / "—" mismatch / "n/3" pending(amber) / "·" confirmed.
- **Modal**: backdrop rgba(10,10,10,.45); panel bg + 1px border + hard shadow, title UPPER 2px + bottom
  border, body, actions right-aligned (ghost Cancel + primary action).
- **tx-row** (confirm/history): left (marker • → ↩ 🔥 + mono addr/coinid + optional tag yours/verify),
  right (amount). `.total` bold + top border. **tx-warn** = red box for irreversibility/stale-tip.
- **Toast**: bottom-center, 1px border + hard shadow, mono 11.5; success=accent left bar "[✓]",
  error "[x]" red, warn "[!]" amber. Auto-dismiss ~3.4s.
- **Empty state**: glyph + UPPER heading + sub + optional action buttons.

---

## 3. App shell

- **Nav** (sticky, 52dp, bottom border): brand `utxoWallet` (15/700) + sub `pick your coins` (9 UPPER dim);
  right = **theme toggle** button ("Dark"/"Light", only in ORIGINAL) + **design toggle** + live block
  `● #<n>` (pulsing accent dot + `#` + chainBlock, "—" until first block).
- **Tabs** (1px border, equal width, right-dividers): **Wallet · Balances · History · CFG**. Active =
  heading text + inset 3px accent underline; inactive dim. Lazy-render Balances/History/CFG on select.

---

## 4. Screens

### 4.1 Wallet
- **Selection bar** (sticky under tabs): left = count ("Pick your coins" / "N coins selected") + total
  (amount + token). Right = **Clear** (disabled if none), **Tools ▾** (Split / Distribute / Untrack —
  enabled only with selection + WRITE + no pending/job; we also surface **Consolidate** here per user),
  **Send →** (enabled: selection + WRITE + not locked + no pending).
- **Distribute banner**: when a job runs — "Distribute in progress — batch X of Y · N of M posted…".
- **Address cards** (with-coins first, empties below): collapsible; COPY address; meta (total Minima ·
  token count · Contract badge). **Coin rows**: checkbox (disabled if unconfirmed/mismatch), amount,
  token, coinid (tap=copy), copy, status pill. Single-tokenid lock (tapping a different token clears).
- **Empty**: 🪙 "No coins yet" + "Show my address" / "Refresh".

### 4.2 Balances
- Per-token **bal-card** (Minima first, native = 3px accent left border): icon (40dp — Minima M glyph /
  token identicon + real icon overlay), symbol (UPPER) + full name, big amount (17/700 tabular) + N coins.
  **Sendable / Locked split** when locked>0. **Description** (clamp 3 lines, show more). **Details**
  (collapsible): Token ID (copy), Decimals, Website link. **Consolidate** button when count ≥ 3.
- Empty: "No custom tokens held." (Minima card always shown).

### 4.3 History
- **history-row**: head = dir-badge (OUT/SELF/IN) + time + status-pill (Sending/Approve in MiniHub/
  Re-post needed/Confirmed/Failed). Summary = amount → recipient (copy). **Details ▾** expands:
  FROM (input coins: addr + amount + copy), TO (recipient or N outputs), CHANGE (yours/external), BURN,
  TX HASH (real → Explorer ↗ `explorer.minima.global/transactions/<txid>`, else "not yet captured"),
  INTERNAL ID, NOTE. Terminal actions: **Resume now** (action_needed/finalizing), **Re-post**
  (rejected/needs_repost), **Dismiss** (failed/repost).
- Status buckets: posting/finalizing/posted→Sending(blue); awaiting_approval→Approve(amber);
  needs_repost→Re-post(amber); confirmed→Confirmed; error/rejected/expired/could_not_confirm→Failed(red).
- Empty: 📜 "No sends yet".

### 4.4 CFG (Settings)
- **Default change address**: radios **Rotate** ("Cycle through your 64 default addresses…") /
  **First input** ("Send change back to the source coin's address…"). State `defaultChangeMode` (rotate).
- **WRITE permission**: status (✓ Confirmed at HH:MM:SS / ✗ Not granted — Send is disabled) + **Re-test**
  (probe `txncreate`→`txndelete`). [On our IPC, "enabled in Minima Core" is the analog — map accordingly.]
- **Node lock state**: ✓ Unlocked / ✗ LOCKED + **Re-check** (probe).
- **Track a coin**: input `0x…` + **Track** → `cointrack enable:true coinid:…`.
- **Appearance**: design language (Original/Current) + theme (Light/Dark for Original).
- **About**: `utxoWallet · v<appVersion>`.

---

## 5. Flows

### 5.1 Send (full construction) — the core
Send modal (title "Send selected coins"):
1. **Selected coins (FROM)**: section "Selected coins" — list each input `…coinid…  amount`, then Total.
2. **Recipient address** — input `Mx… or 0x…`; recognition note (Your wallet / Tracked contract / Not
   recognized / Invalid). `validateAddress`: `0x`+exactly 64 hex, or `Mx`+40–118 alnum.
3. **Amount** + `max` action → `total − burn` (Minima) / `total` (token). `validateAmount(amt, maxSpendable)`:
   AMOUNT_RE `^(0|[1-9][0-9]*)(\.[0-9]{1,44})?$`; >0; ≤ max. Errors: "Invalid amount."/"Max sendable is X."/"Amount must be > 0."
4. **Change goes to** + `next` action (getaddress). Pre-filled by `resolveDefaultChangeAddr` (rotate→
   getaddress; first→source coin addr if mine else getaddress). Editable. Required unless amount+burn==total
   ("No change — full amount sent."). Validate format.
5. **Burn** (Minima only; disabled for tokens) — optional, ≥0.
6. **Preview →** (disabled until valid) → **Confirm modal**.

Confirm modal (title "Confirm transaction"): **Inputs** (each coin + Total in), **Outputs** (each → addr
[tag yours] + amount; **change** ↩ addr [yours/verify] + amount if >0; **burn** 🔥 + amount if >0),
optional stale-tip warning, **irreversibility** tx-warn ("Once signed and posted, this cannot be undone.
Verify the recipient address…"), posting stages, **Back** / **Sign & Post →**.

Build (`buildTransaction`/`signAndPost`), exact order: record history (posting; full inputs+outputs+
changeaddr+burn+postblock) → `txncreate id:uw_…` → `txninput id coinid:` per coin → `txnoutput id amount
address [tokenid:]` per recipient → `txnoutput` change (if >0) → `txnsign id publickey:auto txnpostauto:true
txndelete:true [txnpostburn:<burn>]` (burn only Minima & >0). Post-size guard 64KB (`oversizedPostError`,
pre-flight `estimateTxPowBytes`, model overhead 4096/perInput 6144/perOutput 900). Finalize → find real
txpowid → history `posted`. Pending (READ-mode/approval) → `awaiting_approval`, resume on NEWBLOCK
(throttle 2 blocks, expiry 20 blocks) via single-shot `signAndPost` after `txncheck`.

### 5.2 Split — Tools ▾
"Number of coins (2–15)" + burn (Minima). Each = total/n (DOWN to token decimals), last = remainder.
N fresh getaddress outputs → buildTransaction. Pre-flight size guard. Confirm title "Confirm split",
warn " These coins go to your own wallet addresses."

### 5.3 Distribute — Tools ▾ (FIX the affordability bug)
Modal "Distribute selected coins": **Send to** my-addresses (count 2–64) | external-list (textarea, validated,
deduped); **Amount to each**; **Burn** (Minima). Live summary "N × amount = total [· burn][· change][—
auto-chained as K transactions]". `MAX_ADDR_OUTPUTS_PER_BATCH = 14`, batchCount = ceil(N/14).
**Affordability: `needed = N×perAddr + burn`; reject ONLY if `needed > total` (strict `>`).
`needed == total` (change 0) is ALLOWED.** [Current bug: rejects equal — fix to strict greater + allow
change 0.] Change funds later batches: batch1 change = `total − batch1×perAddr − burn`; only fetch a change
addr if change>0. Pre-conditions: selection, WRITE, not locked, no existing job. Auto-chain: post batch1,
on each NEWBLOCK find the change coin (by coinid; fallback addr+amount), post next ≤14 (no burn on later
batches), expiry ~25 blocks. Confirm shows auto-chain + stale-tip warnings.

### 5.4 Consolidate (per user's spec, kept reachable from Wallet Tools + Balances per-token)
- No selection → `consolidate tokenid:0x00` (auto). (Original full version: dialog maxcoins 3–20 (def 20),
  coinage (def 3), burn, **dry-run** preview "spent/created/net" then post if it reduces. Offer this as the
  Balances-tab per-token path.)
- 2+ selected → merge exactly those into one (`txncreate`/inputs/one output=sum to fresh addr).

### 5.5 Untrack / Track / Receive
Untrack selected → `cointrack enable:false coinid:` per coin (confirm). Track (CFG) → `cointrack enable:true`.
Receive → "Your default address" + QR (white panel) + copy + `next`.

---

## 6. State, persistence, events
- Persist (SharedPreferences here; keypair in dapp): `theme`, `designLanguage`, `defaultChangeMode`,
  `distributeJob`, schema/size-model. History → local SQLite (rich: internalid, txnid, status, recipient,
  amount, tokenid, inputs(JSON), outputs(JSON), changeaddr, burn, ts, note, postblock).
- Events (NOTIFY over IPC): **NEWBLOCK** → update block #, reload coins, resume pending, watch confirmations,
  resume distribute; **NEWBALANCE** → debounced reload. Reads debounced.
- Selection model: `selected` set, `selectedTokenid` lock, `sendableCoinIds` (filtered query),
  `myWalletAddresses`/`myScriptAddresses` (from `scripts`).

## 7. Command reference (over MinimaAPI.Command)
`coins relevant:true [sendable:true]` · `balance` · `tokens` · `getaddress` · `scripts` · `block` ·
`checkaddress address:` · `txncreate/txninput/txnoutput/txnsign/txndelete` · `consolidate` · `cointrack` ·
`history` (we keep our own SQLite). Explorer: `https://explorer.minima.global/transactions/<txid>`.

## 8. Build order
1) Design tokens + toggle → 2) shell (nav+tabs) → 3) Wallet (retoken) → 4) Send+Confirm (full) →
5) Balances (rich) → 6) History (rich) → 7) CFG → 8) Distribute fix + Split/Consolidate/Untrack →
9) build → code-review → fix → review → release.
