#!/bin/sh
#
# Runs boson-cli from a directory holding bin/ and lib/: a Boson distribution, or target/dist.
#
# Java is the bundled JRE when there is one, then $JAVA_HOME, then java on the PATH. JAVA_OPTS is
# passed to it. Without a bundled JRE, Java 17 or later is required and is checked for.

# Resolve symbolic links: packages link this script into /usr/bin.
PRG="$0"
while [ -h "$PRG" ]; do
  link=$(ls -ld "$PRG" | sed 's/.*-> //')
  case "$link" in
    /*) PRG="$link" ;;
    *) PRG="$(dirname "$PRG")/$link" ;;
  esac
done
BASEDIR=$(cd "$(dirname "$PRG")/.." > /dev/null && pwd)

if [ -x "$BASEDIR/jre/bin/java" ]; then
  # The runtime bundled beside us, built for this package: its version needs no checking.
  JAVA="$BASEDIR/jre/bin/java"
else
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
  else
    JAVA=java
  fi

  # No runtime is bundled, so this is the portable package and the Java we just found is the
  # user's. Check it here: a too-old JVM otherwise fails with UnsupportedClassVersionError, which
  # names a bytecode level rather than the thing to fix. The cost of starting a JVM to ask is paid
  # only by this package - the ones with a bundled runtime take the branch above.
  #
  # "java -version" prints openjdk version "17.0.20.1"; Java 8 prints "1.8.0_452", whose leading 1
  # correctly compares as older than 17. The version line is searched for rather than assumed to
  # be the first: with JAVA_TOOL_OPTIONS or _JAVA_OPTIONS set, the JVM prints "Picked up ..." ahead
  # of it.
  java_major=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
  if [ -z "$java_major" ]; then
    echo "Error: no Java runtime found." >&2
    echo "Hint: install Java 17 or later, or point JAVA_HOME at one." >&2
    exit 1
  fi
  if [ "$java_major" -lt 17 ]; then
    echo "Error: Boson needs Java 17 or later, but $JAVA is Java $java_major." >&2
    echo "Hint: install Java 17 or later, or point JAVA_HOME at one." >&2
    exit 1
  fi
fi

# The serial collector: a command runs briefly and needs no parallel GC threads to start.
exec "$JAVA" -XX:+UseSerialGC $JAVA_OPTS -cp "$BASEDIR/lib/*" io.bosonnetwork.cli.BosonCli "$@"
