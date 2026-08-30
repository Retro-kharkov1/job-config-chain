@echo off
setlocal enabledelayedexpansion

rem config-template-sync: zero-host-tooling Docker build (native cmd.exe variant of
rem docker-build.sh, for machines without Git Bash/WSL). See docker-build.sh for the
rem full rationale (why two sequential `docker run` calls, not docker-compose; image
rem choices) -- this script does the exact same two steps.
rem
rem Usage:
rem   docker-build.cmd
rem
rem Result: target\config-template-sync.hpi lands on the HOST filesystem at the same
rem path mvnw.cmd already produces it at.

set "GITVERSION_IMAGE=gittools/gitversion:6.8.2-alpine.3.23-9.0"
set "MAVEN_IMAGE=maven:3.9-eclipse-temurin-11"

set "REPO_DIR=%~dp0"
if "%REPO_DIR:~-1%"=="\" set "REPO_DIR=%REPO_DIR:~0,-1%"

echo ==^> [1/2] Computing SemVer via GitVersion (%GITVERSION_IMAGE%)
set "SEMVER="
for /f "usebackq delims=" %%V in (`docker run --rm -v "%REPO_DIR%:/repo" %GITVERSION_IMAGE% /repo /showvariable SemVer`) do set "SEMVER=%%V"
if "%SEMVER%"=="" (
  echo Failed to compute SemVer via GitVersion Docker image >&2
  exit /b 1
)
echo     SemVer: %SEMVER%

echo ==^> [2/2] Building with Maven (%MAVEN_IMAGE%), -Drevision=%SEMVER%
rem -Dmaven.clean.failOnError=false: works around a documented Windows-only file-lock
rem issue where maven-clean-plugin's clean:clean goal can fail to delete a native helper
rem lib the JVM briefly still holds open, on repeated builds against this bind mount.
rem See https://maven.apache.org/plugins/maven-clean-plugin/clean-mojo.html#failOnError
docker run --rm -v "%REPO_DIR%:/repo" -w /repo %MAVEN_IMAGE% mvn -Drevision=%SEMVER% -Dmaven.clean.failOnError=false clean verify
if errorlevel 1 exit /b 1

echo ==^> Done. Artifact: target\config-template-sync.hpi (Plugin-Version: %SEMVER%)
