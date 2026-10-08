# Authoring config: adding keys and getting the paste-ready template

[Use cases](README.md) · [Guide home](../README.md) · [Pipeline reference](../pipeline/README.md)

## UF-1 — Add a new config key

When you need to introduce a new setting (a feature flag, connection string, or per-Job override),
open the relevant Config Set (common or Job-scoped), add the key at the correct nested path, mark
it secret/non-secret (supplying a Jenkins credential ID if secret — never a real value), and save
a new version with a mandatory change note.

*Example:* open a Common Config Set → add `Feature.NewFlag: true` → save with change note "Add new
feature flag" → optionally activate immediately. Screenshot: [Common Config Set
editor](../user-guide/config-set-editor.png).

*Outcome:* a new, versioned snapshot exists; "Generate template" returns the same key rendered as
`#{Feature.NewFlag}#`, ready to paste into the app's config file.

## UF-2 — Get the exact template to paste into your app's config file

When an application developer needs the tokens for their config file, request "Generate template"
for the relevant scope and paste the returned block verbatim into the target file.

*Example:* "Generate template" on a Common Config Set returns
`{"Database":{"Host":"#{Database.Host}#"}}`, pasted directly into `appsettings.Production.json`.

*Outcome:* zero hand-typed tokens — the token text is copy-pasted from the system's own output,
eliminating typo drift.

