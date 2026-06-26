#!/usr/bin/env bash
# Local APK rebuild for Professor VPN v4.0 (sandbox cannot run full Gradle).
# Recompiles all app sources with kotlinc + javac, re-DEXes with d8, patches the
# classes*.dex inside the existing universal APK, then zipaligns + signs.
set -euo pipefail

export JAVA_HOME=/home/user/build-tools/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH
SDK=/home/user/build-tools/android-sdk
BT=$SDK/build-tools/34.0.0
ANDROID_JAR=$SDK/platforms/android-34/android.jar
APP=/home/user/webapp/app
WORK=/home/user/apkbuild
rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/dex"

# ---- compiler jars ----
KC=/home/user/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.24/78dab849090e6c5e2eadb6e59a11cdc28fb67a08/kotlin-compiler-embeddable-1.9.24.jar
TROVE=/home/user/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/lib/trove4j-1.0.20200330.jar
ANNO=/home/user/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar
SCRIPT=/home/user/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-script-runtime/1.9.24/96771497da90fbc5af1c90fce148db2595a62502/kotlin-script-runtime-1.9.24.jar
KSTD=/home/user/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/lib/kotlin-stdlib-1.9.22.jar
KREF=/home/user/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/lib/kotlin-reflect-1.9.22.jar
KC_CP="$KC:$TROVE:$ANNO:$SCRIPT:$KSTD:$KREF"

# ---- app compile classpath (android.jar + R.jar + deps + libv2ray) ----
R_JAR="$APP/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/release/processReleaseResources/R.jar"
CP="$ANDROID_JAR:$R_JAR:/tmp/libv2ray.jar"
while IFS= read -r j; do CP="$CP:$j"; done < /tmp/cp.txt

# ---- sources ----
KT_SRC=$(find "$APP/src/main/java" -name "*.kt")
GEN_JAVA=$(find "$APP/build/generated/data_binding_base_class_source_out/release/out" -name "*.java")
BUILDCONFIG="$APP/build/generated/source/buildConfig/release/com/neonvpn/app/BuildConfig.java"

echo "==> [1/5] kotlinc compiling $(echo "$KT_SRC" | wc -l) Kotlin files ..."
java -Xmx820m -XX:+UseSerialGC -cp "$KC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -jvm-target 17 \
  -classpath "$CP" \
  -d "$WORK/classes" \
  $KT_SRC $GEN_JAVA "$BUILDCONFIG" 2>&1 | grep -vE "^warning:|is deprecated|will become an error|Recompile with|parameter is never used|never used|is never used" | tail -40 || true

echo "==> [2/5] javac compiling generated Java (R/BuildConfig/databinding) ..."
# kotlinc already compiled the .java passed above; ensure databinding+R compiled too.
mkdir -p "$WORK/jclasses"
javac -source 17 -target 17 -nowarn -proc:none \
  -cp "$CP:$WORK/classes" \
  -d "$WORK/jclasses" \
  $GEN_JAVA "$BUILDCONFIG" 2>&1 | tail -20 || true
cp -rn "$WORK/jclasses/." "$WORK/classes/" 2>/dev/null || true

CLASS_COUNT=$(find "$WORK/classes" -name "*.class" | wc -l)
echo "    compiled $CLASS_COUNT classes"
if [ "$CLASS_COUNT" -lt 100 ]; then echo "FATAL: too few classes compiled"; exit 1; fi

echo "==> [3/5] d8 dexing app classes (+ deps as needed) ..."
# Collect runtime jars to merge so referenced library classes are present.
DEPJARS=$(while IFS= read -r j; do echo "$j"; done < /tmp/cp.txt | tr '\n' ' ')
find "$WORK/classes" -name "*.class" > "$WORK/classlist.txt"
"$BT/d8" --release --min-api 24 \
  --lib "$ANDROID_JAR" \
  --output "$WORK/dex" \
  @"$WORK/classlist.txt" /tmp/libv2ray.jar $DEPJARS 2>&1 | tail -15

echo "    dex produced:"; ls -la "$WORK/dex"/*.dex

echo "==> [4/5] patching APK with new dex ..."
SRC_APK=/home/user/webapp/build/ProfessorVPN-v4.0-universal.apk
cp "$SRC_APK" "$WORK/base.apk"
cd "$WORK"
# remove old dex, add new
zip -d base.apk "classes*.dex" >/dev/null 2>&1 || true
cd dex && zip -0 "$WORK/base.apk" classes*.dex >/dev/null && cd ..

echo "==> [5/5] zipalign + sign ..."
"$BT/zipalign" -f -p 4 base.apk aligned.apk
KS=/home/user/webapp/app/neonvpn.keystore
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:neonvpn123 --key-pass pass:neonvpn123 --ks-key-alias neonvpn \
  --out "$WORK/ProfessorVPN-v4.0-universal.apk" aligned.apk 2>&1 | tail -5
"$BT/apksigner" verify --print-certs "$WORK/ProfessorVPN-v4.0-universal.apk" 2>&1 | head -3

echo "==> DONE: $WORK/ProfessorVPN-v4.0-universal.apk"
ls -la "$WORK/ProfessorVPN-v4.0-universal.apk"
