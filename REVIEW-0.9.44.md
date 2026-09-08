# PandaPools 0.9.44 review checkpoint

The 0.9.43 UI process was killed at 23:26:46 on 8 September 2026 while receiving a single full-history reply: Android reported TransactionTooLargeException, a 205820-byte parcel, and exit reason 13 / subreason 26. This is distinct from Minima's consensus TxPoW-size rejection.

## Changes and reuse

- NodeTransportService hosts the existing minimaapi.aar receiver in a private process. Same-UID Messenger messages carry UUIDs, and private cache files carry bounded payloads. No RPC listener or node modification. Service disconnects fail pending requests without replay; transport errors deliberately omit status so the signing quarantine remains set.
- ReceiptRecovery reuses eight unchanged files from `/Users/eurobuddha/Projects/minima/maxima/core/src/main/java/com/eurobuddha/maxima/core/{codec,msg}`. SHA3Digest usage and dependency match PocketWeb's PocketProtocol. Only the JSON adapter and bounded decimal-scale reconstruction are new.
- A complete mined header must hash to its stated TxPoW ID before the nonce-zero/body-hash-zero submission ID can be recovered. This identity proof is followed by independent `txpow onchain` inclusion/depth checks. Five-minute time filtering only selects recovery candidates; it cannot confirm a receipt.
- Legacy original submission IDs remain stored and visible in details. DB upgrade replays retained history without dropping records. No receipts are hidden or archived.

## Code review

### Summary

The exact legacy consolidation fixture reproduces both the real mined hash and the saved submission ID. Recovery is bounded and runs off the UI thread. Internal service access is non-exported and same-UID checked; no signing logic moves into the transport. Review caught incomplete-history passes being marked finished; they now retain the repair flag. Null transport replies also retain the uncertain-write guard.

### Validation

172 tests, zero failures/errors in each debug and release suite; lintRelease passes. The fixture is the public mined header `0x0000B366B9A4E506FB1DAE5DA3D6753C7315CC671ED579822682A1E93698A632`, which recovers original receipt `0x8C6B7401E1343318C7EC80563408C9978C40B7CA59EF76CE443EB0D2B68D27EB`. Stock-node evidence previously placed it at block 2304536 with 31 confirmations at tip 2304567. Tests reject changed headers, unrelated mined IDs and malformed parents. Transport tests cover large payloads, allocation bounds, invalid paths and uncertain outcomes.

APK: `releases/pandapools-0.9.44.apk` (exclusive creation; no versioned artifact overwritten).
SHA-256: `a034a41e41fab9a325d152b690e385fed99de3798a197717d4e9fc808929b380`.
Signer SHA-256: `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.

### Remaining device gate / verdict

Approve for native device validation, not a claim that all historical receipts are recovered. The S23 is reconnected and still running 0.9.43 before this checkpoint is committed. Confirm 0.9.44 process survival, full-history behaviour and real receipt counts after installation. A private process contains UI exposure; stock Android may still reject a large broadcast in that process. Headers absent from retained node history cannot be reconstructed from amount/time. Broad fund-safety review and MDS/desktop parity remain unfinished.
