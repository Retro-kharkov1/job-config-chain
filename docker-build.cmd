@echo off
setlocal enabledelayedexpansion

rem job-config-chain: zero-host-tooling Docker build (native cmd.exe variant of
rem docker-build.sh, for machines without Git Bash/WSL). See docker-build.sh for the
rem full rationale (why two sequential `docker run` calls, not docker-compose; image
rem choices) -- this script does the exact same two steps.
rem
rem Usage:
rem   docker-build.cmd
rem
rem Result: target\job-config-chain.hpi lands on the HOST filesystem at the same
rem path mvnw.cmd already produces it at.

set "MAVEN_IMAGE=maven:3.9-eclipse-temurin-21"

set "REPO_DIR=%~dp0"
if "%REPO_DIR:~-1%"=="\" set "REPO_DIR=%REPO_DIR:~0,-1%"

echo ==^> Building with Maven (%MAVEN_IMAGE%)
rem -Dmaven.clean.failOnError=false: works around a documented Windows-only file-lock
rem issue where maven-clean-plugin's clean:clean goal can fail to delete a native helper
rem lib the JVM briefly still holds open, on repeated builds against this bind mount.
rem See https://maven.apache.org/plugins/maven-clean-plugin/clean-mojo.html#failOnError
docker run --rm -v "%REPO_DIR%:/repo" -w /repo %MAVEN_IMAGE% mvn -Dmaven.clean.failOnError=false clean verify
if errorlevel 1 exit /b 1

echo ==^> Done. Artifact: target\job-config-chain.hpi (dev version 999999-SNAPSHOT)
