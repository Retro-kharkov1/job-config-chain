# syntax=docker/dockerfile:1
#
# Disposable, fully-seeded Jenkins for e2e-testing the config-template-sync plugin.
# Build context MUST be the repo root (see docker-compose.yml: `context: ..`, `dockerfile:
# docker/test-jenkins/Dockerfile`) so stage 1 can see pom.xml/src.
#
# NOT a hardened deployment image — setup wizard is disabled and Jenkins boots with no
# security realm configured, on purpose, so a fresh `docker compose up` needs zero manual
# clicks. Never reuse this image/recipe for anything internet-facing.

########################################################################
# Stage 1: build the plugin's .hpi from source, so `docker compose up --build` never
# depends on a pre-existing host build (target/config-template-sync.hpi).
#
# Plain `mvn` (no mvnw wrapper, no GitVersion CLI) — mirrors docker-build.sh's approach
# for the exact same reason: `-Drevision=...` can't be supplied without a SemVer already
# computed, and the plugin's own README documents that a bare `mvn` invocation still
# succeeds, falling back to pom.xml's default `revision` (0.0.0-SNAPSHOT). The Jenkins
# plugin parent POM (org.jenkins-ci.plugins:plugin) pins <finalName> to the bare
# artifactId, so the output path is always target/config-template-sync.hpi regardless of
# version — matching what stage 2 below expects.
#
# JDK/Maven image pinned to eclipse-temurin-11 to match this project's
# <java.level>11</java.level> (see pom.xml and docker-build.sh) — NOT JDK 21, even though
# the Jenkins controller image in stage 2 is jdk21 (that JDK only runs the Jenkins
# controller itself; it has no bearing on the plugin's own compiler target).
########################################################################
FROM maven:3.9-eclipse-temurin-11 AS plugin-builder
WORKDIR /build
COPY pom.xml ./
COPY src ./src
# BuildKit cache mount for the local Maven repo: without it, every `docker compose up --build`
# re-downloads the entire dependency tree from scratch inside the ephemeral build container,
# since normal COPY/RUN layers don't persist across builds the way a named volume would on a
# long-lived host. This mount persists across builds (keyed by the target path) even though the
# container itself is thrown away — dependencies are fetched once, not on every rebuild.
RUN --mount=type=cache,target=/root/.m2 mvn -B clean verify

########################################################################
# Stage 2: Jenkins controller with base plugins + this plugin's .hpi baked in at
# image-build time (no runtime plugin install, no restart-after-copy dance), plus the
# init.groovy.d seed script and its fixtures.
########################################################################
FROM jenkins/jenkins:lts-jdk21

# Skip the setup wizard entirely — disposable local test instance, not a login-gated
# deployment. No admin user is created; Jenkins boots with legacy full-anonymous-access
# semantics, which is intentional here and must never be copied into a real environment.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

COPY --chown=jenkins:jenkins docker/test-jenkins/plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY --from=plugin-builder --chown=jenkins:jenkins /build/target/config-template-sync.hpi /usr/share/jenkins/ref/plugins/config-template-sync.hpi

# Copied into $JENKINS_HOME on first boot only (the official image's ref-copy mechanism
# never overwrites a file that's already present in the JENKINS_HOME volume) — see
# https://github.com/jenkinsci/docker/blob/master/README.md. The seed script itself must
# still be idempotent because it re-runs on every subsequent container start once it has
# landed in the volume.
COPY --chown=jenkins:jenkins docker/test-jenkins/init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/

# Kept OUTSIDE $JENKINS_HOME/ref-copy semantics on purpose (this is not one of the
# directories the ref-copy mechanism is documented for) — the seed script reads its
# fixture Jenkinsfile straight off the image filesystem at startup instead.
COPY --chown=jenkins:jenkins docker/test-jenkins/fixtures/ /opt/config-template-sync-e2e/
