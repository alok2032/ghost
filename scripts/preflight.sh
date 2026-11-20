#!/usr/bin/env bash
# AYRA AI — preflight guard. Run BEFORE every commit and as the FIRST job in CI.
# Usage: bash scripts/preflight.sh
# Exit:  0 = clean (0 FAILs), 1 = at least one FAIL. WARN never fails the build.
set -u
cd "$(dirname "$0")/.." || exit 2

FAILS=0
WARNS=0
fail() { echo "  FAIL  $*"; FAILS=$((FAILS+1)); }
warn() { echo "  WARN  $*"; WARNS=$((WARNS+1)); }
ok()   { echo "  ok    $*"; }
section() { echo; echo "== $* =="; }

SRC_FILES() {
  find . -type f \
    \( -name '*.kt' -o -name '*.kts' -o -name '*.ts' -o -name '*.mjs' -o -name '*.xml' \
       -o -name '*.json' -o -name '*.md' -o -name '*.yml' -o -name '*.properties' \) \
    -not -path './.git/*' -not -path '*/node_modules/*' -not -path '*/build/*' \
    -not -path './.gradle/*' -not -path './backend/dist/*' -not -path './public/*' \
    -not -path './app/schemas/*' -not -path './docs/INCIDENTS.md' -not -path './docs/baseline-preflight.txt' \
    -not -name 'package-lock.json' -not -name '*.example' -not -path './scripts/*' \
    -not -path './.template/*' -not -path './.build-outputs/*'
}
MAIN_KT() { find app/src/main -name '*.kt' 2>/dev/null; }

# ---------------------------------------------------------------- 1
section "1. Firebase Single Source of Truth (app/google-services.json)"
GS="app/google-services.json"
if [ ! -f "$GS" ]; then
  warn "$GS missing (acceptable on fresh clone only if CI injects it from secret)"
else
  PID=$(grep -o '"project_id": *"[^"]*"' "$GS" | head -1 | sed 's/.*: *"//;s/"//')
  PKG=$(grep -o '"package_name": *"[^"]*"' "$GS" | head -1 | sed 's/.*: *"//;s/"//')
  APPID=$(grep -o 'applicationId *= *"[^"]*"' app/build.gradle.kts | head -1 | sed 's/.*= *"//;s/"//')
  [ -n "$PID" ] && ok "active project_id resolved from $GS" || fail "cannot read project_id from $GS"
  if [ "$PKG" = "$APPID" ]; then
    ok "package_name == applicationId ($APPID)"
  else
    fail "package_name '$PKG' != applicationId '$APPID'"
  fi
  STRAY=$(SRC_FILES | grep -v 'google-services\.json' | xargs grep -nE 'gen-lang-client-[0-9]+' 2>/dev/null \
          | grep -vE '(test/|Test\.kt|\.test\.mjs)' || true)
  if [ -n "$STRAY" ]; then
    fail "hardcoded Firebase project IDs found outside google-services.json (SSOT violation):"
    echo "$STRAY" | sed 's/^/        /' | cut -c1-170
  else
    ok "no hardcoded gen-lang-client-<digits> outside google-services.json"
  fi
fi

# ---------------------------------------------------------------- 2
section "2. Build & Signing Hygiene"
if [ -x gradlew ]; then
  ok "gradlew has executable permission"
else
  fail "gradlew is not executable (run: chmod +x gradlew / git update-index --chmod=+x gradlew)"
fi

HAS_CONTAINER_KEYSTORE=0
if grep -q 'rootProject.file("debug.keystore")' app/build.gradle.kts; then
  if [ -f debug.keystore.base64 ] && grep -q 'validateProductionReleaseSigning' app/build.gradle.kts; then
    HAS_CONTAINER_KEYSTORE=1
    warn "AI Studio container signingConfigs retains debug.keystore; CI/Play builds MUST supply keystore.properties + -PayraRequireProductionSigning=true"
  elif [ ! -f debug.keystore ]; then
    fail "app/build.gradle.kts points to missing debug.keystore without container base64/upload fallback"
  else
    warn "app/build.gradle.kts references debug.keystore"
  fi
else
  ok "app/build.gradle.kts does not depend on debug.keystore"
fi

