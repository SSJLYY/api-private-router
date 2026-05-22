@echo off
setlocal

if exist "%~dp0\.mvn\wrapper\maven-wrapper.jar" (
  java -cp "%~dp0\.mvn\wrapper\maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain %*
  exit /b %errorlevel%
)

echo Maven Wrapper files are not fully bootstrapped yet.
echo.
echo Expected file:
echo   .mvn\wrapper\maven-wrapper.jar
echo.
echo Current workspace has Java but no Maven installation.
echo Bootstrap options:
echo   1. Install Maven and run: mvn -N wrapper:wrapper
echo   2. Copy a prepared .mvn\wrapper\ directory into java-backend
echo.
exit /b 1
