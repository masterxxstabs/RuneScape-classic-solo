#!/usr/bin/env sh
set -e
cd "$(dirname "$0")"

echo "Preparing the launcher. The first run downloads build tools and can take a few minutes..."
./gradlew --console=plain -q :tools:launcher:shadowJar

# Run a copy so an update can rebuild the launcher while the old one is still closing.
mkdir -p .launcher
cp tools/launcher/build/libs/launcher.jar .launcher/launcher.jar.tmp
mv -f .launcher/launcher.jar.tmp .launcher/launcher.jar

if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
exec "$JAVA" -jar .launcher/launcher.jar "$@"
