# Resolution matrix

[Pipeline reference](README.md) · [Parameters](parameters.md)

| `useBase` | `configKey` | `version` | Resolves to |
|---|---|---|---|
| `false` | - | - | Job's own local config, ACTIVE version, full effective content (own override + its folded base chain) |
| `false` | - | `N` | Job's own local config, version `N`, full effective content of that version |
| `false` | given | any | **fail-loud**: `configKey` only valid together with `useBase: true` |
| `true` | - | - | Job's own local config, ACTIVE version's folded base chain only (own override content ignored) |
| `true` | - | `N` | Job's own local config, version `N`'s own recorded base chain folded (that version's own override ignored); unambiguous regardless of chain length |
| `true` | `X` | - | Global COMMON Config Set named `X`, its ACTIVE version: direct global lookup by name, NOT required to be present in the job's own base chain |
| `true` | `X` | `N` | Global COMMON Config Set named `X`, pinned to version `N` |

Each row is demonstrated in [Resolution-matrix rows](../use-cases/resolution-matrix-rows.md). The build console
names the row a call hit:

![Build console naming the resolution mode of a call](build-console-resolution-mode.png)
*A build console log naming exactly which resolution-matrix row a pipeline call hit.*

## Isolation rule

A call can **never** reach another job's own attached local config, by any parameter, under any composition. The
only things any call can reach are (a) the calling job's own local config, and (b) any global COMMON Config Set,
named directly via `configKey` or referenced in the job's own base chain. No parameter names a target job, so
this isolation is structural, not a runtime check that could be bypassed.
