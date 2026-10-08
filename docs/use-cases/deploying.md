# Deploying: validate then substitute

[Use cases](README.md) · [Guide home](../README.md) · [Pipeline reference](../pipeline/README.md)

## UF-3 — Validate config drift before deploying

When your deploy pipeline needs to catch a broken/renamed token before it reaches a live deploy,
call `configChainValidate(file: 'app.json')` before any real substitution.

*Example:*
```groovy
configChainValidate(file: 'app.json')
```
If `app.json` contains `#{doesNotExist}#` with no matching key, the build fails naming
`doesNotExist`. If the effective configuration has an unused key, the build succeeds with a
`[WARN]` naming it — see [UF-23](drift-outcomes.md#uf-23--missing-key-drift-fails-the-build-naming-the-token-orphaned-key-drift-warns-without-failing)
for the exact wording of both outcomes and a real failure console.

*Outcome:* the pipeline proceeds only when no token is left unresolvable.

## UF-4 — Substitute real values at deploy time

Once validation passes, call `configChainSubstitute(file: 'app.json')`.

*Example:*
```groovy
configChainSubstitute(file: 'app.json')
```
*Outcome:* every `#{Path}#` token in `app.json` is replaced with its real value (secrets resolved
from their bound Jenkins credential, never from a stored placeholder), a Deployment Binding is
written recording exactly which versions were used, and the pipeline proceeds to deploy the
substituted file.

