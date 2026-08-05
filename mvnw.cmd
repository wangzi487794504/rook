@REM ----------------------------------------------------------------------------
@REM Maven Wrapper startup script for Windows NT
@REM ----------------------------------------------------------------------------

@if "%DEBUG%"=="" @echo off
@REM Find project base dir
set "MAVEN_PROJECTBASEDIR=%~dp0"

set "MAVEN_OPTS=-Xmx512m -Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%"

if not "%MAVEN_PROJECTBASEDIR%"=="" (
    set "MAVEN_BASEDIR=%MAVEN_PROJECTBASEDIR%"
)

@REM Find maven-wrapper.jar
set "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"

if not exist "%WRAPPER_JAR%" (
    echo Maven Wrapper JAR not found. Downloading...
    powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar' -OutFile '%WRAPPER_JAR%'}"
)

@REM Start MAVEN
set "MAVEN_CMD_LINE_ARGS=%*"
"%JAVA_HOME%\bin\java.exe" %MAVEN_OPTS% -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %MAVEN_CMD_LINE_ARGS%
