# Graph Report - .  (2026-07-27)

## Corpus Check
- 69 files · ~58,187 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 952 nodes · 2404 edges · 38 communities (36 shown, 2 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 153 edges (avg confidence: 0.81)
- Token cost: 209,849 input · 0 output

## Community Hubs (Navigation)
- Wallet & Token Cards UI
- My LP Pool UI
- Activity History View
- Swap Engine & Quotes
- App Bootstrap & Lifecycle
- Design Token System
- Pool Manager Operations
- Swap Math Tests
- History & Util Helpers
- Base Views & Pool Cards
- Doze Keep-Alive Service
- Own-Pool Store & Covenant Tests
- Project Docs & Design Rationale
- Global Event Feed
- Backup & Restore
- Pool Book Scanner
- Price Oracle
- Cloud Anchor Re-announcer (Python)
- LP Store
- Node API IPC
- Beacon Re-announcer
- Command Chain Runner
- Token Icon Resolver
- Transaction Poster
- Owner Key Recovery
- Pool Book State Tests
- Gradle Wrapper Script
- Minima Coin Branding
- Launcher Icon (hdpi)
- Launcher Icon (mdpi)
- Launcher Icon (xhdpi)
- Launcher Icon (xxhdpi)
- Launcher Icon (xxxhdpi)
- Minima Logo Branding
- Minima SVG Icon
- Agent Instruction Rule

## God Nodes (most connected - your core abstractions)
1. `Pool` - 69 edges
2. `MyLpView` - 65 edges
3. `MainActivity` - 51 edges
4. `ActivityView` - 45 edges
5. `Design` - 36 edges
6. `NodeApi` - 36 edges
7. `SwapView` - 36 edges
8. `PoolManager` - 33 edges
9. `WalletView` - 32 edges
10. `BaseView` - 20 edges

## Surprising Connections (you probably didn't know these)
- `CURRENT Design Language (native Material dark)` --semantically_similar_to--> `PandaPools — Android`  [INFERRED] [semantically similar]
  DESIGN_MAP.md → README.md
- `Minima Command Reference over MinimaAPI.Command` --conceptually_related_to--> `minimaapi Broadcast-Intent IPC`  [INFERRED]
  DESIGN_MAP.md → README.md
- `Covenant Reconstruction and parseok Gate` --conceptually_related_to--> `0.5% Pool Covenant (unique address per pool)`  [INFERRED]
  anchor/README.md → README.md
- `PandaPools Cloud Anchor — Beacon Re-announcer` --conceptually_related_to--> `5-Layer Pool Recovery`  [EXTRACTED]
  anchor/README.md → README.md
- `Pool Registry Beacon (unspendable sentinel dust)` --shares_data_with--> `PandaPools MiniDapp (shared registry sibling)`  [INFERRED]
  anchor/README.md → README.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Pool Discovery Persistence (beacon lifecycle)** — readme_five_layer_pool_recovery, anchor_readme_beacon_reannouncer, anchor_readme_registry_beacon, anchor_readme_fund_safety_model [EXTRACTED 0.90]
- **utxoWallet Transaction Construction Flows** — design_map_send_flow, design_map_split_flow, design_map_distribute_flow, design_map_consolidate_flow, design_map_minima_command_reference [EXTRACTED 0.90]

## Communities (38 total, 2 thin omitted)

### Community 0 - "Wallet & Token Cards UI"
Cohesion: 0.06
Nodes (20): Identicon, Bitmap, ImageLoader, Bitmap, JSONObject, TokenBalance, Bitmap, GradientDrawable (+12 more)

### Community 1 - "My LP Pool UI"
Cohesion: 0.08
Nodes (11): EditText, LinearLayout, MathContext, Override, TextView, Uri, View, MyLpView (+3 more)

### Community 2 - "Activity History View"
Cohesion: 0.07
Nodes (10): ActivityLog, Entry, Context, SharedPreferences, ActivityView, LinearLayout, Override, TextView (+2 more)

### Community 3 - "Swap Engine & Quotes"
Cohesion: 0.05
Nodes (15): Coin, JSONObject, JSONArray, JSONObject, MarketPrice, Route, FundCb, PoolTxn (+7 more)

### Community 4 - "App Bootstrap & Lifecycle"
Cohesion: 0.06
Nodes (18): ActivityResultLauncher, HistoryDb, Context, Override, Listener, Handler, Override, TextView (+10 more)

### Community 5 - "Design Token System"
Cohesion: 0.09
Nodes (12): Design, Context, Mode, CURRENT, ORIGINAL_DARK, ORIGINAL_LIGHT, Context, GradientDrawable (+4 more)

### Community 6 - "Pool Manager Operations"
Cohesion: 0.07
Nodes (11): AddrCb, CreateResult, ForwardResult, PoolManager, Result, SelCb, SweepResult, Context (+3 more)

### Community 7 - "Swap Math Tests"
Cohesion: 0.10
Nodes (10): Alloc, MathContext, PoolRouter, MathContext, Quote, VirtualCurve, Test, PoolRouterTest (+2 more)

### Community 8 - "History & Util Helpers"
Cohesion: 0.09
Nodes (7): HistoryEntry, JSONArray, JSONObject, JSONObject, Util, Test, UtilTest

### Community 9 - "Base Views & Pool Cards"
Cohesion: 0.08
Nodes (13): BaseView, View, NonNull, Override, View, MainPager, Override, TextView (+5 more)

### Community 10 - "Doze Keep-Alive Service"
Cohesion: 0.09
Nodes (21): BootReceiver, Context, Intent, Override, HeartbeatReceiver, Context, Intent, Override (+13 more)

### Community 11 - "Own-Pool Store & Covenant Tests"
Cohesion: 0.13
Nodes (6): Context, SharedPreferences, OwnPoolStore, PoolCovenant, Test, PoolCovenantTest

### Community 12 - "Project Docs & Design Rationale"
Cohesion: 0.10
Nodes (24): PandaPools Cloud Anchor — Beacon Re-announcer, Covenant Reconstruction and parseok Gate, Faucet Auto-Funding, Anchor Fund-Safety Model, Pool Registry Beacon (unspendable sentinel dust), Consolidate Flow (merge coins), CURRENT Design Language (native Material dark), Runtime Design-Language Toggle (ORIGINAL / CURRENT) (+16 more)

### Community 13 - "Global Event Feed"
Cohesion: 0.21
Nodes (6): Event, GlobalFeed, Context, MathContext, SharedPreferences, Snap

### Community 14 - "Backup & Restore"
Cohesion: 0.20
Nodes (6): BackupCb, Context, JSONArray, JSONObject, Recovery, RestoreCb

### Community 15 - "Pool Book Scanner"
Cohesion: 0.18
Nodes (4): JSONObject, Pattern, Listener, PoolBook

### Community 16 - "Price Oracle"
Cohesion: 0.17
Nodes (7): Anchor, Cb, Handler, JSONArray, JSONObject, MathContext, PriceOracle

### Community 17 - "Cloud Anchor Re-announcer (Python)"
Cohesion: 0.24
Nodes (16): block(), derive(), faucet_topup(), main(), pool_script(), Largest coin per leg = the true reserve (dust can't masquerade). Returns (reserv, Best-effort: request a drip from the co-located faucet so the anchor self-funds., Relay a fresh beacon: dust send to the sentinel carrying the SAME state map (ver (+8 more)

### Community 18 - "LP Store"
Cohesion: 0.31
Nodes (5): Context, MathContext, SharedPreferences, LpStore, Snapshot

### Community 19 - "Node API IPC"
Cohesion: 0.23
Nodes (7): Cb, Context, Handler, JSONObject, NodeApi, PairingListener, MinimaAPI

### Community 20 - "Beacon Re-announcer"
Cohesion: 0.26
Nodes (4): Context, JSONObject, Listener, ReAnnouncer

### Community 21 - "Command Chain Runner"
Cohesion: 0.26
Nodes (3): CmdChain, Done, JSONObject

### Community 22 - "Token Icon Resolver"
Cohesion: 0.27
Nodes (3): IconResolver, Pattern, TokenMeta

### Community 23 - "Transaction Poster"
Cohesion: 0.27
Nodes (3): Done, JSONObject, TxPost

### Community 24 - "Owner Key Recovery"
Cohesion: 0.31
Nodes (3): Cb, JSONObject, OwnerKeyRecovery

### Community 26 - "Gradle Wrapper Script"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 27 - "Minima Coin Branding"
Cohesion: 0.50
Nodes (4): PandaPools Android App UI, Minima Brand Logo (angular M mark), Minima Coin Icon (drawable), Minima Native Token (MINIMA)

### Community 28 - "Launcher Icon (hdpi)"
Cohesion: 0.67
Nodes (4): Android Adaptive Launcher Icon System, Launcher Icon Foreground (hdpi), MiniDapp Grid Motif (green squares in circle), PandaPools App Branding

### Community 29 - "Launcher Icon (mdpi)"
Cohesion: 0.67
Nodes (4): Android Adaptive Launcher Icon System, Green Block-Grid Motif, PandaPools Launcher Icon Foreground (mdpi), PandaPools App Branding

### Community 30 - "Launcher Icon (xhdpi)"
Cohesion: 0.67
Nodes (4): Android Adaptive Launcher Icon Foreground Layer, Green 2x2 Grid-in-Circle Motif, PandaPools Launcher Foreground Icon (xhdpi), PandaPools App Branding

### Community 31 - "Launcher Icon (xxhdpi)"
Cohesion: 0.67
Nodes (4): PandaPools Launcher Foreground Icon (xxhdpi), Android Adaptive Launcher Icon, Minima Visual Identity (Green-on-Black Blocks Motif), PandaPools App Branding

### Community 32 - "Launcher Icon (xxxhdpi)"
Cohesion: 0.67
Nodes (4): Android Adaptive Launcher Icon (foreground layer), Green Circle with 2x2 Grid-of-Squares Motif, PandaPools Launcher Foreground Icon (xxxhdpi), PandaPools App Branding

### Community 33 - "Minima Logo Branding"
Cohesion: 1.00
Nodes (3): PandaPools Android App UI, Minima Blockchain Brand, Minima Logo (App Drawable)

### Community 34 - "Minima SVG Icon"
Cohesion: 1.00
Nodes (3): PandaPools App Branding Asset, Minima Blockchain Brand Identity, Minima Icon SVG

## Knowledge Gaps
- **17 isolated node(s):** `ORIGINAL_LIGHT`, `ORIGINAL_DARK`, `CURRENT`, `ORIGINAL Design Language (brutalist/terminal)`, `Design Tokens (central Design token object)` (+12 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Pool` connect `My LP Pool UI` to `Swap Engine & Quotes`, `App Bootstrap & Lifecycle`, `Pool Manager Operations`, `Swap Math Tests`, `Base Views & Pool Cards`, `Own-Pool Store & Covenant Tests`, `Global Event Feed`, `Backup & Restore`, `Pool Book Scanner`, `Beacon Re-announcer`?**
  _High betweenness centrality (0.197) - this node is a cross-community bridge._
- **Why does `MainActivity` connect `App Bootstrap & Lifecycle` to `Wallet & Token Cards UI`, `Activity History View`, `Design Token System`, `Base Views & Pool Cards`, `Node API IPC`?**
  _High betweenness centrality (0.146) - this node is a cross-community bridge._
- **Why does `NodeApi` connect `Node API IPC` to `Swap Engine & Quotes`, `App Bootstrap & Lifecycle`, `Design Token System`, `Pool Manager Operations`, `Doze Keep-Alive Service`, `Backup & Restore`, `Pool Book Scanner`, `Beacon Re-announcer`, `Command Chain Runner`, `Transaction Poster`, `Owner Key Recovery`?**
  _High betweenness centrality (0.128) - this node is a cross-community bridge._
- **What connects `ORIGINAL_LIGHT`, `ORIGINAL_DARK`, `CURRENT` to the rest of the system?**
  _17 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Wallet & Token Cards UI` be split into smaller, more focused modules?**
  _Cohesion score 0.06190476190476191 - nodes in this community are weakly interconnected._
- **Should `My LP Pool UI` be split into smaller, more focused modules?**
  _Cohesion score 0.07919506653683869 - nodes in this community are weakly interconnected._
- **Should `Activity History View` be split into smaller, more focused modules?**
  _Cohesion score 0.06771929824561404 - nodes in this community are weakly interconnected._