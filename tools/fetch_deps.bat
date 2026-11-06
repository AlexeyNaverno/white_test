@echo off
setlocal
REM Download dependencies (not stored in the repo):
set DEPS=%~1
if "%DEPS%"=="" set DEPS=deps
mkdir "%DEPS%" 2>nul

echo [1/5] libv2ray.aar (Xray-core, ~60 MB)...
if not exist "%DEPS%\libv2ray.aar" curl -fL --retry 3 -o "%DEPS%\libv2ray.aar" "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.10.31/libv2ray.aar" || exit /b 1
echo [2/5] tor-android.aar (Tor 0.4.9.13.2, ~13 MB)...
if not exist "%DEPS%\tor-android.aar" curl -fL --retry 3 -o "%DEPS%\tor-android.aar" "https://repo1.maven.org/maven2/info/guardianproject/tor-android/0.4.9.13.2/tor-android-0.4.9.13.2.aar" || exit /b 1
echo [3/5] jtorctl-0.4.5.7.jar...
if not exist "%DEPS%\jtorctl-0.4.5.7.jar" curl -fL --retry 3 -o "%DEPS%\jtorctl-0.4.5.7.jar" "https://repo1.maven.org/maven2/info/guardianproject/jtorctl/0.4.5.7/jtorctl-0.4.5.7.jar" || exit /b 1
echo [4/5] kotlin-stdlib-2.2.10.jar...
if not exist "%DEPS%\kotlin-stdlib-2.2.10.jar" curl -fL --retry 3 -o "%DEPS%\kotlin-stdlib-2.2.10.jar" "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.10/kotlin-stdlib-2.2.10.jar" || exit /b 1
echo [5/5] localbroadcastmanager-1.1.0.aar...
if not exist "%DEPS%\localbroadcastmanager-1.1.0.aar" curl -fL --retry 3 -o "%DEPS%\localbroadcastmanager-1.1.0.aar" "https://dl.google.com/dl/android/maven2/androidx/localbroadcastmanager/localbroadcastmanager/1.1.0/localbroadcastmanager-1.1.0.aar" || exit /b 1
echo [deps] OK: %DEPS%
endlocal