if grep -nE 'storePassword *= *"[^"]+"|keyPassword *= *"[^"]+"' app/build.gradle.kts >/dev/null; then
  if [ "$HAS_CONTAINER_KEYSTORE" -eq 1 ]; then
    warn "AI Studio container signingConfigs contains debug password (guarded by validateProductionReleaseSigning when -PayraRequireProductionSigning=true)"
  else
    fail "hardcoded signing password in app/build.gradle.kts"
  fi
else
  ok "no hardcoded signing passwords in app/build.gradle.kts"
fi

if grep -qE '^\s*versionCode *= *1$' app/build.gradle.kts; then
  warn "versionCode is 1 (override via CI_RUN_NUMBER / release bump before Play upload)"
fi

# ---------------------------------------------------------------- 3
section "3. Secrets & .gitignore Coverage"
SECRETS=$(SRC_FILES | grep -v 'google-services\.json' | xargs grep -nEI \
  'AIza[0-9A-Za-z_-]{30,}|sk-(proj|ant)-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY|xox[baprs]-[A-Za-z0-9-]{10,}|gsk_[A-Za-z0-9]{20,}' \
  2>/dev/null | grep -vE '(Test\.kt|\.test\.mjs|/test/)' || true)
if [ -n "$SECRETS" ]; then
  fail "possible secrets detected outside google-services.json:"
  echo "$SECRETS" | sed 's/^/        /' | cut -c1-160
else
  ok "no secrets detected outside google-services.json"
fi

for ig in '\.env' '\*\.jks' 'keystore\.properties' 'debug\.keystore'; do
  if grep -qE "^\s*$ig\s*$" .gitignore 2>/dev/null; then
    :
  else
    fail ".gitignore missing entry matching $ig"
  fi
done
ok ".gitignore covers .env, *.jks, keystore.properties, debug.keystore"

# ---------------------------------------------------------------- 4
section "4. Dead Models & Legacy Unsafe Aliases"
if [ ! -f config/dead-models.txt ]; then
  fail "config/dead-models.txt missing"
else
  QUOTED_DEAD_REGEX=$(grep -vE '^\s*(#|$)' config/dead-models.txt | sed -E 's/^\^(.*)\$$/"\1"/; s/^\^([^"].*)/"\1/' | tr '\n' '|' | sed 's/|$//')
  if [ -n "$QUOTED_DEAD_REGEX" ]; then
    EXACT_DEAD=$(MAIN_KT | xargs grep -nE "$QUOTED_DEAD_REGEX" 2>/dev/null || true)
    if [ -n "$EXACT_DEAD" ]; then
      fail "dead/retiring model IDs hardcoded in app/src/main:"
      echo "$EXACT_DEAD" | sed 's/^/        /' | cut -c1-160
    else
      ok "no dead/retiring model IDs active in app/src/main (verified against config/dead-models.txt)"
    fi
  fi
fi

UNSAFE_ALIASES=$(MAIN_KT | grep -v 'AyraPreferences\.kt' | xargs grep -nE '"(qwen_uncensored|godmode)"' 2>/dev/null || true)
if [ -n "$UNSAFE_ALIASES" ]; then
  fail "legacy unsafe aliases still referenced outside AyraPreferences migration:"
  echo "$UNSAFE_ALIASES" | sed 's/^/        /'
else
  ok "no legacy qwen_uncensored/godmode aliases outside preference migration"
fi

# ---------------------------------------------------------------- 5
section "5. Credential Transport (No API Keys in URLs)"
KEY_IN_URL=$(MAIN_KT | xargs grep -nE '([?&]|\{?[A-Za-z_]+\}?)key=(\$|\{)' 2>/dev/null || true)
if [ -n "$KEY_IN_URL" ]; then
  fail "API key placed in URL query parameter:"
  echo "$KEY_IN_URL" | sed 's/^/        /'
else
  ok "no API keys appended to URL query parameters"
fi

# ---------------------------------------------------------------- 6
section "6. Swallowed-Exception Ratchet"
CURRENT_SWALLOWED=$(MAIN_KT | xargs grep -rnE 'catch\s*\(\s*_\s*:\s*(Exception|Throwable)' 2>/dev/null | wc -l | tr -d ' ')
if [ -f .ci/swallowed-baseline.txt ]; then
  BASELINE_SWALLOWED=$(cat .ci/swallowed-baseline.txt | tr -d '[:space:]')
  if [ "$CURRENT_SWALLOWED" -gt "$BASELINE_SWALLOWED" ]; then
    fail "swallowed catch(_) count increased ($CURRENT_SWALLOWED > baseline $BASELINE_SWALLOWED)"
  else
    ok "swallowed catch(_) count = $CURRENT_SWALLOWED (baseline <= $BASELINE_SWALLOWED)"
  fi
