@echo off
REM ============================================
REM Rook Windows packaging script
REM Output: dist/rook/ (app-image with bundled trimmed JRE)
REM         dist/rook-0.1.0-SNAPSHOT-win.zip
REM ============================================
setlocal

REM Change to project root (script is under scripts/)
cd /d "%~dp0.."

REM 1. Build fat jar
call .\mvnw.cmd clean package
if errorlevel 1 (
    echo [ERROR] mvnw.cmd clean package failed
    exit /b 1
)

REM 2. Clean old artifacts
if exist dist rmdir /s /q dist
mkdir dist

REM 3. Prepare a clean input dir with only the fat jar.
REM    jpackage copies the whole --input dir into app/, so point it at a
REM    dir that contains only the fat jar (not Maven's target/ intermediates).
mkdir dist\input
copy /y target\rook-0.1.0-SNAPSHOT.jar dist\input\
if errorlevel 1 (
    echo [ERROR] copy fat jar failed
    exit /b 1
)

REM 4. Generate trimmed JRE with jlink.
REM    Module list = jdeps result (java.base,java.desktop,java.management,
REM    java.net.http,java.sql) + jdk.crypto.ec (HTTPS, loaded via ServiceLoader).
REM    To refresh after dependency changes, run:
REM      jdeps --print-module-deps --ignore-missing-deps target/rook-0.1.0-SNAPSHOT.jar
jlink --add-modules java.base,java.desktop,java.management,java.net.http,java.sql,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages --output dist\jre
if errorlevel 1 (
    echo [ERROR] jlink failed
    exit /b 1
)

REM 5. Generate app-image using the trimmed runtime
jpackage --type app-image --name rook --input dist\input --main-jar rook-0.1.0-SNAPSHOT.jar --main-class com.rook.cli.Main --dest dist --win-console --runtime-image dist\jre
if errorlevel 1 (
    echo [ERROR] jpackage failed
    exit /b 1
)

REM 6. Clean intermediate dirs (input + jre are embedded into the app-image)
rmdir /s /q dist\input
rmdir /s /q dist\jre

REM 7. Archive to zip
tar -a -c -f dist\rook-0.1.0-SNAPSHOT-win.zip -C dist rook
if errorlevel 1 (
    echo [ERROR] archive failed
    exit /b 1
)

echo.
echo Packaging complete: dist\rook-0.1.0-SNAPSHOT-win.zip
endlocal
