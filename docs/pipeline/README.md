# Pipeline reference

[Guide home](../README.md) · [Getting started](../getting-started/README.md) · [Use cases](../use-cases/README.md)

Steps: `configChainValidate`, `configChainSubstitute`, `setupConfigChain`.

| Step | Purpose |
|---|---|
| `configChainValidate` | Fails if the file has tokens with no matching key (drift); warns about unused keys. |
| `configChainSubstitute` | Replaces every `#{Path}#` token in the file with its value and records a [Deployment Binding](../concepts/deployment-binding.md). |
| `setupConfigChain` | Declares `file` and the resolution parameters once for the rest of the build. |

| Page | Content |
|---|---|
| [Parameters](parameters.md) | Every parameter, the Map convention and `setupConfigChain`. |
| [Resolution matrix](resolution-matrix.md) | What each parameter combination resolves to, and the isolation rule. |
| [Substitution](substitution.md) | Single-pass substitution and the workspace warning. |
| [File encoding](encoding.md) | UTF-8 with fallback and the `encoding` parameter. |
| [Snippet Generator and help](snippet-generator.md) | In-Jenkins help and translations. |
| [Failure messages](failure-messages.md) | Fail-loud paths, drift messages and the build-log prefix. |

Steps work on Pipeline jobs, including multibranch branch jobs.
