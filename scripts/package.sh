#!/usr/bin/env bash
# ============================================
# Rook macOS/Linux packaging script
# Output: dist/rook/ (app-image with bundled trimmed JRE)
#         dist/rook-0.1.0-SNAPSHOT-<mac|linux>.tar.gz
# ============================================
set -euo pipefail

# Change to project root (script is under scripts/)
cd "$(dirname "$0")/.."

# 1. Build fat jar
./mvnw clean package

# 2. Clean old artifacts
rm -rf dist
mkdir -p dist/input

# 3. Prepare a clean input dir with only the fat jar.
#    jpackage copies the whole --input dir into app/, so point it at a
#    dir that contains only the fat jar (not Maven's target/ intermediates).
cp target/rook-0.1.0-SNAPSHOT.jar dist/input/

# 4. Generate trimmed JRE with jlink.
#    Module list = jdeps result (java.base,java.desktop,java.management,
#    java.net.http,java.sql) + jdk.crypto.ec (HTTPS, loaded via ServiceLoader).
#    To refresh after dependency changes, run:
#      jdeps --print-module-deps --ignore-missing-deps target/rook-0.1.0-SNAPSHOT.jar
jlink --add-modules java.base,java.desktop,java.management,java.net.http,java.sql,jdk.crypto.ec \
      --strip-debug --no-header-files --no-man-pages \
      --output dist/jre

# 5. Generate app-image using the trimmed runtime
jpackage --type app-image --name rook \
         --input dist/input \
         --main-jar rook-0.1.0-SNAPSHOT.jar \
         --main-class com.rook.cli.Main \
         --dest dist \
         --runtime-image dist/jre

# 6. Clean intermediate dirs (input + jre are embedded into the app-image)
rm -rf dist/input dist/jre

# 6a. Include config.yaml (API key uses env var placeholder) so the
#     app can find it next to the rook launcher at launch.
cp config.yaml dist/rook/

# 6b. Include project config dir (.rook/) so MCP / hooks / rules resolve
#     relative to the app working directory.
if [ -d .rook ]; then cp -r .rook dist/rook/; fi

# 7. Archive to tar.gz (detect platform: Darwin=mac, otherwise linux)
OS="$(uname -s)"
case "$OS" in
    Darwin) SUFFIX="mac" ;;
    *)      SUFFIX="linux" ;;
esac
tar -czf "dist/rook-0.1.0-SNAPSHOT-${SUFFIX}.tar.gz" -C dist rook

echo
echo "Packaging complete: dist/rook-0.1.0-SNAPSHOT-${SUFFIX}.tar.gz"