else
  fail ".ci/swallowed-baseline.txt missing"
fi

# ---------------------------------------------------------------- 7
section "7. Database Safety & Vault Recovery"
if grep -qE 'exportSchema\s*=\s*true' app/src/main/java/com/ayra/ai/app/data/ChatDatabase.kt; then
  ok "ChatDatabase exportSchema = true"
else
  fail "ChatDatabase exportSchema is false"
fi

if grep -qE 'enum class VaultState' app/src/main/java/com/ayra/ai/app/security/VaultDatabaseKeyManager.kt 2>/dev/null; then
  ok "VaultState key-loss recovery state present"
else
  fail "VaultState key-loss recovery missing in VaultDatabaseKeyManager.kt"
fi

# ---------------------------------------------------------------- 8
section "8. Documentation Drift & Generated Facts"
if [ -x scripts/gen-facts.sh ] && bash scripts/gen-facts.sh --check >/dev/null 2>&1; then
  ok "docs/FACTS.md matches codebase"
else
  fail "docs/FACTS.md is missing or out of date (run bash scripts/gen-facts.sh)"
fi

STALE_ROOM_DOCS=$(grep -rnE 'Room\s*(schema\s*)?v9\b' README.md SECURITY_ARCHITECTURE.md SECURITY_AUDIT_REPORT.md DEPLOYMENT_AND_RISK_REPORT.md 2>/dev/null || true)
if [ -n "$STALE_ROOM_DOCS" ]; then
  fail "docs claim Room v9 while ChatDatabase is higher:"
  echo "$STALE_ROOM_DOCS" | sed 's/^/        /' | cut -c1-160
else
  ok "no stale Room v9 claims in primary docs"
fi

# ---------------------------------------------------------------- 9
section "9. Manifest & Bundled Asset Hygiene"
if grep -q 'android:allowBackup="false"' app/src/main/AndroidManifest.xml && \
   grep -q 'android:usesCleartextTraffic="false"' app/src/main/AndroidManifest.xml; then
  ok "AndroidManifest enforces allowBackup=false and usesCleartextTraffic=false"
else
  fail "AndroidManifest missing allowBackup=false or usesCleartextTraffic=false"
fi

STRAY_ASSET_SCRIPTS=$(find app/src/main/assets -type f \( -name '*.cjs' -o -name '*.sh' -o -name '*.ts' -o -name '*.js' \) 2>/dev/null || true)
if [ -n "$STRAY_ASSET_SCRIPTS" ]; then
  fail "unexpected executable/node/shell scripts in app/src/main/assets:"
  echo "$STRAY_ASSET_SCRIPTS" | sed 's/^/        /'
else
  ok "zero stray .cjs/.sh/.ts/.js scripts in app/src/main/assets"
fi

# ---------------------------------------------------------------- 10
section "10. Bounded HTTP Response Reads & Redirect SSRF Guard (P12)"
RAW_BODY_READS=$(MAIN_KT | grep -v 'NetworkSecurityHelper\.kt' | xargs grep -nE '\.body\?\.(string|bytes)\(' 2>/dev/null || true)
if [ -n "$RAW_BODY_READS" ]; then
  fail "unbounded resp.body?.string()/bytes() found outside NetworkSecurityHelper.kt:"
  echo "$RAW_BODY_READS" | sed 's/^/        /' | cut -c1-170
else
  ok "zero unbounded .body?.string()/.bytes() calls outside NetworkSecurityHelper.kt"
fi

# ---------------------------------------------------------------- 11
section "11. Network Security Config & Cleartext Parity (P11)"
NSC="app/src/main/res/xml/network_security_config.xml"
if grep -q 'cleartextTrafficPermitted="false"' "$NSC" 2>/dev/null && \
   grep -q 'localhost' "$NSC" 2>/dev/null && \
   grep -q '127.0.0.1' "$NSC" 2>/dev/null; then
  ok "network_security_config.xml enforces HTTPS default with loopback-only cleartext"
else
  fail "network_security_config.xml missing cleartextTrafficPermitted=\"false\" base-config or loopback domains"
