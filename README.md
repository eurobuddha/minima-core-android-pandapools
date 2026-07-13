# PandaPools — Android

A native Android **constant-product AMM** for [Minima](https://minima.global). Swap MINIMA against a token across aggregated micro-pools, provide liquidity, and earn the **0.5 % swap fee** — trustlessly, on-chain, no custodian. The app talks to a local Minima Core node on-device via the `minimaapi` broadcast-Intent IPC.

It shares the **same mainnet registry and 0.5 % covenant** as the [PandaPools MiniDapp](https://github.com/eurobuddha/pandapools-mds), so both trade the **same live pools**.

> ⚠️ **Development software — use at your own risk.** PandaPools is experimental, actively-developed software provided **AS IS**, without warranty of any kind. It builds and posts real on-chain transactions that move real funds; despite extensive testing and code review, bugs may exist. Test with small amounts first, keep your seed backed up, and only risk what you can afford to lose. Nothing here is financial advice.

## Features

- **Swap** — trades are routed across every pool for a pair (water-filling by best price) and settle in one transaction; all-or-nothing.
- **Provide liquidity** — create a pool or add to one; LPs earn the 0.5 % fee, which accrues inside the pool (K grows). No LP token.
- **Five tabs** — Swap · Pools · My LP · Wallet · Activity (personal history + a live all-pools activity feed).
- **5-layer pool recovery** — durable recipe persistence, re-track-on-launch, backup/restore, and faded-beacon re-announce, so you can always find and withdraw your pools — even after a node resync or on a new device.

## Design

Pools are two reserve coins at a **unique covenant address** (the covenant's params are hardcoded literals, so the address is its script hash) plus a discovery beacon at a shared sentinel. The covenant enforces the constant-product invariant with a **KMIN product floor** that defeats a dust-pairing drain unique to the UTXO/2-coin model. All fund math is exact (grain-floored, pool-favourable rounding), and every post is gated on the covenant's own `txncheck` verdict. See the MiniDapp repo's README for a fuller design write-up.

## Build

Standard Android Gradle build (the release variant is debug-signed for install parity):

```bash
./gradlew assembleRelease
```

The build is pinned to Android Studio's bundled JBR 21 (`org.gradle.java.home` in `gradle.properties`). Distributed via the [PandaApps](https://github.com/eurobuddha/minima-core-apks) store.

## License

[MIT](LICENSE) © 2026 eurobuddha for this app's original code. It bundles Apache-2.0 components from Minima (`minimaapi.aar`, and other Minima Core parts) which retain their own license — see [NOTICE](NOTICE).
