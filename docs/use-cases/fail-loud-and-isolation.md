# Fail-loud paths and isolation

[Use cases](README.md) · [Guide home](../README.md) · [Pipeline reference](../pipeline/README.md)

## UF-13 — `configKey` supplied without `useBase: true` fails loud (matrix row 3)

If you call with `configKey` set but `useBase` omitted or `false`, the build aborts immediately
rather than silently falling through to some other resolution.

*Example:*
```groovy
configChainSubstitute(file: 'app.json', configKey: 'unrelated-shared-config')
```
*Outcome:* aborts with `[configTemplateSync] 'configKey' ('unrelated-shared-config') is only valid
together with useBase: true — remove 'configKey', or add 'useBase: true' to this call.`
Demonstrated live by `config-template-sync-matrix-demo`, build #7 ("ROW3-FAILLOUD").

## UF-14 — `configKey` names a Config Set that doesn't exist as a global COMMON Config Set

If `configKey` was mistyped or never created as a COMMON Config Set, the call fails loud instead
of silently resolving nothing.

*Trigger:* `useBase: true, configKey: 'X'` where no global COMMON Config Set named `X` exists.

*Example:*
```groovy
configChainSubstitute(file: 'app.json', useBase: true, configKey: 'typo-name')
```
*Outcome:* aborts with `[configTemplateSync] No global COMMON Config Set found for configKey
'typo-name'.`

## UF-15 — A call can never reach another Job's own attached config

No parameter combination, on any of the three pipeline steps, can be coerced into naming a target
Job other than the one actually running the step.

*Example:* there is no parameter on any of the three pipeline steps that names a target Job.

*Outcome:* isolation is structural, not a bypassable runtime check — no code path accepts another
Job's identifier. Global COMMON Config Sets remain reachable by any Job via `useBase`+`configKey`
regardless of chain membership (see UF-11) — that is not an isolation violation, since a global
Config Set belongs to no single Job.

## UF-16 — Job has no attached config at all: valid, silent no-op (state 1)

A brand-new Job with nothing configured yet is a valid resolution target, not an error state.

*Trigger:* a resolution-matrix row targeting the Job's own config (rows 1/2/4/5) is called against
a Job with no config property at all, or zero saved versions.

*Example:*
```groovy
configChainValidate(file: 'app.json')  // on a brand-new Job with nothing configured yet
```
*Outcome:* the effective configuration is simply empty — a valid, non-error state. If `app.json`
has zero tokens, this is a silent no-op; if it has one or more tokens, the existing missing-keys
fail-loud path fires naming every such token.

