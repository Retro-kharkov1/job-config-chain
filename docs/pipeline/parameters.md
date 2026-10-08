# Parameters

[Pipeline reference](README.md) · [Resolution matrix](resolution-matrix.md)

`configChainValidate` / `configChainSubstitute` take:

| Parameter | Type | Meaning |
|---|---|---|
| `file` | String, **always required** | Path to the target config file to validate/substitute. |
| `useBase` | boolean, default `false` | When `true`, resolves the base chain only (own override ignored), or redirects to a named global Config Set when `configKey` is also given. |
| `configKey` | String, optional | Only valid together with `useBase: true`. Names a global COMMON Config Set to resolve directly, independent of the calling job's own base chain. |
| `version` | int, optional | Pins a specific version instead of the currently ACTIVE one. Meaning depends on the other parameters; see the [resolution matrix](resolution-matrix.md). |
| `redeployFromRun` | int or `<jobFullName>#<buildNumber>`, optional (`configChainSubstitute`, `setupConfigChain`) | Replays a different Run's frozen Deployment Binding byte-identically, for rollback ([Deployment Binding](../concepts/deployment-binding.md)). |
| `encoding` | String, optional (`configChainValidate`, `configChainSubstitute`) | Charset of the target file; default UTF-8 with fallback, see [File encoding](encoding.md). |

There is no `environment` parameter: every call resolves, by construction, against the **calling job's own
attached local config**, unless `useBase: true` + `configKey` explicitly redirects to a named global Config Set.

> **Planned, not yet available:** the token delimiter shown throughout this guide (`#{...}#`) is being made
> configurable per Config Key/job (five built-in presets - `{{...}}`, `${...}`, `$(...)`, `%...%`, `#{...}#` -
> plus a custom prefix/suffix pair), with `#{...}#` staying the permanent zero-config default. This is a
> finalized spec, but **no code implementing it has been written yet**: every call, template and message in this
> guide today uses `#{...}#` unconditionally, with no way to change it.

## Jenkinsfile Map-parameter convention

A single Map built once and passed as the step's sole argument; `file` lives in the same map, not appended
separately:

```groovy
def cfg = [
    file: 'app.json',
    useBase: true,
    version: 5
    // configKey: 'shared-database',   // optional, only meaningful together with useBase: true
]
configChainValidate(cfg)
configChainSubstitute(cfg)
```

## `setupConfigChain`

`setupConfigChain(file:, useBase:, configKey:, version:, redeployFromRun:)` is a build-scoped convenience
alternative: call it once, and any later zero-argument (or partial) call to
`configChainValidate()`/`configChainSubstitute()` in the same build reads whichever parameter it wasn't itself
given from the stored state. Explicit call-site parameters always override stored setup state, per parameter.
`setupConfigChain` is rejected inside a `parallel {}` block (its build-scoped state would be ambiguous across
concurrently-running branches); the two other steps remain fully safe inside `parallel {}` when called with
their own full explicit parameters.

Scenarios: [setupConfigChain use cases](../use-cases/setup-config-chain.md).
