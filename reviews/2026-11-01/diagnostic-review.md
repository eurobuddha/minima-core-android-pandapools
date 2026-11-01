# Diagnostic release review

Scope: PandaPools 0.9.70-diagnostic and Pandamonium 2.2.3-diagnostic (version code 273).

The 2026-10-31 automatic-signing candidate remains blocked. This diagnostic removes the signature probe and removes the state-confirmation capability from the recovery coordinator. It only probes the node mode, reads wallet status/keys, and creates missing deterministic addresses within the existing bounded search. An exact public-key/index/tree match records the derivation index while retaining the signing hold. It does not change node signing counters.

File restore still validates and tracks pool covenants, checks/imports reserve proofs through the existing recovery implementation, and saves recipes. It cannot invoke automatic Classic withdrawal. The existing manual signing-state confirmation is blocked in diagnostic builds at both the UI and durable store boundary. Existing healthy pools and ordinary wallet operations are otherwise unaffected.

Results distinguish matched keys from verified reserves and can be copied for support. Matching an owner key is not represented as proof that signing is safe. Both Classic and block-mode restored pools remain paused.

Validation: the coordinator suite enforces a command allowlist in every scenario and asserts signing holds remain set. It covers missing/existing keys, wrong seed, unknown/Classic mode, changed wallet/mode, persistent search bounds, missing/advanced backup counters, storage failures and duplicate callbacks. The old reused-leaf scenario is now exercised with zero signing requests and an unchanged node counter. Full build/test counts and APK digests are recorded in the release notes/checksum files.

In-place compatibility: unchanged package com.eurobuddha.pandamonium, same family signing certificate, version code 273 versus 270 in the existing 2.2.0 APK. No data reset or uninstall step is used.

Verdict: approved for a diagnostic prerelease, not automatic signing recovery. No live restored wallet has been exercised. The earlier full-host lint debt remains documented in the 2026-10-31 review; no lint checks were suppressed for this release.
