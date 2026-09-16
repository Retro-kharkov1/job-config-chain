#!/usr/bin/env bash
set -euo pipefail

# job-config-chain: zero-host-tooling Docker build.
#
# Mirrors what mvnw/mvnw.cmd already do for a local build (dotnet-gitversion computes a
# real SemVer from git history, then `-Drevision=<SemVer>` is forwarded into Maven), but
# runs both steps inside containers, so a machine with NO host-installed Java, Maven, or
# GitVersion CLI can still produce a correctly versioned target/job-config-chain.hpi.
# This is an ADDITIONAL option alongside mvnw/mvnw.cmd, not a replacement for them.
#
# Why two sequential `docker run` calls instead of a docker-compose.yml:
# `${revision}` is interpolated into pom.xml's <version> while Maven builds its project
# model, BEFORE any lifecycle phase runs (see the long comment on the `revision` property
# in pom.xml) — so the SemVer MUST be known and passed as `-Drevision=...` before the
# Maven container's `mvn` invocation starts. docker-compose services do not have a
# built-in mechanism to capture one service's stdout and inject it as an argument into a
# second service's command (`depends_on` only orders container start, it does not pipe
# output between services) — verified this is a real limitation, not an assumption, before
# choosing a plain wrapper script instead. Capturing stdout into a shell variable and
# passing it via `-Drevision=` to the second `docker run` is the reliable mechanism.
#
# Images used (pinned, not `:latest`, for reproducible builds):
#   - gittools/gitversion   — official GitVersion image: https://gitversion.net/docs/usage/docker
#   - maven:*-eclipse-temurin-11 — official Maven image, JDK 11 to match this project's
#     <java.level>11</java.level> / <maven.compiler.source>11</maven.compiler.source> in pom.xml.
#
# Usage:
#   ./docker-build.sh
#
# Result: target/job-config-chain.hpi lands on the HOST filesystem (bind-mounted, not
# copied out of a container), at the same path other tooling (e.g. Docker-Jenkins e2e
# testing of this plugin) already expects.

GITVERSION_IMAGE="gittools/gitversion:6.8.2-alpine.3.23-9.0"
MAVEN_IMAGE="maven:3.9-eclipse-temurin-11"

# Resolve this script's directory as a Windows-style path when running under Git
# Bash/MSYS (pwd -W), otherwise fall back to the plain POSIX path (Linux/macOS/native
# WSL) — Docker Desktop's bind-mount resolution needs the Windows-style path when
# invoked from Git Bash. MSYS_NO_PATHCONV stops MSYS from mangling the container-side
# "/repo" argument into a bogus Windows path.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
export MSYS_NO_PATHCONV=1

echo "==> [1/2] Computing SemVer via GitVersion (${GITVERSION_IMAGE})"
SEMVER="$(docker run --rm -v "${SCRIPT_DIR}:/repo" "${GITVERSION_IMAGE}" /repo /showvariable SemVer)"
echo "    SemVer: ${SEMVER}"

echo "==> [2/2] Building with Maven (${MAVEN_IMAGE}), -Drevision=${SEMVER}"
# `-Dmaven.clean.failOnError=false`: on repeated builds against this Windows-backed bind
# mount, maven-clean-plugin's `clean:clean` goal can hit a well-known Windows file-lock
# issue deleting a native helper lib (e.g. plexus-utils' own JNA-extracted temp .dll under
# target/tmp) that the JVM briefly still holds open — a documented Windows-only quirk of
# `mvn clean`, unrelated to this project's own code (see maven-clean-plugin's official
# `failOnError` parameter: https://maven.apache.org/plugins/maven-clean-plugin/clean-mojo.html#failOnError).
# `false` makes a leftover-file clean warning non-fatal instead of aborting an otherwise
# healthy build; it does not skip cleaning, it only stops one locked leftover from failing
# the whole run.
# Named volume `job-config-chain-m2` persists the local Maven repo across separate
# `docker-build.sh` invocations (a plain `docker run --rm` has no state of its own between runs) —
# without it, every invocation re-downloads the full dependency tree from scratch.
docker run --rm -v "${SCRIPT_DIR}:/repo" -v "job-config-chain-m2:/root/.m2" -w /repo "${MAVEN_IMAGE}" \
  mvn -Drevision="${SEMVER}" -Dmaven.clean.failOnError=false clean verify

echo "==> Done. Artifact: target/job-config-chain.hpi (Plugin-Version: ${SEMVER})"
