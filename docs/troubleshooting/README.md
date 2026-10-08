# Troubleshooting

[Guide home](../README.md) · [Failure messages](../pipeline/failure-messages.md)

Symptom to cause to fix. The exact message texts are in [Failure messages](../pipeline/failure-messages.md).

## The build fails

| Symptom | Cause | Fix |
|---|---|---|
| `Missing config keys ...` from `configChainValidate` | The file has a `#{Path}#` token with no matching key in the effective configuration. | Fix the typo in the token, or add the key to the resolved source named in the message ([UF-23](../use-cases/drift-outcomes.md#uf-23--missing-key-drift-fails-the-build-naming-the-token-orphaned-key-drift-warns-without-failing)). |
| `'configKey' ... is only valid together with useBase: true` | `configKey` given without `useBase: true`. | Remove `configKey` or add `useBase: true` ([resolution matrix](../pipeline/resolution-matrix.md)). |
| `No global COMMON Config Set found for configKey` | The name is mistyped or no global Config Set has it. | Check the name on the [Config Chains list](../user-guide/manage-jenkins-pages.md). |
| `AbortException` naming `file` | No `file` at the call site and no earlier `setupConfigChain`. | Pass `file` ([Parameters](../pipeline/parameters.md)). |
| `setupConfigChain` rejected | It was called inside a `parallel {}` branch. | Call it before `parallel`, or pass full parameters to each step ([UF-21](../use-cases/setup-config-chain.md#uf-21--setupconfigchain-is-rejected-inside-a-parallel--block)). |
| Failure naming a credential ID | The bound Jenkins credential is missing. | Create it or rebind the path ([Secrets manifest](../user-guide/secrets-manifest.md)). |
| Unresolved token remains | A token has no value after substitution. | Run `configChainValidate` first and fix the drift. |

## The build warns

| Warning | Meaning |
|---|---|
| `Orphaned config keys` | The configuration has keys no token uses. Not a failure; remove the key or restore the token. |
| File is not valid UTF-8 | The step fell back to the agent's default charset. Set `encoding` ([File encoding](../pipeline/encoding.md)). |
| File is outside the workspace | `file` resolves outside the workspace. The build continues ([Substitution](../pipeline/substitution.md)). |
| `redeployFromRun` fell back to the live chain | No Deployment Binding exists for the target run ([Deployment Binding](../concepts/deployment-binding.md)). |

## The result is not what I expected

| Symptom | Explanation |
|---|---|
| A rolled-back version did not reach the server | Activation never deploys; run a new deploy ([UF-5](../use-cases/rollback.md#uf-5--roll-back-a-bad-config-change)). |
| An array has fewer elements than expected | Arrays are replaced wholesale ([Merging and arrays](../concepts/merging-and-arrays.md)). |
| A value that looks like a token stays in the output | Substitution is single-pass ([Substitution](../pipeline/substitution.md)). |

## A page is missing

| Symptom | Cause |
|---|---|
| No **Config Chains** page on a job | The page exists only on Pipeline jobs (including multibranch branch jobs), and only for users with Administer ([Job page](../user-guide/job-page.md)). |
