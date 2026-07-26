@echo off
setlocal EnableExtensions
set "BASE_DIR=%~dp0"
set "PROPERTIES=%BASE_DIR%.mvn\wrapper\maven-wrapper.properties"
set "WRAPPER_JAR=%BASE_DIR%.mvn\wrapper\maven-wrapper.jar"

for /F "usebackq tokens=1,* delims==" %%A in ("%PROPERTIES%") do (
  if "%%A"=="wrapperUrl" set "WRAPPER_URL=%%B"
  if "%%A"=="wrapperSha256Sum" set "WRAPPER_SHA256=%%B"
)

if not exist "%WRAPPER_JAR%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop';" ^
    "New-Item -ItemType Directory -Force -Path (Split-Path '%WRAPPER_JAR%') | Out-Null;" ^
    "Invoke-WebRequest -UseBasicParsing -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%.tmp';" ^
    "if ('%WRAPPER_SHA256%' -ne '') { $actual=(Get-FileHash '%WRAPPER_JAR%.tmp' -Algorithm SHA256).Hash.ToLowerInvariant(); if ($actual -ne '%WRAPPER_SHA256%') { throw 'Maven Wrapper checksum validation failed' } };" ^
    "Move-Item -Force '%WRAPPER_JAR%.tmp' '%WRAPPER_JAR%'"
  if errorlevel 1 exit /B 1
)

if defined JAVA_HOME (
  set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_CMD=java.exe"
)

"%JAVA_CMD%" %MAVEN_OPTS% -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%BASE_DIR%" org.apache.maven.wrapper.MavenWrapperMain %*
exit /B %ERRORLEVEL%
