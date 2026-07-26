#!/bin/sh
# Gradle wrapper script.
# 优先使用 gradle/wrapper/gradle-wrapper.jar；如果不存在则回退到系统 gradle。

DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$DIR"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec "$JAVACMD" \
        -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
        org.gradle.wrapper.GradleWrapperMain "$@"
else
    echo "gradle-wrapper.jar not found; falling back to system gradle."
    exec gradle "$@"
fi