fi

# ---------------------------------------------------------------- 12
section "12. Room Schema Export Verification (P13)"
ROOM_VER=$(grep -oE 'version\s*=\s*[0-9]+' app/src/main/java/com/ayra/ai/app/data/ChatDatabase.kt | head -1 | sed 's/.*= *//')
SCHEMA_FILE="app/schemas/com.ayra.ai.app.data.ChatDatabase/${ROOM_VER}.json"
if [ -n "$ROOM_VER" ] && [ -f "$SCHEMA_FILE" ]; then
  ok "committed Room schema JSON exists ($SCHEMA_FILE)"
else
  fail "missing committed Room schema JSON ($SCHEMA_FILE)"
fi

# ---------------------------------------------------------------- 13
section "13. Prompt Boundary, WebView CSP & Keystore Consolidation (P9, P10, P14)"
if grep -q 'wrappedUntrustedContent' app/src/main/java/com/ayra/ai/app/security/AiToolSecurityGuard.kt 2>/dev/null && \
   grep -q 'SPOOFED_TAG_REGEX' app/src/main/java/com/ayra/ai/app/security/AiToolSecurityGuard.kt 2>/dev/null && \
   grep -q 'ZERO_WIDTH_OR_BIDI_REGEX' app/src/main/java/com/ayra/ai/app/security/AiToolSecurityGuard.kt 2>/dev/null; then
  ok "AiToolSecurityGuard enforces unconditional boundary wrapping + NFKC/zero-width normalization"
else
  fail "AiToolSecurityGuard missing unconditional wrapping or zero-width normalization"
fi

if grep -q 'taintedByUntrustedData' app/src/main/java/com/ayra/ai/app/ai/agent/AgentCore.kt 2>/dev/null; then
  ok "AgentCore enforces untrusted-data taint and exfiltration guard"
else
  fail "AgentCore missing taintedByUntrustedData exfiltration guard"
fi

if grep -q 'allowFileAccess = false' app/src/main/java/com/ayra/ai/app/ui/components/ThreeDModelCard.kt 2>/dev/null && \
   grep -q 'blockNetworkLoads = true' app/src/main/java/com/ayra/ai/app/ui/components/ThreeDModelCard.kt 2>/dev/null && \
   grep -q 'Content-Security-Policy' app/src/main/java/com/ayra/ai/app/ui/components/ThreeDModelCard.kt 2>/dev/null; then
  ok "ThreeDModelCard WebView hardened with CSP and network/file lockdown"
else
  fail "ThreeDModelCard WebView missing CSP or file/network lockdown settings"
fi

DUAL_WRITES=$(grep -nE 'EncryptedPreferences\(context\)\.putString' app/src/main/java/com/ayra/ai/app/data/AyraPreferences.kt 2>/dev/null || true)
if [ -n "$DUAL_WRITES" ]; then
  fail "AyraPreferences still dual-writes API keys to EncryptedPreferences:"
  echo "$DUAL_WRITES" | sed 's/^/        /'
else
  ok "AyraPreferences consolidates API key writes exclusively to SecureKeyStore"
fi

# ---------------------------------------------------------------- 14
section "14. Automation & Reminder Tool Confirmation Guard (H3)"
TOOL_REG="app/src/main/java/com/ayra/ai/app/ai/agent/AgentToolRegistry.kt"
if grep -q 'scheduleTaskFromPrompt' "$TOOL_REG" 2>/dev/null; then
  fail "AgentToolRegistry.kt still calls unguarded scheduleTaskFromPrompt (H3 bypass risk)"
elif grep -q 'scheduleReminderOnly' "$TOOL_REG" 2>/dev/null && \
     grep -q 'taintedByUntrustedData && tool.name == "reminder_schedule"' app/src/main/java/com/ayra/ai/app/ai/agent/AgentCore.kt 2>/dev/null; then
  ok "reminder_schedule restricted to scheduleReminderOnly and gated by taint guard in AgentCore"
else
  fail "H3 reminder_schedule REMINDER-only restriction or AgentCore taint gate missing"
fi

echo
if [ "$FAILS" -gt 0 ]; then
  echo "PREFLIGHT: $FAILS FAIL(s), $WARNS WARN(s)."
  exit 1
else
  echo "PREFLIGHT: clean. ($WARNS WARN(s))"
  exit 0
fi
