#!/bin/bash
# Maven Wrapper script
MAVEN_PROJECTBASEDIR="$(cd "$(dirname "$0")" && pwd)"
exec java -Dmaven.multiModuleProjectDirectory="$MAVEN_PROJECTBASEDIR" \
          -classpath "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar" \
          org.apache.maven.wrapper.MavenWrapperMain "$@"
