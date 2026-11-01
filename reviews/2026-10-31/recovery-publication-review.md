# Code Review

## Summary

Publication is blocked by a reproduced one-time signing-key reuse bug in automatic block-mode recovery. The candidate correctly matches owner keys and bounds regeneration, but treats a recent block and successful signing-counter read-back as proof that signing is safe. Those checks cannot establish whether the old wallet already signed at that index. This finding supersedes the earlier “approve with suggestions” assessment.

Reviewed artifacts: PandaPools 0.9.69 and Pandamonium 2.2.2 candidate. No candidate was published during this review. The reproduction uses a fixed public disposable seed and the bundled node JAR; it does not open any real wallet, connect to a node, post transactions, or access funds.

## Findings

### CRITICAL — Recovery can reuse a signing leaf and then enable automatic refresh

**File:** `app/src/main/java/com/eurobuddha/pandapools/BlockOwnerRecovery.java:125` (embedded copy has the same logic), especially the `sign ... data:0x00` call at line 130 and durable confirmation at line 143.

**Problem:** `height > savedFloor` is insufficient when the recipe is old or contains no signing count. Minima's block-based selector chooses the maximum supported by chain height and the counters it currently has. Multiple signatures during one block can move the original wallet's counter beyond chain height. Seed restoration loses the original wallet's later per-key and global counters. The restored node can therefore select an index already used by the stopped original node. The dummy signature is itself a real one-time-key use; checking the incremented counter afterward cannot undo reuse.

**Reproduction with the actual bundled implementation:**

1. At block 2,399,999, the old wallet's persisted global counter is 2,399,999. Its next signature uses index 2,400,000.
2. Stop that wallet. Restore the same seed, with lost counters and an older recipe whose floor is below 2,400,000.
3. The restored chain reaches block 2,400,000. The recovery signature also uses index 2,400,000.
4. Both signatures verify for different messages with the identical one-time public key. Read-back is 2,400,001, which satisfies the candidate's checks and would clear the signing hold.

Actual output is saved in `leaf-probe.log`. `RestoredLeafProbe.java` invokes the bundled `block.getCurrentBlockAsKeyUses` and `TreeKey` implementations; only the chain tip and counter persistence are replaced with in-memory fixtures. This counterexample does not require two active wallet copies. The fixture demonstrates the missing invariant, not that this particular remote user's key has already been reused.

**Fix:** Separate deterministic key recovery from authorization to sign. Missing keys can be regenerated and matched automatically. Do not issue the probe signature or clear the hold solely from block height and an old recipe. Resuming signing needs authoritative signing state that covers the old wallet's later signatures, or a separately justified recovery design. A diagnostic candidate can test key discovery and reserve recovery while retaining the signing hold. This changes the requested automatic-control behavior and requires the user's direction before implementation.

## Validation and limits

- Existing unit suites: 330 standalone and 377 embedded tests passed in the preceding build. Their mocked replies do not test the old wallet's previously consumed leaves.
- The actual-node reproduction demonstrates identical one-time public keys for two different valid signatures at the same index, despite stopping the original wallet.
- Both existing APKs are signed with the established family release certificate. Signature validity does not establish recovery safety.
- Existing Pandamonium lint debt remains: 15 MissingTranslation errors in unchanged host resources; library-only lint also reports ExactAlarm because the imported library has no targetSdk of its own. Host APK targets API 35, and release vital lint passed. These are separate from the signing blocker.

## Verdict

BLOCK publication of the current automatic-signing candidates for real-pool recovery. Await the user's choice between a key-discovery diagnostic release and holding publication while signing recovery is resolved. Do not describe the existing candidate as verified safe for seed-only restoration.
