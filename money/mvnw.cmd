@ECHO OFF
SETLOCAL

set APP_HOME=%~dp0
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
if "%APP_HOME:~-1%"=="\" set APP_HOME=%APP_HOME:~0,-1%
set WRAPPER_JAR=%APP_HOME%\.mvn\wrapper\maven-wrapper.jar

where java >NUL 2>NUL
if errorlevel 1 (
  echo Error: Java not found in PATH.
  exit /b 1
)

if not exist "%WRAPPER_JAR%" (
  echo Error: "%WRAPPER_JAR%" not found.
  echo Run once with internet access to download wrapper jar.
  exit /b 1
)

java -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%APP_HOME%" org.apache.maven.wrapper.MavenWrapperMain %*
exit /b %errorlevel%
