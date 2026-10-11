#!/usr/bin/env bash
set -euo pipefail

# job-config-chain: zero-host-tooling Docker build.
#
# Runs the Maven build inside a container, so a machine with NO host-installed Java or Maven can
# still produce target/job-config-chain.hpi. This is a plain dev build: the version stays
# pom.xml's `${changelist}` default (999999-SNAPSHOT). A release version (<commit count>.v<hash>)
# is only computed when `-Dset.changelist` is passed (git-changelist-maven-extension), which the
# release workflow does on a clean tag checkout. This is an ADDITIONAL option
# alongside mvnw/mvnw.cmd, not a replacement for them.
#
# Images used (pinned, not `:latest`, for reproducible builds):
#   - maven:*-eclipse-temurin-21 — official Maven image, JDK 21. Real build-time requirement
#     discovered 2026-09-16 while bumping the org.jenkins-ci.plugins:plugin parent POM to
#     6.2236.v12dd4c483242: that parent's managed maven-hpi-plugin version fails its own
#     `validate` goal with "Java 21 or later is necessary to build this plugin" on JDK 17 —
#     confirmed by an actual failing build, not assumed. This is independent of pom.xml's
#     <maven.compiler.release>17</maven.compiler.release> (the *target* bytecode/API level,
#     kept at 17 for broad Jenkins-core compatibility per the parent's own baseline) — JDK 21
#     can still compile `--release 17` output; JDK 21 is only required to *run* Maven/the
#     hpi-plugin's tooling itself.
#
# Usage:
#   ./docker-build.sh
#
# Result: target/job-config-chain.hpi lands on the HOST filesystem (bind-mounted, not
# copied out of a container), at the same path other tooling (e.g. Docker-Jenkins e2e
# testing of this plugin) already expects.

MAVEN_IMAGE="maven:3.9-eclipse-temurin-21"

# Resolve this script's directory as a Windows-style path when running under Git
# Bash/MSYS (pwd -W), otherwise fall back to the plain POSIX path (Linux/macOS/native
# WSL) — Docker Desktop's bind-mount resolution needs the Windows-style path when
# invoked from Git Bash. MSYS_NO_PATHCONV stops MSYS from mangling the container-side
# "/repo" argument into a bogus Windows path.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
export MSYS_NO_PATHCONV=1

echo "==> Building with Maven (${MAVEN_IMAGE})"
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
  mvn -Dmaven.clean.failOnError=false clean verify

echo "==> Done. Artifact: target/job-config-chain.hpi (dev version 999999-SNAPSHOT)"
