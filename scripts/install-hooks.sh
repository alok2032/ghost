#!/usr/bin/env bash
# Installs git pre-commit hook running scripts/preflight.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -d .git ]; then
  echo "No .git directory found in $(pwd); skipping hook installation until git init."
  exit 0
fi

mkdir -p .git/hooks
cat > .git/hooks/pre-commit <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
bash scripts/preflight.sh
EOF
chmod +x .git/hooks/pre-commit
echo "Installed pre-commit hook at .git/hooks/pre-commit"
