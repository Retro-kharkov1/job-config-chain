# Secrets and credentials

[Concepts](README.md) · [Deployment Binding](deployment-binding.md)

A Config Set (and a job's own config) owns a **secrets manifest**: a list of dotted paths that hold secrets,
each bound to a Jenkins credential ID.

- **No real secret value is ever stored.** At a manifest path the stored content only ever holds the reserved
  `__SECRET__` placeholder; the editor and the save path refuse free text there.
- **Values are resolved at substitution time**, only from the bound credential, and are never written back into
  any stored content.
- **A missing credential fails the build** naming the credential ID; a placeholder or empty value is never
  substituted (see [Failure messages](../pipeline/failure-messages.md)).
- **A credential ID may back several paths or Config Sets**; reuse is allowed without a warning.
- Secret leaves appear in [Generate Template](../user-guide/generate-template.md) output as ordinary
  `#{Dotted.Path}#` tokens.

Binding a secret in the UI: [step 5 of Getting started](../getting-started/README.md) and
[Secrets manifest](../user-guide/secrets-manifest.md).
