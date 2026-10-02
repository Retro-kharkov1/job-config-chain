# Contributing

Thanks for considering a contribution. This plugin keeps versioned, structurally-validated
configuration chains inside Jenkins itself, so changes tend to touch persisted data — please read
the parts below that apply before opening a pull request.

## Building from source

Requirements: JDK 21+ for a host build, or only Docker for the container build.

```bash
./mvnw clean verify        # Linux/macOS
mvnw.cmd clean verify      # Windows
```

```bash
./docker-build.sh          # Linux/macOS/Git Bash/WSL, no host Java/Maven needed
docker-build.cmd           # Windows cmd.exe
```

The result is `target/job-config-chain.hpi`. Install it by uploading it under Manage Jenkins -> Plugins ->
Advanced settings -> Deploy Plugin.

Versions are computed by GitVersion from commit history, not written into `pom.xml`: the `<version>` element is
`${revision}` and the build passes `-Drevision=<SemVer>` (the wrapper and `docker-build.*` do this; if
`dotnet-gitversion` is missing, the build falls back to `0.0.0-SNAPSHOT`). The `.hpi` version is
`${revision}-${changelist}`, with `changelist` defaulting to `999999-SNAPSHOT` outside the official CD pipeline.
An uncommitted change cannot produce a new version number: commit first, then build, or successive builds will
carry the same version. Use `+semver: minor` or `+semver: major` in a commit message to bump beyond a patch.

This versioning scheme is expected to change when the plugin moves to the Jenkins organization (official CD);
see [HOSTING.md](HOSTING.md).

## Fast loop for UI changes: `mvn hpi:run`

For Jelly, CSS and JavaScript work, start a throwaway Jenkins with the plugin loaded straight from the working
tree instead of rebuilding an `.hpi`:

```bash
mvn hpi:run
```

Jenkins starts at <http://localhost:8080/jenkins> with a fresh `work/` directory (reuse it between runs, delete
it to reset). Pipeline steps and the Snippet Generator need the Pipeline plugins; `hpi:run` loads the plugin's
declared dependencies, so install any extra plugin you need from the Plugins page.

Static resources (adjunct CSS/JS) and Jelly views are normally re-read from `src/main/resources` while the
server runs, so a browser refresh is enough. This is **unverified for this plugin**: Jelly files inside taglibs
(`lib/jobconfigchain`) may be cached and need a restart of `hpi:run`, or running with
`-Dstapler.jelly.noCache=true` (`mvn hpi:run -Dstapler.jelly.noCache=true`). Java changes always need a restart.
If a change does not show up, restart before debugging.

An alternative is the disposable Docker Jenkins with seeded data described in
[docker/test-jenkins/README.md](docker/test-jenkins/README.md).

## Tests

`./mvnw clean verify` and `./docker-build.sh` run the full suite. Please do not delete or weaken a failing test to get a green
build — if a test fails, that is the finding.

New behaviour needs a test. Two areas deserve particular care:

- **Persistence.** Config sets, versions, and job properties are stored inside `$JENKINS_HOME` via
  XStream. A rename or type change to a persisted field silently breaks reading existing data, so
  round-trip coverage matters more here than usual.
- **Merge semantics.** Base-chain resolution and RFC 7396 merge-patch decide which value actually
  reaches a deployed config file. Cover the precedence, not only the happy path.

## UI changes

The pages are Jelly, which is **XML** — a literal `<` or an HTML tag written inside a JavaScript
comment or expression will break the parser at render time rather than at build time. Escape them
(`&lt;`, `&amp;&amp;`) or reword the comment.

Icons use the `"symbol-<name> plugin-<plugin-short-name>"` spelling, space-separated. A dash-joined
value silently resolves to no plugin and renders a missing-symbol placeholder.

## Pull requests

- One logical change per pull request; keep unrelated cleanups separate.
- Describe what changes for a user of the plugin, and why — not which files moved.
- Open an issue first for significant work, and make sure the build is green before asking for review.
- Use the domain vocabulary of the user docs (`Config Key`, `Config Set`, `base chain`, `effective configuration`).
