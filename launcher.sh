#!/usr/bin/env sh
set -e
cd "$(dirname "$0")"

echo "Preparing the launcher. The first run downloads build tools and can take a few minutes..."
./gradlew --console=plain -q :tools:launcher:shadowJar

if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
exec "$JAVA" -jar tools/launcher/build/libs/launcher.jar "$@"
