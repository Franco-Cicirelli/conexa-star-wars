#!/usr/bin/env bash
# Load with: source scripts/java-env.sh
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
elif /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home)"
    export PATH="$JAVA_HOME/bin:$PATH"
elif [ -x '/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/javac' ]; then
    export JAVA_HOME='/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home'
    export PATH="$JAVA_HOME/bin:$PATH"
else
    echo 'No JDK was found. Set JAVA_HOME to a JDK version 21 or later.' >&2
    return 1
fi
