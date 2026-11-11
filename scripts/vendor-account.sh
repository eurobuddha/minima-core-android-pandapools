#!/usr/bin/env bash
# Vendor the shared Trading Account (com.eurobuddha.account) VERBATIM from Dexicon, its single source, into this app.
# One header line names the source and Dexicon's version; the rest is Dexicon's bytes. `--check` fails on any drift.
# The same 24 words give the same keys in Dexicon, AtomiX and PandaPools (SameKeysEverywhereTest pins them); the apps
# are mutually exclusive on a phone (Family) — never edit these files here: change them in Dexicon and re-vendor.
set -euo pipefail
cd "$(dirname "$0")/.."
DEX=${DEXICON:-../dexicon}
P=com/eurobuddha/account
MAIN="KeyUses PrefsKeyUses AccountKeys AccountVault VirtualWallet Account AccountSetup Transport Family ArchiveProofs"
TESTS="AccountKeysTest VirtualWalletTest KeyCountMigrationTest AccountTestAccess SameKeysEverywhereTest AccountRouteTest LeafShareTest FamilyRuleTest FamilyInstalledTest"
VER=$(grep -E 'versionName' "$DEX/app/build.gradle" | head -1 | grep -oE '"[^"]+"' | tr -d '"')
SHA=$(git -C "$DEX" rev-parse --short HEAD 2>/dev/null || echo unknown)
head_for() { echo "// VENDORED VERBATIM from apks/dexicon $VER ($SHA) $1 by scripts/vendor-account.sh — do not edit here; change Dexicon and re-vendor."; }
fail=0; MODE="${1:-}"
one() {  # $1 = src root, $2 = dst root, $3 = class
  local f="$P/$3.java" src="$1/$P/$3.java" dst="$2/$P/$3.java"
  [ -f "$src" ] || { echo "SOURCE MISSING: $src"; fail=1; return; }
  if [ "$MODE" = "--check" ]; then
    [ -f "$dst" ] || { echo "MISSING: $dst"; fail=1; return; }
    tail -n +2 "$dst" | cmp -s - "$src" || { echo "DRIFT: $dst"; fail=1; }
  else
    mkdir -p "$(dirname "$dst")"; { head_for "$f"; cat "$src"; } > "$dst"
  fi
}
for c in $MAIN; do one "$DEX/app/src/main/java" app/src/main/java "$c"; done
for c in $TESTS; do one "$DEX/app/src/test/java" app/src/test/java "$c"; done
if [ "$MODE" = "--check" ]; then
  # nothing else may live in the shared package here (it would not be in the other apps)
  for f in app/src/main/java/$P/*.java app/src/test/java/$P/*.java; do
    [ -f "$f" ] || continue
    head -1 "$f" | grep -q '^// VENDORED VERBATIM from apks/dexicon ' || { echo "NOT VENDORED: $f"; fail=1; }
  done
  [ $fail = 0 ] && echo "ACCOUNT VENDOR PASS — matches apks/dexicon $VER" || { echo "ACCOUNT VENDOR FAIL — run scripts/vendor-account.sh"; exit 1; }
else
  echo "vendored the Trading Account from apks/dexicon $VER ($SHA)"
fi
