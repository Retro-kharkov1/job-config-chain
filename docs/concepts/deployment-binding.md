# Deployment Binding (build-pinned replay)

[Concepts](README.md) · [Pipeline parameters](../pipeline/parameters.md)

On every successful real substitution, the plugin creates or updates a **Deployment Binding**, keyed purely by
the current Run's own identity (`run.getExternalizableId()`) - default on, no opt-in flag. The binding freezes
the exact resolved base-chain version numbers and own-config version used.

- **Same-Run replay:** a second (or later) real-substitution call within the *same* Run reuses that Run's
  already-written binding instead of re-resolving active or pinned references live. Every call within one build
  therefore sees the identical effective configuration, even if a base Config Set's active version changes
  mid-build.
- **`redeployFromRun` cross-Run replay:** `configChainSubstitute(..., redeployFromRun:
  <build-number-or-jobFullName#buildNumber>)` looks up the Deployment Binding of the **target** Run (not the
  current one) and substitutes using its frozen chain, byte-identical to the target Run's original output, even
  if a referenced base Config Set's active version has since changed. The current Run also gets its own fresh
  binding, so a future rollback can chain forward and target it too. If no binding exists for the resolved
  target, the step falls back to the current job's own live base chain and says so loudly in the build log,
  naming the unresolved value; it never aborts and never silently substitutes the target's current live-active
  config.
- An explicit `version` parameter always suppresses binding lookup and write for that call, so a deliberate
  one-off version override never corrupts a Run's own natural binding history.

Scenarios: [Build-identity pinning](../use-cases/build-pinning.md).
