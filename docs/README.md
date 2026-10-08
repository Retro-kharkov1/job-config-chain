# Job Config Chain user guide

Job Config Chain keeps a structured configuration store in Jenkins in sync with the `#{Dotted.Path}#` tokens of
a target config file, validates drift before a deploy and substitutes the real values at deploy time.
This guide is organised by topic; the [project README](../README.md) is the short overview.

| Section | Read it when you want to |
|---|---|
| [Getting started](getting-started/README.md) | Go from install to a first successful build, with screenshots. |
| [Concepts](concepts/README.md) | Understand Config Sets, the base chain and override, merging, secrets and Deployment Bindings. |
| [User guide](user-guide/README.md) | Use the Manage Jenkins pages, the Config Set editor, the job page, compare and Generate Template. |
| [Pipeline reference](pipeline/README.md) | Look up step parameters, the resolution matrix, encoding, substitution rules and failure messages. |
| [Use cases](use-cases/README.md) | Follow scenario walkthroughs (`UF-1` ... `UF-23`): authoring, deploying, rollback, drift. |
| [Troubleshooting](troubleshooting/README.md) | Find the cause of a failed build or a missing page. |

Related project files: [README](../README.md), [CONTRIBUTING](../CONTRIBUTING.md), [HOSTING](../HOSTING.md).
