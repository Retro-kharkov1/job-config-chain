# Config Template Sync

A Jenkins plugin that keeps a nested, structured config-token store (split into one **common**
layer and per-environment **env** overlays) structurally in sync with `#{Dotted.Path}#`
placeholder tokens in a target application config file (e.g. `appsettings.json`,
`application.yml`). It validates drift between the store and the file before a deploy, and
substitutes real values into the file at deploy time — without an external database and without a
git-backed store; everything is persisted natively under `$JENKINS_HOME`.

## Domain model

- **Config Set** — a named, versioned collection of configuration, either `common` (full nested
  baseline) or `env` (a sparse overlay for one environment). A common Config Set and its env Config
  Sets are tied together structurally by a shared `projectKey`, never by free-text name matching.
- **Config Set Version** — an immutable, append-only snapshot (content, author, timestamp, a
  mandatory non-empty change note). Version numbers are monotonically increasing and never reused.
  Exactly one version per Config Set is "active" at a time; activating a version is a pure metadata
  flip and never triggers a live deploy by itself.
- **Effective Configuration** — the env layer's active/pinned content applied as an
  [RFC 7396 JSON Merge Patch](https://www.rfc-editor.org/rfc/rfc7396) on top of the common layer's
  active/pinned content. Always computed on demand, never persisted as a separate copy.
- **Deployment Binding** — records exactly which common/env version numbers were used the last time
  a given build/version identifier was substituted for real, so a later rollback of the application
  build can replay the same config it originally shipped with. Pinning via a Deployment Binding is
  opt-in: it only applies when the caller explicitly passes `buildVersion` to the substitute step.

## Known limitation: arrays are replaced wholesale, never merged element-by-element

RFC 7396 JSON Merge Patch has a well-known limitation with JSON arrays: a patch always replaces an
array value wholesale, it never merges array elements one-by-one. This plugin inherits that
limitation as-is and does not attempt to work around it in this version — if an env-layer overlay
sets a path whose value is an array, that array entirely replaces whatever array (or other value)
was at that path in the common layer's content. There is no per-element override mechanism. Model
array-valued settings accordingly (e.g. prefer an object keyed by a stable identifier over an array
of objects, if you need per-environment element-level overrides).

## Pipeline steps

- `configTemplateValidate(projectKey, environment, file)` — fails the build, naming every
  placeholder token in `file` that has no matching key in the effective configuration ("missing").
  A key in the effective configuration with no matching token in `file` ("orphaned") only produces
  a warning, it never fails the build.
- `configTemplateSubstitute(projectKey, environment, file, buildVersion)` — `buildVersion` is
  optional. Re-runs the same drift check as `configTemplateValidate` as a defensive re-check, then
  resolves the effective configuration (pinned to a prior Deployment Binding if `buildVersion`
  matches one, otherwise the currently active versions — with an explicit log line if a
  `buildVersion` was given but no binding was found), substitutes every `#{Dotted.Path}#` token in
  `file`, and fails if any token-shaped text remains afterward. This step does not resolve Jenkins
  credentials itself — secret values are expected to already be present in the calling
  Jenkinsfile's environment (e.g. via `withCredentials`) and are merged in by dotted-path key before
  substitution.

## Building and versioning

Build with the bundled Maven Wrapper — no separate script or CI platform required:

```
./mvnw clean verify      # Linux/macOS
mvnw.cmd clean verify    # Windows
```

The wrapper computes the plugin's version from git history via the
[GitVersion](https://gitversion.net/) CLI (`dotnet-gitversion`, config in `GitVersion.yml`) before
delegating to Maven, and passes it as `-Drevision=<computed SemVer>` (Maven's
[CI Friendly Versions](https://maven.apache.org/maven-ci-friendly.html) mechanism — see `pom.xml`).
This only affects the version of the artifact produced by a **local** build; it has no dependency on
any CI platform and does not decide where or when the plugin gets deployed. If `dotnet-gitversion` is
not installed, the build still succeeds and falls back to the default `revision` in `pom.xml`
(`0.0.0-SNAPSHOT`).

Invoking the raw `mvn` binary directly (bypassing the wrapper) also works, but without automatic
version computation — Maven resolves `${revision}` while building the reactor's project model, before
any plugin execution runs, so it cannot be set from inside the build itself; only the wrapper (or an
explicit `-Drevision=...` flag) can supply it in time.

### Zero-host-tooling option: Docker build

If you don't want to install Java, Maven, or the GitVersion CLI on your machine at all — only
[Docker](https://www.docker.com/) is required — use:

```
./docker-build.sh      # Linux/macOS/Git Bash/WSL
docker-build.cmd       # Windows cmd.exe
```

This runs the exact same two logical steps as `mvnw`/`mvnw.cmd`, just inside containers instead of on
the host:

1. [`gittools/gitversion`](https://gitversion.net/docs/usage/docker) (the official GitVersion Docker
   image) computes the SemVer from this repo's git history, mounted read/write at `/repo`.
2. The official `maven` image (`eclipse-temurin-11` variant, matching this project's
   `<java.level>11</java.level>` in `pom.xml`) runs `mvn -Drevision=<computed SemVer> clean verify`
   against the same mounted repo.

Both steps run as **two sequential `docker run` commands**, not a single `docker-compose.yml` —
compose services have no built-in way to capture one service's stdout and inject it as a `-D`
argument into a second service's command, and `${revision}` must be known before Maven starts (see
the note above), so a plain script that captures GitVersion's output into a shell variable and passes
it to the Maven container is the reliable mechanism.

`target/config-template-sync.hpi` lands on the **host** filesystem at the same path the `mvnw`/
`mvnw.cmd` path already produces it at (the repo directory is bind-mounted into both containers, not
copied in/out) — so anything downstream that expects it there (e.g. Docker-Jenkins e2e testing of
this plugin) keeps working unchanged.

## Explicitly out of scope for this milestone

No Stapler/Jelly admin UI, no Monaco editor integration, no private Jenkins Update Center, and no
version-history retention/pruning policy (history is kept indefinitely by design — see the
project's requirements documentation for the reasoning).
