# Contributing

Thanks for considering a contribution. This plugin keeps versioned, structurally-validated
configuration templates inside Jenkins itself, so changes tend to touch persisted data — please read
the parts below that apply before opening a pull request.

## Building

The build runs entirely in containers, so no host-installed Java, Maven, or GitVersion is required:

```bash
./docker-build.sh          # Linux/macOS/Git Bash
docker-build.cmd           # Windows
```

The result is `target/job-config-chain.hpi`. A conventional `./mvnw verify` also works if you do have
a JDK 21+ on the host.

Versions are computed by GitVersion from commit history, not written into `pom.xml` — the
`<version>` element is `${revision}` and the build passes `-Drevision=<SemVer>`. This means **an
uncommitted change cannot produce a new version number**: commit first, then build, or successive
builds will all carry the same version and become impossible to tell apart.

Use `+semver: minor` or `+semver: major` in a commit message to bump beyond the default patch
increment.

## Tests

`./docker-build.sh` runs the full suite. Please do not delete or weaken a failing test to get a green
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
- Make sure the build is green before asking for review.
