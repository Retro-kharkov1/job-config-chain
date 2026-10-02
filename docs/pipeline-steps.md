# Pipeline step reference

[Back to the README](../README.md) · [Getting started](getting-started.md) · [Use cases](use-cases.md)

Steps: `configChainValidate`, `configChainSubstitute`, `setupConfigChain`. Each is also available in the
Pipeline Syntax Snippet Generator, with help text on every field.

## Parameters

`configChainValidate` / `configChainSubstitute` take:

| Parameter | Type | Meaning |
|---|---|---|
| `file` | String, **always required** | Path to the target config file to validate/substitute. |
| `useBase` | boolean, default `false` | When `true`, resolves the base chain only (own override ignored), or redirects to a named global Config Set when `configKey` is also given. |
| `configKey` | String, optional | Only valid together with `useBase: true`. Names a global COMMON Config Set to resolve directly, independent of the calling Job's own base chain. |
| `version` | int, optional | Pins a specific version instead of the currently ACTIVE one. Meaning depends on the other parameters — see the matrix below. |
| `redeployFromRun` | int or `<jobFullName>#<buildNumber>`, optional (`configChainSubstitute`, `setupConfigChain`) | Replays a different Run's frozen Deployment Binding byte-identically, for rollback. |
| `encoding` | String, optional (`configChainValidate`, `configChainSubstitute`) | Charset of the target file; default UTF-8 with fallback, see [File encoding](#file-encoding). |

There is no `environment` parameter — every call resolves, by construction, against the **calling
Job's own attached local config**, unless `useBase: true` + `configKey` explicitly redirects to a
named global Config Set.

> **Planned, not yet available:** the token delimiter shown throughout this document (`#{...}#`)
> is being made configurable per Config Key/Job (five built-in presets — `{{...}}`, `${...}`,
> `$(...)`, `%...%`, `#{...}#` — plus a custom prefix/suffix pair), with `#{...}#` staying the
> permanent zero-config default. This is a finalized spec, but **no code implementing it has been
> written yet** — every call, template, and message in this document today uses `#{...}#`
> unconditionally, with no way to change it.

## Resolution matrix

| `useBase` | `configKey` | `version` | Resolves to |
|---|---|---|---|
| `false` | — | — | Job's own local config, ACTIVE version, full effective content (own override + its folded base chain) |
| `false` | — | `N` | Job's own local config, version `N`, full effective content of that version |
| `false` | given | any | **fail-loud** — `configKey` only valid together with `useBase: true` |
| `true` | — | — | Job's own local config, ACTIVE version's folded base chain only (own override content ignored) |
| `true` | — | `N` | Job's own local config, version `N`'s own recorded base chain folded (that version's own override ignored) — unambiguous regardless of chain length |
| `true` | `X` | — | Global COMMON Config Set named `X`, its ACTIVE version — direct global lookup by name, NOT required to be present in the Job's own base chain |
| `true` | `X` | `N` | Global COMMON Config Set named `X`, pinned to version `N` |

## Isolation rule

A call can **never** reach another Job's own attached local config, by any parameter, under any
composition — full stop. The only things any call can ever reach are (a) the calling Job's own
local config, and (b) any global COMMON-role Config Set, named directly via `configKey` or
referenced in the Job's own base chain. There is no parameter that names a target Job, so this
isolation is structural, not a runtime check that could be bypassed.

## Jenkinsfile Map-parameter convention

A single Map built once and passed as the step's sole argument — `file` lives in the same map, not
appended separately:

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

`setupConfigChain(file:, useBase:, configKey:, version:, redeployFromRun:)` is a build-scoped
convenience alternative: call it once, and any later zero-argument (or partial) call to
`configChainValidate()`/`configChainSubstitute()` in the same build reads whichever parameter
it wasn't itself given from the stored state. Explicit call-site parameters always override stored
setup state, per parameter. `setupConfigChain` is rejected inside a `parallel {}` block (its
build-scoped state would be ambiguous across concurrently-running branches); the two other steps
remain fully safe inside `parallel {}` when called with their own full explicit parameters.

## Fail-loud paths

- **`configKey` without `useBase: true`:**
  `[configTemplateSync] 'configKey' ('<value>') is only valid together with useBase: true — remove
  'configKey', or add 'useBase: true' to this call.`
- **`configKey` names a Config Set that doesn't exist:**
  `[configTemplateSync] No global COMMON Config Set found for configKey '<value>'.`
- **`file` missing from both the call site and any prior `setupConfigChain` call:** an
  `AbortException` naming `file` as the missing required parameter.
- **Missing-key drift (fatal, `configChainValidate`):** `Missing config keys — target file
  '<file>' (Job='<jobFullName>', resolved via <resolution mode>) references <N> token(s) with no
  matching key in the effective configuration: <list>. Check for a typo in the token's dotted
  path, or add this key to the resolved source described above.`
- **Orphaned-key drift (non-fatal, warning only):** `Orphaned config keys — Job='<jobFullName>',
  resolved via <resolution mode>: the effective configuration has <N> key(s) with no matching token
  in target file '<file>': <list>. This is not a failure, but likely means either the key is
  genuinely unused or the token was removed from the file without removing the key.`
- **Unresolved token remains after substitution:** the substitute step fails loudly rather than
  leaving `#{...}#`-shaped text in the output file.
- **Missing secret credential:** the build fails loudly naming the credential ID; it never
  substitutes a placeholder or empty value.

## Build-pinning and reproducibility

On every successful real substitution, the plugin creates or updates a **Deployment Binding**,
keyed purely by the current Run's own identity (`run.getExternalizableId()`) — default-on, no
opt-in flag. The binding freezes the exact resolved base-chain version numbers and own-config
version used.

- **Same-Run replay:** a second (or later) real-substitution call within the *same* Run reuses that
  Run's already-written binding instead of re-resolving `ACTIVE`/`PINNED` references live —
  guaranteeing every call within one build sees the identical effective configuration, even if a
  base Config Set's active version changes mid-build.
- **`redeployFromRun` cross-Run replay:** `configChainSubstitute(..., redeployFromRun:
  <build-number-or-jobFullName#buildNumber>)` looks up the Deployment Binding of the **target**
  Run (not the current one) and substitutes using its frozen chain — byte-identical to the target
  Run's original output, even if a referenced base Config Set's active version has since changed.
  The current Run also gets its own fresh binding written, so a future rollback can chain forward
  and target it too. If no binding exists for the resolved target, the step falls back to the
  current Job's own live base chain and says so loudly in the build log, naming the unresolved
  value — it never aborts nor silently substitutes the target's current live-active config.
- An explicit `version` parameter always suppresses binding lookup/write for that call — a
  deliberate one-off version override must never corrupt a Run's own natural binding history.

## Substitution is single-pass

The target file is scanned once. Every `#{Path}#` token found in the file as it was read is replaced by its
value, and inserted text is never scanned again, even if a value itself looks like a token (for example a
secret that contains `#{Other.Path}#`). A value is always inserted literally. For ordinary configurations the
output is identical to earlier releases; the only difference is that a value shaped like a token is no longer
expanded a second time.

## File encoding

Both `configChainValidate` and `configChainSubstitute` read the target file as **UTF-8**, and
`configChainSubstitute` writes it back as UTF-8, regardless of the agent's platform charset. A file that is not
valid UTF-8 is not a new failure: the step logs a warning naming the file and falls back to the agent's default
charset, which is how earlier releases behaved. The optional `encoding` parameter (for example
`encoding: 'ISO-8859-1'`) overrides this: the named charset is used for reading and writing, with no fallback
and no warning, and an unknown name fails the build. A byte-order mark is neither added nor removed.

## Target files should be inside the workspace

`file` is expected to name a file inside the build workspace. If the resolved location (after `..` segments and
symbolic links) is outside it, the step writes a warning to the build log and continues; the build is not failed.

## Arrays are replaced wholesale

Config layers are combined with RFC 7396 Merge Patch. When an overlay (a job's own override, or a later base
chain entry) contains a JSON array, that array **replaces** the one below it as a whole; arrays are never merged
element by element. To change one element of an array, repeat the complete array in the overlay.

## Renamed steps and URLs

The steps were previously called `configTemplateValidate`, `configTemplateSubstitute` and
`setupConfigTemplate`. The old names still work as deprecated aliases with identical behavior and output, so
existing Jenkinsfiles and replays of older builds keep running; they appear in the Snippet Generator only under
advanced/deprecated entries. Use the new names in new Jenkinsfiles. The old `/configTemplates` page URLs redirect
(GET only) to `/configChains`. Build-log messages keep their `[configTemplateSync]` prefix.
