# Use cases

[Guide home](../README.md) · [Getting started](../getting-started/README.md) · [Pipeline reference](../pipeline/README.md)

Every scenario is a `when you need to X, do Y` walkthrough, grouped by theme. Each keeps its `UF-N` id for
traceability back to the use-case catalog and the plugin's own javadoc citations. Scenarios named "demo" refer
to jobs seeded by the test Jenkins in `docker/test-jenkins` (see
[its README](../../docker/test-jenkins/README.md)).

| Theme | Scenarios |
|---|---|
| [Authoring config: adding keys and getting the paste-ready template](authoring.md) | UF-1, UF-2 |
| [Deploying: validate then substitute](deploying.md) | UF-3, UF-4 |
| [Rollback: reverting config and replaying old builds](rollback.md) | UF-5, UF-6 |
| [Resolution-matrix rows — controlling exactly what a call resolves to](resolution-matrix-rows.md) | UF-7, UF-8, UF-9, UF-10, UF-11, UF-12 |
| [Fail-loud paths and isolation](fail-loud-and-isolation.md) | UF-13, UF-14, UF-15, UF-16 |
| [Build-identity pinning and reproducibility across runs](build-pinning.md) | UF-17, UF-18 |
| [`setupConfigChain` — declaring parameters once per build](setup-config-chain.md) | UF-19, UF-20, UF-21, UF-22 |
| [Drift outcomes in detail](drift-outcomes.md) | UF-23 |
