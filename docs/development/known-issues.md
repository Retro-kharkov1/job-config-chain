# Known issues

## Intermittent Jenkins-core console error on the env config page (observed 2026-09-03)

During real-browser verification of the FR-71-74/FR-89 base-chain UI redesign, a `TypeError:
Cannot read properties of undefined (reading 'replace')` was observed intermittently on the
`EnvConfigSetPage`, with this stack:

```
at xmlEscape (header.js:37) -> menuItem (header.js:173) -> generateDropdownItems (header.js:471)
-> utils.generateDropdown.trigger (header.js:713) -> createTippy (vendors.js:9937)
```

This is entirely inside Jenkins core's own bundled JS (breadcrumb/nav dropdown tooltip
generation) — not this plugin's code. A grep of this plugin's Jelly/JS for `.replace(` found no
matches. It did not reproduce on demand via direct hovering/clicking of the breadcrumb and did not
block any of the redesigned page's functionality (accordion, type-filtered picker, three-pane
merge, discard, `explicitlyStandalone` checkbox/badge all verified working).

Suspected but unconfirmed trigger: the plugin's entry point moved from `RootAction` to
`ManagementLink` (see `ConfigTemplatesRootAction`'s class javadoc), which changes what appears in
Jenkins' breadcrumb/Manage-Jenkins dropdown; a dropdown menu item populated from some extension
without an expected field could feed `undefined` into core's `xmlEscape`. Not root-caused further
since it is core-side and non-blocking — flagged here for future investigation if it recurs or
becomes reproducible.
