# Config Sets and versions

[Concepts](README.md) · [Base chain and override](base-chain-and-override.md)

A **Config Set** is a named, versioned configuration written in JSON, XML or YAML. The name is its **Config
Key** (for example `sample-app`). A *common* Config Set is global and is created on the Manage Jenkins page
([Manage Jenkins pages](../user-guide/manage-jenkins-pages.md)). A job additionally has its own Config Set, its
override, edited on the job page ([Job page](../user-guide/job-page.md)).

- **Content type.** JSON, XML or YAML, chosen once when the Config Set is first saved and locked afterwards.
  All entries of one base chain must share the content type.
- **Versions are immutable.** Saving never alters a prior version. Version numbers only grow and are never
  reused. Every version requires a change note and records its author and timestamp.
- **One active version.** Exactly one version is active at any time; activating one deactivates the previous
  one. A version can be saved and activated in one step (**Save & Activate**).
- **Rollback.** Activating a historical version only moves the active pointer; no content is duplicated and the
  full history stays visible and re-activatable. Activation never triggers a deploy by itself - the next
  pipeline run picks the active version up (see
  [UF-5](../use-cases/rollback.md#uf-5--roll-back-a-bad-config-change)).
- **No pruning.** Version history and Deployment Bindings are kept indefinitely, because auditability is the
  purpose of the plugin.
- **Syntax-only validation.** A save is blocked when the content is not syntactically valid for its type; no
  schema is applied.
- **Empty is valid.** A Config Set or job with no saved version is a normal starting state, not an error.

Comparing versions and activating them from the history table is described in [Version history, compare and
rollback](../user-guide/version-history-and-compare.md).
