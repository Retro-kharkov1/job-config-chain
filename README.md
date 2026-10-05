# Job Config Chain

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A Jenkins plugin for **job-scoped configuration management** with **base-chain composition**,
**secrets binding**, and **build-identity pinning** for safe rollback. It keeps a nested, structured
config store (shared **common** Config Sets plus per-job overrides) in sync with `#{Dotted.Path}#`
placeholder tokens in a target application config file (for example `appsettings.json` or
`application.yml`). It validates drift between the store and the file before a deploy, and substitutes the
real values into the file at deploy time. Everything is persisted natively under `$JENKINS_HOME`; no external
database and no git-backed store.

## Contents

- [Quick start](#quick-start)
- [Install](#install)
- [Concepts](#concepts)
- [Pipeline steps at a glance](#pipeline-steps-at-a-glance)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [Support this project](#support-this-project)
- [License](#license)

## Quick start

1. [Install](#install) the plugin.
2. Open **Manage Jenkins** and the **Config Chains** page (`/configChains`), create a Config Set (for example
   `sample-app`), then write and activate its first version. Mark secret leaves and bind each to a Jenkins
   credential ID (never a real value).
3. Open the consuming job's own **Config Chains** page (`/job/<name>/configChains`), add the Config Set to the
   job's base chain and, optionally, the job's own override content.
4. Call the steps from the Jenkinsfile:

```groovy
pipeline {
  agent any
  stages {
    stage('Prepare config') {
      steps {
        configChainValidate(file: 'app.json')
        configChainSubstitute(file: 'app.json')
      }
    }
  }
}
```

`configChainValidate` fails the build if `app.json` references a `#{Path}#` token with no matching key in the
effective configuration. `configChainSubstitute` then replaces every token in place, taking secrets from their
bound Jenkins credentials. The full click-through with screenshots is in
[Getting started](docs/getting-started.md).

## Install

> The plugin is not yet listed on the Jenkins Update Center (plugins.jenkins.io). Until it is, install it
> manually; this section will be replaced by the Update Center listing once the plugin is hosted.

1. Download `job-config-chain.hpi` from the latest
   [GitHub Release](https://github.com/Retro-kharkov1/job-config-chain/releases).
2. In Jenkins: **Manage Jenkins -> Plugins -> Advanced settings -> Deploy Plugin**, choose the file and upload.

Building the `.hpi` yourself is described in [CONTRIBUTING.md](CONTRIBUTING.md). Hosting status:
[HOSTING.md](HOSTING.md).

## Concepts

- **Config Set** - a named, versioned configuration (JSON, XML or YAML) with a change note per version and one
  active version. A *common* Config Set is global; a job also has its own Config Set (its override).
- **Base chain** - the ordered list of common Config Sets a job builds on; each entry follows the active
  version or is pinned to one. Later entries win on overlapping keys, and the job's own override is applied last.
- **Effective configuration** - the result of merging the base chain and the override. Layers are combined with
  RFC 7396 Merge Patch, so **arrays are replaced wholesale by an overlay, never merged element by element**.
- **Secrets manifest** - the paths that hold credentials, each bound to a Jenkins credential ID. The stored
  content only ever contains a placeholder at those paths.
- **Deployment Binding** - recorded on every real substitution: the exact versions a build used. It lets a later
  build replay an older deployment byte-identically (`redeployFromRun`).

## Pipeline steps at a glance

| Step | Purpose |
|---|---|
| `configChainValidate` | Fails if the file has tokens with no matching key (drift); warns about unused keys. |
| `configChainSubstitute` | Replaces every `#{Path}#` token in the file with its value and records a Deployment Binding. |
| `setupConfigChain` | Declares `file` and the resolution parameters once for the rest of the build. |

Parameters: `file`, `useBase`, `configKey`, `version`, `redeployFromRun`, `encoding`. The former names
`configTemplateValidate`, `configTemplateSubstitute` and `setupConfigTemplate` still work as deprecated aliases.
Details, the resolution matrix and the failure messages are in the
[Pipeline step reference](docs/pipeline-steps.md).

## Documentation

| Page | Content |
|---|---|
| [Getting started](docs/getting-started.md) | Install to first successful build, with screenshots. |
| [Pipeline step reference](docs/pipeline-steps.md) | Parameters, resolution matrix, isolation, failures, pinning, encoding, arrays. |
| [Use cases](docs/use-cases.md) | Scenario walkthroughs: authoring, deploying, rollback, drift. |
| [HOSTING.md](HOSTING.md) | Status of the Jenkins hosting request. |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Build from source, fast UI loop, tests, pull requests. |

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md), including the fast
`mvn hpi:run` loop for UI changes.

## Support this project

If this plugin saves you time, consider supporting its development:

- [GitHub Sponsors](https://github.com/sponsors/Retro-kharkov1)
- [Buy Me a Coffee](https://buymeacoffee.com/retro.kharkov)

## License

MIT - see [LICENSE](LICENSE).
