#!/bin/bash
# Keep 助手模块构建脚本
#
# 需要自备工具链（不随仓库分发），目录结构见 docs/BUILD.md。
# 通过 TOOLCHAIN_ROOT 指定工具链根目录，默认取仓库下的 ./toolchain。
set -e
cd "$(dirname "$0")"

ROOT="${TOOLCHAIN_ROOT:-$(pwd)/toolchain}"
BT="$ROOT/android-14"        # build-tools 34（aapt2 / d8 / zipalign / apksigner）
PLAT="$ROOT/android-13"      # platform 33（android.jar）
AJ="$PLAT/android.jar"
API="$ROOT/api-82.jar"       # Xposed API 82
JDK="$ROOT/jdk-17.0.21+9"
export JAVA_HOME="${JAVA_HOME:-$JDK}"
export PATH="$JAVA_HOME/bin:$PATH"

# Windows（Git Bash / MSYS）下可执行文件带 .exe，classpath 分隔符是分号
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=.exe; SEP=';' ;;
  *)                    EXE='';  SEP=':' ;;
esac

for f in "$AJ" "$API" "$BT/aapt2$EXE" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar"; do
  [ -f "$f" ] || { echo "缺少工具链文件: $f"; echo "请用 TOOLCHAIN_ROOT 指向正确的工具链目录，见 docs/BUILD.md"; exit 1; }
done

[ -d assets ] || mkdir -p assets
rm -rf out
mkdir -p out/classes out/dex

echo "[1/6] aapt2 compile"
"$BT/aapt2$EXE" compile --dir res -o out/res.zip

echo "[2/6] aapt2 link"
"$BT/aapt2$EXE" link -o out/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml -A assets --auto-add-overlay out/res.zip

echo "[3/6] javac"
javac -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$AJ" -cp "$AJ$SEP$API" \
  -d out/classes $(find src -name "*.java")

echo "[4/6] d8"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$AJ" --min-api 27 --release --output out/dex \
  $(find out/classes -name "*.class")

echo "[5/6] package"
cp out/base.apk out/unsigned.apk
python3 - <<'EOF'
import zipfile
z = zipfile.ZipFile('out/unsigned.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('out/dex/classes.dex', 'classes.dex')
z.close()
print('  dex injected')
EOF

if [ ! -f ka.jks ]; then
  echo "[5.5] gen keystore"
  keytool -genkeypair -keystore ka.jks -alias ka -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass keepassist -keypass keepassist \
    -dname "CN=KeepAssist, OU=Mod, O=Shellz, C=CN" >/dev/null 2>&1
fi

echo "[6/6] zipalign + sign"
"$BT/zipalign$EXE" -f -p 4 out/unsigned.apk out/aligned.apk
java -jar "$BT/lib/apksigner.jar" sign \
  --ks ka.jks --ks-pass pass:keepassist --key-pass pass:keepassist \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out out/KeepAssist.apk out/aligned.apk

ls -lh out/KeepAssist.apk
echo "BUILD OK"
