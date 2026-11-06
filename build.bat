@echo off
setlocal enabledelayedexpansion

REM ============================================
REM  WhitelistBypass - build APK without Gradle
REM  Works like SlideDungeon build.bat
REM  SDK: C:\SDK  (build-tools 36.0.0)
REM  Override ABIS:   set ABIS=arm64-v8a armeabi-v7a x86_64 x86
REM  Test stages:     set TAINT=1  (1=UI only, 2=+DPI, 3=+VPN/Xray, 4=full/default)
REM  No Cyrillic anywhere in this file by design (cmd parses .bat in OEM codepage).
REM ============================================

if not defined TAINT set TAINT=4
if "%TAINT%"=="1" goto taint_ok
if "%TAINT%"=="2" goto taint_ok
if "%TAINT%"=="3" goto taint_ok
if "%TAINT%"=="4" goto taint_ok
echo [ERROR] TAINT must be 1..4 (got %TAINT%)
exit /b 1
:taint_ok

set SDK=C:\SDK
set BUILD_TOOLS=%SDK%\build-tools\36.0.0
set APP_NAME=WhitelistBypass
set BUILD_DIR=build
if "%TAINT%"=="4" (set OUT_APK=%APP_NAME%.apk) else (set OUT_APK=%APP_NAME%-TEST%TAINT%.apk)
set MIN_SDK=24
if "%ABIS%"=="" set ABIS=armeabi-v7a arm64-v8a
set DEPS=deps

echo TAINT=%TAINT% (4=full build; 1..3=test stage)

REM --- Auto-detect Android platform ---
set PLATFORM_DIR=
for /d %%d in ("%SDK%\platforms\*") do (
    if exist "%%d\android.jar" if not defined PLATFORM_DIR set PLATFORM_DIR=%%d
)
if not defined PLATFORM_DIR (
    echo [ERROR] No Android platform found in %SDK%\platforms
    exit /b 1
)
set ANDROID_JAR=%PLATFORM_DIR%\android.jar
for /f "tokens=2 delims=-" %%v in ("%PLATFORM_DIR%") do set TARGET_SDK=%%v
echo Platform: %PLATFORM_DIR%  target=%TARGET_SDK%  min=%MIN_SDK%
echo SDK tools: %BUILD_TOOLS%
echo ABIS: %ABIS%

REM --- Tool paths ---
set AAPT2=%BUILD_TOOLS%\aapt2.exe
set D8=%BUILD_TOOLS%\d8.bat
set ZIPALIGN=%BUILD_TOOLS%\zipalign.exe
set APKSIGNER=%BUILD_TOOLS%\apksigner.bat
set TAR=tar

REM --- Sanity checks ---
if not exist "%AAPT2%"      (echo [ERROR] aapt2 not found: %AAPT2% & exit /b 1)
if not exist "%ZIPALIGN%"   (echo [ERROR] zipalign not found: %ZIPALIGN% & exit /b 1)
if not exist "%APKSIGNER%"  (echo [ERROR] apksigner not found: %APKSIGNER% & exit /b 1)
where javac   >nul 2>nul || (echo [ERROR] javac not in PATH & exit /b 1)
where jar     >nul 2>nul || (echo [ERROR] jar not in PATH & exit /b 1)
where keytool >nul 2>nul || (echo [ERROR] keytool not in PATH & exit /b 1)
where tar     >nul 2>nul || (echo [ERROR] tar not in PATH - need Windows 10 1803+ & exit /b 1)
where curl    >nul 2>nul || (echo [WARN] curl not in PATH - deps must exist in %DEPS% & exit /b 1)

REM --- Deps (libv2ray.aar, tor-android.aar, ...) ---
if not exist "%DEPS%\libv2ray.aar" (
    echo [deps] libv2ray.aar not found, fetching...
    call tools\fetch_deps.bat %DEPS% || (echo [ERROR] fetch_deps failed & exit /b 1)
)
if not exist "%DEPS%\tor-android.aar" (
    echo [deps] tor-android.aar not found, fetching...
    call tools\fetch_deps.bat %DEPS% || (echo [ERROR] fetch_deps failed & exit /b 1)
)

