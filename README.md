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

## Installing through Jenkins' normal Update Center / Plugin Manager flow

Beyond manual `.hpi` upload (Manage Jenkins → Plugins → Advanced settings → Deploy Plugin), this
plugin can be installed the same way official plugins are — search-and-install from "Available
plugins" — by pointing a Jenkins instance at a **self-hosted private Update Center** instead of
(or in addition to) the default `updates.jenkins.io`.

### How the official public Update Center actually works (for later reference — not done in this
### pass)

Grounded in the official Jenkins developer docs:
[Source Code Hosting](https://www.jenkins.io/doc/developer/publishing/source-code-hosting/),
[Guide to Plugin Hosting](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/),
[Setting up plugin releases through GitHub](https://www.jenkins.io/doc/developer/publishing/releasing-cd/).

`updates.jenkins.io` is generated from artifacts released to Jenkins' own Artifactory
(`repo.jenkins-ci.org`), which in turn requires:

1. **A public GitHub repository** — the Jenkins project only hosts free/open-source plugins under
   an OSI-approved license; the repo must already be public before requesting hosting.
2. **A hosting request** — a GitHub issue filed in `jenkins-infra/repository-permissions-updater`
   using its hosting-request template, reviewed by the Jenkins Hosting team (turnaround: days,
   with a back-and-forth if changes are requested).
3. **A fork into the `jenkinsci` GitHub org** — once approved, the repo is forked into `jenkinsci`
   (maintainer keeps admin access there), and the original repo is expected to be deleted (a
   `jenkinsci`-owned fork can later be re-forked back out to keep one canonical location).
4. **Release permissions on Jenkins' Artifactory** — usually granted automatically by the hosting
   PR; otherwise requested separately via the `repository-permissions-updater` repo.
5. **A release mechanism** — the current recommended approach is the GitHub Actions **CD
   (Continuous Delivery)** workflow: any successful `ci.jenkins.io` build of the default branch can
   cut a new release automatically (using Maven CI-friendly incremental versions like
   `123.vabcdef456789`, not `maven-release-plugin`), triggered by labeled, merged PRs — no manual
   `mvn release:prepare/perform` or local credentials needed. This requires re-adding a CI platform
   (GitHub Actions) to the repo, which this project deliberately removed for local, CI-independent
   builds (see "Building and versioning" above) — a real, explicit trade-off to make consciously
   before pursuing this path, not something to slip in silently.

None of this is executed in this pass — this plugin is personal and currently private, which is
incompatible with step 1 above. This is documented here purely as the known "what it would take
later" checklist once/if the plugin is made public and mature enough to seek official hosting.

### The self-hosted private Update Center (set up in this pass)

Any Jenkins instance can point at **any** update site URL, not just the official one (Manage
Jenkins → Plugins → Advanced settings → **Update Site**), as long as that URL serves a correctly
shaped `update-center.json`. Grounded against `jenkinsci/jenkins` core source
(`hudson/model/UpdateSite.java`, `hudson/model/DownloadService.java` — no single jenkins.io page
documents the exact JSON field shapes precisely, so this was verified directly against the
parsing code, including two real bugs hit and worked around below) and the
[site layout reference](https://github.com/jenkins-infra/update-center2/blob/master/site/LAYOUT.md):

- `sha1`/`sha256` in a plugin entry must be the **Base64-encoded** binary digest (not hex).
- Every object in a plugin's `dependencies` array **must include an explicit `"optional"` field**
  as the literal string `"true"` or `"false"` — omitting it crashes `UpdateSite.getData()` with an
  uncaught `NullPointerException` the moment Jenkins tries to read the site
  (`UpdateSite.Plugin`'s constructor calls `get(depObj, "optional").equals("false")` with no null
  guard). This was hit and fixed while building this site — see
  `distribution/generate_update_center.py`.
- The file can be **plain JSON** (`DownloadService#loadJSON` slices between the first `{` and last
  `}`, so a legacy `updateCenter.post(...)` JSONP wrapper is optional, not required).
- By default Jenkins **verifies a cryptographic signature** on every update site's JSON
  (`hudson.model.DownloadService.signatureCheck`, source: `DownloadService.java`). This site is
  served as plain unsigned JSON (no RSA/X.509 signing pipeline for a single personal plugin), so
  any Jenkins instance consuming it must start with
  `-Dhudson.model.DownloadService.noSignatureCheck=true` on the JVM. This disables signature
  checking for **every** configured update site on that instance, including the default one — an
  acceptable trade-off for a personal/internal instance, but call it out explicitly before applying
  it to a shared production master.

**Regenerating the site after a build:**

```
mvnw.cmd clean package -DskipTests            # or ./mvnw for a full `verify` once tests are green
python3 distribution/generate_update_center.py \
  --hpi target/config-template-sync.hpi \
  --base-url "https://github.com/Retro-kharkov1/config-template-sync/releases/download/vVERSION" \
  --out distribution/site/update-center.json
```

The script reads the plugin name/version/required-core/dependencies straight out of the built
`.hpi`'s own `META-INF/MANIFEST.MF` (so the JSON can never drift from the artifact it describes)
and computes the Base64 sha1/sha256 itself.

**Where the `.hpi` + `update-center.json` are actually served from:** GitHub Release assets on
this repo (`https://github.com/Retro-kharkov1/config-template-sync/releases`) — chosen over GitHub
Pages because Release assets give a stable, directly-fetchable URL per version with zero extra
hosting/build step, whereas Pages would need a publishing job to keep a served directory in sync.
To cut a release: tag the commit (e.g. `git tag v0.1.0 && git push origin v0.1.0`), build, run the
generator with `--base-url` pointing at that tag's release-download path, then create a GitHub
Release for that tag in the UI and attach `target/config-template-sync.hpi` and
`distribution/site/update-center.json` as release assets.

**Point a Jenkins instance at it:** Manage Jenkins → Plugins → Advanced settings → Update Site →
add
`https://github.com/Retro-kharkov1/config-template-sync/releases/latest/download/update-center.json`
(GitHub's `.../releases/latest/download/<asset>` alias always resolves to the most recent
release's matching asset, so this URL never needs to change between versions) → Submit → Check now.
The plugin then appears in **Available plugins** and installs through the normal
search-and-install flow, the same as any plugin from the official Update Center.

#### ⚠️ Private-repo blocker (real, not glossed over)

This repository is currently **private**. GitHub Release assets and raw file URLs on a private
repo return `404`/`401` to an unauthenticated request — confirmed directly
(`curl -s https://api.github.com/repos/Retro-kharkov1/config-template-sync` → `404 Not Found` with
no credentials). Stock Jenkins' Update Center fetcher makes a plain unauthenticated HTTP `GET`; it
has no mechanism to attach a GitHub token to that request. **Practically, this means the update
site above only works for the owner's own authenticated tooling/local testing today — it does not
work for any other Jenkins instance until this repository is made public.** The project's earlier
stated plan was to go public "once the MVP is proven" — whether that bar is met is the owner's
call, not something flipped automatically here; this update-center setup is real and tested (see
below) but only becomes broadly useful once the repo's visibility changes.

#### Verified end-to-end, locally

Using `distribution/docker-compose.update-center-test.yml` (a disposable static file server
serving `distribution/site/` + a throwaway `jenkins/jenkins:lts-jdk17` container, standing in for
the not-yet-reachable GitHub Release URLs), this was actually run against a real Jenkins instance:

1. Registered the custom update site pointed at the local static server.
2. Confirmed `config-template-sync` appeared in `Available plugins`
   (`/updateCenter/api/json` `availables` list) with the correct version, checksum-bearing entry,
   and dependencies.
3. Triggered install through `pluginManager/installNecessaryPlugins` (the same endpoint the
   "Install" button in the UI calls) — Jenkins auto-resolved `workflow-step-api` and `credentials`
   from the default `updates.jenkins.io` site and installed `config-template-sync` from this site,
   with **no manual `.hpi` upload**.
4. Confirmed the plugin was `active: true`, `enabled: true` via `pluginManager/api/json`.
5. Also verified the negative case: pointing the site at the (currently 404) real GitHub Release
   URL produces exactly the `Failed to download from https://...` install failure predicted by the
   private-repo blocker above — i.e. the blocker isn't theoretical, it reproduces.

Run it yourself: `docker compose -f distribution/docker-compose.update-center-test.yml up -d`, then
Jenkins is at `http://localhost:8080` (no login — setup wizard is skipped for this disposable
container) and the static site at `http://localhost:8090/update-center.json`.

## Explicitly out of scope for this milestone

No Stapler/Jelly admin UI, no Monaco editor integration, and no version-history
retention/pruning policy (history is kept indefinitely by design — see the project's requirements
documentation for the reasoning). The private Update Center above is now in place; making the
repository public (required for it to work beyond local/owner testing) and pursuing official
`jenkinsci`-org hosting remain explicit follow-ups for the owner to decide on.
