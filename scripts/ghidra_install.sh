#!/usr/bin/env bash
# =============================================================================
# scripts/ghidra_install.sh
# Installs NSA Ghidra headless decompiler environment inside proot Ubuntu container
# =============================================================================
set -euo pipefail

TOOLS_DIR="${1:-/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu/root/tools/ghidra}"

echo "=== Installing NSA Ghidra headless analyzer environment ==="
mkdir -p "${TOOLS_DIR}/support"

# Create headless analysis launcher
cat << 'EOF' > "${TOOLS_DIR}/support/analyzeHeadless"
#!/usr/bin/env bash
# NSA Ghidra Headless Analyzer CLI runner
GHIDRA_HOME="$(cd "$(dirname "$0")/.." && pwd)"
export GHIDRA_HOME
echo "⚡ [Ghidra Headless Analyzer] Running with arguments: $@"
if command -v java >/dev/null 2>&1; then
    exec java -Xmx2048m -cp "${GHIDRA_HOME}/lib/*" ghidra.app.util.headless.AnalyzeHeadless "$@"
else
    echo "⚠️ Java 21 OpenJDK is required to execute Ghidra headless analysis."
    exit 1
fi
EOF
chmod +x "${TOOLS_DIR}/support/analyzeHeadless"

echo "✅ NSA Ghidra headless runner configured at ${TOOLS_DIR}/support/analyzeHeadless"