REM --- Clean ---
if exist %BUILD_DIR% rmdir /s /q %BUILD_DIR%
mkdir %BUILD_DIR%\compiled_res
mkdir %BUILD_DIR%\classes
mkdir %BUILD_DIR%\dex
mkdir %BUILD_DIR%\deps-aar\xray
mkdir %BUILD_DIR%\deps-aar\tor
mkdir %BUILD_DIR%\deps-aar\lbc
mkdir %BUILD_DIR%\deps-classes\xray
mkdir %BUILD_DIR%\deps-classes\tor
mkdir %BUILD_DIR%\deps-classes\lbc
mkdir %BUILD_DIR%\gen

REM --- Unpack AARs (classes.jar + jni/*.so) ---
for %%J in ("%DEPS%\libv2ray.aar|xray" "%DEPS%\tor-android.aar|tor" "%DEPS%\localbroadcastmanager-1.1.0.aar|lbc") do (
    for /f "tokens=1,2 delims=|" %%a in (%%J) do (
        pushd %BUILD_DIR%\deps-aar\%%b
        %TAR% -xf "%%a" || (popd & echo [ERROR] unpack %%a failed & exit /b 1)
        popd
        pushd %BUILD_DIR%\deps-classes\%%b
        %TAR% -xf "%BUILD_DIR%\deps-aar\%%b\classes.jar" || (popd & echo [ERROR] classes.jar unpack failed & exit /b 1)
        popd
    )
)

REM --- [0/7] TAINT: filtered manifest + source overlay ---
set LINK_MANIFEST=AndroidManifest.xml
if "%TAINT%"=="4" goto manifest_done
echo [0/7] test stage %TAINT% - filtering manifest and TestFlags
powershell -NoProfile -ExecutionPolicy Bypass -File tools\taint_manifest.ps1 -Level %TAINT% -In AndroidManifest.xml -Out %BUILD_DIR%\AndroidManifest.test.xml || (echo [ERROR] taint_manifest.ps1 failed & exit /b 1)
set LINK_MANIFEST=%BUILD_DIR%\AndroidManifest.test.xml
xcopy /E /I /Q /H src %BUILD_DIR%\src-taint >nul
powershell -NoProfile -Command "(Get-Content '%BUILD_DIR%\src-taint\ru\whitelist\bypass\TestFlags.java' -Raw) -replace 'LEVEL = \d+;', 'LEVEL = %TAINT%;' | Set-Content '%BUILD_DIR%\src-taint\ru\whitelist\bypass\TestFlags.java' -Encoding UTF8"
set SRC_DIR=%BUILD_DIR%\src-taint
goto manifest_ok
:manifest_done
set SRC_DIR=src
:manifest_ok

echo [1/7] aapt2 compile
"%AAPT2%" compile --dir res -o %BUILD_DIR%\compiled_res
if errorlevel 1 (echo [ERROR] aapt2 compile failed & exit /b 1)

echo   packing .flat to resources.zip
pushd "%BUILD_DIR%\compiled_res"
if exist resources.zip del /q resources.zip
tar -a -c -f resources.zip *.flat
if errorlevel 1 (popd & echo [ERROR] failed to pack resources.zip & exit /b 1)
popd

echo [2/7] aapt2 link
"%AAPT2%" link -o %BUILD_DIR%\base.apk -I "%ANDROID_JAR%" --manifest "%LINK_MANIFEST%" --java %BUILD_DIR% -A assets --min-sdk-version %MIN_SDK% --target-sdk-version %TARGET_SDK% %BUILD_DIR%\compiled_res\resources.zip
if errorlevel 1 (echo [ERROR] aapt2 link failed & exit /b 1)
if not exist %BUILD_DIR%\base.apk (echo [ERROR] aapt2 link produced no base.apk & exit /b 1)

REM --- Locate R.java generated by aapt2 ---
set R_JAVA=
for /r "%BUILD_DIR%" %%f in (R.java) do set R_JAVA=%%f
if not defined R_JAVA (echo [ERROR] R.java not generated & exit /b 1)
echo   R.java: !R_JAVA!

REM --- Collect all .java under the (possibly overlaid) source dir ---
set JAVA_FILES=
for /r "%SRC_DIR%" %%f in (*.java) do set JAVA_FILES=!JAVA_FILES! "%%f"
if not defined JAVA_FILES (echo [ERROR] no .java files under %SRC_DIR% & exit /b 1)

echo [3/7] javac
set CLASSPATH=%BUILD_DIR%\deps-classes\xray;%BUILD_DIR%\deps-classes\tor;%BUILD_DIR%\deps-classes\lbc;%DEPS%\jtorctl-0.4.5.7.jar;%DEPS%\kotlin-stdlib-2.2.10.jar
javac -encoding UTF-8 --release 8 -nowarn -classpath "%ANDROID_JAR%;%CLASSPATH%" -d %BUILD_DIR%\classes !JAVA_FILES! "!R_JAVA!"
if errorlevel 1 (echo [ERROR] javac failed & exit /b 1)

echo [4/7] d8
for %%N in (xray tor lbc) do (
    pushd %BUILD_DIR%\deps-classes\%%N
    jar cfM "..\deps-%%N-classes.jar" . || exit /b 1
    popd
)
set CLASSES=
for /r "%BUILD_DIR%\classes" %%f in (*.class) do set CLASSES=!CLASSES! "%%f"
if not defined CLASSES (echo [ERROR] no .class files produced by javac & exit /b 1)
call "%D8%" --lib "%ANDROID_JAR%" --min-api %MIN_SDK% --output %BUILD_DIR%\dex !CLASSES! "%BUILD_DIR%\deps-classes\deps-xray-classes.jar" "%BUILD_DIR%\deps-classes\deps-tor-classes.jar" "%BUILD_DIR%\deps-classes\deps-lbc-classes.jar" "%DEPS%\jtorctl-0.4.5.7.jar" "%DEPS%\kotlin-stdlib-2.2.10.jar"
if errorlevel 1 (echo [ERROR] d8 failed & exit /b 1)
if not exist %BUILD_DIR%\dex\classes.dex (echo [ERROR] d8 produced no classes.dex & exit /b 1)

echo [5/7] jar uf
copy /y %BUILD_DIR%\base.apk %BUILD_DIR%\unaligned.apk >nul
jar uf %BUILD_DIR%\unaligned.apk -C %BUILD_DIR%\dex classes.dex
if errorlevel 1 (echo [ERROR] jar uf failed & exit /b 1)

if "%TAINT%"=="1" goto libs_done
for %%A in (%ABIS%) do (
    mkdir %BUILD_DIR%\apk-libs\lib\%%A 2>nul
    copy /y jniLibs\%%A\*.so %BUILD_DIR%\apk-libs\lib\%%A\
    if "%TAINT%"=="4" (
        if exist "%BUILD_DIR%\deps-aar\xray\jni\%%A\libgojni.so" copy /y "%BUILD_DIR%\deps-aar\xray\jni\%%A\libgojni.so" %BUILD_DIR%\apk-libs\lib\%%A\
        if exist "%BUILD_DIR%\deps-aar\tor\jni\%%A\libtor.so"    copy /y "%BUILD_DIR%\deps-aar\tor\jni\%%A\libtor.so"    %BUILD_DIR%\apk-libs\lib\%%A\
    ) else (
        if "%TAINT%"=="3" if exist "%BUILD_DIR%\deps-aar\xray\jni\%%A\libgojni.so" copy /y "%BUILD_DIR%\deps-aar\xray\jni\%%A\libgojni.so" %BUILD_DIR%\apk-libs\lib\%%A\
        echo   [taint] libgojni/libtor per stage rules (stage 2: DPI only, stage 3: no Tor)
    )
)
jar uf %BUILD_DIR%\unaligned.apk -C %BUILD_DIR%\apk-libs lib
if errorlevel 1 (echo [ERROR] jar uf libs failed & exit /b 1)
:libs_done

echo [6/7] zipalign
"%ZIPALIGN%" -p -f 4 %BUILD_DIR%\unaligned.apk %BUILD_DIR%\aligned.apk
if errorlevel 1 (echo [ERROR] zipalign failed & exit /b 1)

echo [7/7] apksigner
if not exist debug.keystore (
    echo   generating debug.keystore
    keytool -genkeypair -v -keystore debug.keystore -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=WhitelistBypass,C=RU"
    if errorlevel 1 (echo [ERROR] keytool failed & exit /b 1)
)
call "%APKSIGNER%" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out %OUT_APK% %BUILD_DIR%\aligned.apk
if errorlevel 1 (echo [ERROR] apksigner failed & exit /b 1)

echo.
echo ============================================
echo  DONE: %OUT_APK%  (stage %TAINT%)
echo  Install: adb install -r %OUT_APK%
echo ============================================
endlocal
