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

Versions follow the Jenkins continuous-delivery scheme ([JEP-229](https://github.com/jenkinsci/jep/tree/master/jep/229),
[releasing-cd](https://www.jenkins.io/doc/developer/publishing/releasing-cd/)). `pom.xml` has `<version>${changelist}</version>`
with `changelist` defaulting to `999999-SNAPSHOT`; `.mvn/extensions.xml` (git-changelist-maven-extension) and
`.mvn/maven.config` (`-Dchangelist.format=%d.v%s`) compute `<commit count>.v<12-char commit hash>`, for example
`1234.vabcdef012345`, but only when Maven is run with `-Dset.changelist` (needs a clean working tree and full git
history). A normal local or Docker build omits the flag and produces `999999-SNAPSHOT`, which is what you want
during development. To check a release version locally: commit, then `mvn -Dset.changelist -DskipTests clean verify`.
The `.hpi` carries the version in `Plugin-Version`.

Releases: pushing a `vX.Y.Z` tag runs `.github/workflows/release.yml` (build, tests, GitHub Release with the `.hpi`,
GitHub Packages). The tag is only the release label; the plugin version is the computed one above (tag `v0.2.0` ->
`job-config-chain-1234.vabcdef012345.hpi`). Once the plugin lives in the Jenkins organization, releases go through
`.github/workflows/cd.yaml` instead, with the same version scheme; see [HOSTING.md](HOSTING.md).

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
