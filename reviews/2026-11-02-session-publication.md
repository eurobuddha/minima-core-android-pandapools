# Session release publication — 2 November 2026

The user authorized committing, pushing and publishing the session fixes. All nine application source repositories are committed and pushed; the versions below are public GitHub releases. Existing unrelated working-tree changes were preserved. No device installation, live trade, wallet restore, community announcement, or Apple/Google store submission was performed.

## Releases

| Application | Version / release | Source commit |
| --- | --- | --- |
| PandaPools | [0.9.74](https://github.com/eurobuddha/minima-core-android-pandapools/releases/tag/v0.9.74) | [a8281934fd3c](https://github.com/eurobuddha/minima-core-android-pandapools/commit/a8281934fd3c11628aa59fcefcdb0162d9559627) |
| Pandamonium | [2.2.7-Pandamonium](https://github.com/eurobuddha/minima-core-android/releases/tag/v2.2.7-Pandamonium) | [eee948c10492](https://github.com/eurobuddha/minima-core-android/commit/eee948c10492b8652b63365e10e28f1656c7e471) |
| Dexicon Android | [0.1.57](https://github.com/eurobuddha/minima-core-android-dexicon/releases/tag/v0.1.57) | [33735997e906](https://github.com/eurobuddha/minima-core-android-dexicon/commit/33735997e9068a2697fc35adf11be04e31e716a1) |
| PandaDEX Android | [0.4.25](https://github.com/eurobuddha/minima-core-android-pandadex/releases/tag/v0.4.25) | [12c5864f59da](https://github.com/eurobuddha/minima-core-android-pandadex/commit/12c5864f59da6b3b783088abd6779b8a7fc5dd83) |
| Minima History | [0.3.16](https://github.com/eurobuddha/minima-core-android-history/releases/tag/v0.3.16) | [cedc4e12f3a6](https://github.com/eurobuddha/minima-core-android-history/commit/cedc4e12f3a64c0f9f7483065e1c88cf2dfce458) |
| PandaPools MiniDapp | [0.6.34](https://github.com/eurobuddha/pandapools-mds/releases/tag/v0.6.34) | [8e3830e8fe55](https://github.com/eurobuddha/pandapools-mds/commit/8e3830e8fe553a4f1e5c0eebcefc98c0eab8a544) |
| Dexicon MiniDapp | [0.0.50](https://github.com/eurobuddha/dexicon/releases/tag/v0.0.50) | [90e3b04bef36](https://github.com/eurobuddha/dexicon/commit/90e3b04bef367552e9bb00bc04bceb9c5b3d17e9) |
| PandaDEX MiniDapp | [0.4.21](https://github.com/eurobuddha/pandadex-mds/releases/tag/v0.4.21) | [f32ffb1387a1](https://github.com/eurobuddha/pandadex-mds/commit/f32ffb1387a1f796025bb19a8acf5199a8813420) |
| minimaCore Desktop, Mac/Windows/Linux | [0.18.32](https://github.com/eurobuddha/minimacore-desktop/releases/tag/v0.18.32) | [e8dd701d9dbf](https://github.com/eurobuddha/minimacore-desktop/commit/e8dd701d9dbf8be66a9e214f7ce99a2e9e55c94a) |

Release tags identify the tested source commits. GitHub asset digests match the locally verified APK/ZIP hashes. Desktop CI passed on all three platforms; the Mac release uses the locally signed, notarized and stapled `-notarized.dmg`, not an unsigned CI artifact.

## Store publication

- PandaApps catalogue: commit `db8e78c`, 58 entries and 57 binaries validated. Eight updated rows: five Android apps and all three desktop platforms.
- PandaDapps catalogue: commit `47a1638`, three updated apps; unrelated app data retained. Public ZIP bytes were downloaded and checked with the existing SHA-256 utility.
- Public website catalogues were replaced atomically, with pre-update backups retained on the server.
- The existing Sally mirror sync passed. Origin, store mirror and desktop update feed were read back and compared against the complete expected JSON.
- Desktop feed lists `mac-arm64`, `win-x64` and `linux-x64`, all at 0.18.32.

## Release validation notes

The automated regression/build evidence and recovery limits remain documented in [the implementation review](2026-11-02-session-fixes.md). Publication does not turn an untested physical-device backup/restore workflow into a completed acceptance test.

The required Dexicon AtomiX vendor check reported one pre-existing difference: Dexicon clears temporary `boxSeed` and `signSeed` arrays after constructing the key pairs. The donor lacks that cleanup; key derivation and output are otherwise unchanged. That existing protection was retained. The vendor check is not reported as passing.

Source branches were preserved, including PandaPools `review/0.8.9`; no unrelated branch merge or history rewrite was performed.

## Desktop installer hashes

```text
72b869924e11e130198736ce5696343acdfcc558d6fd0fea385d6190a23338c8  minimaCore-0.18.32-arm64-notarized.dmg
82ef86402ede9a65672a0584ec6e5e5e7d49df8a22da38c999ec836eecb8c11c  minimaCore-0.18.32-x64.exe
07b614f439d934edbfb8b4f5dfd16ca7f8e008d29e87185afd6d6b458cd53caa  minimaCore-0.18.32-x64.AppImage
```

## IPFS mirror verification

Published snapshot: [`bafybeigdhvzq45ds7crpvzyd4pnuueix3u2jiexvf57ln2irfgskxzpwde`](https://ipfs.eurobuddha.com/ipfs/bafybeigdhvzq45ds7crpvzyd4pnuueix3u2jiexvf57ln2irfgskxzpwde/). All 58 native/desktop catalogue entries and all 36 MiniDapp entries match the expected versions and hashes. The three updated MiniDapp ZIPs were downloaded from the immutable snapshot and independently hash-checked. The server publication markers were updated at 20:37 UTC. The original SSH client lingered after the server process finished; it was closed only after independently verifying the completed snapshot.
