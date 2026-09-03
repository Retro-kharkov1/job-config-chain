# Wireframe — Config Templates Admin UI (pseudographic, spatial layout)

> Format: plain ASCII box-drawing, per the corrected `wireframe-before-visual-design` rule — a
> wireframe shows WHERE things sit on the page, not process/logic (that's `process-flow.md`,
> correctly Mermaid). No color, no styling — Jenkins renders its own look via Jelly/CSS; only layout
> and behavior matter here. Covers FR-30–FR-39 (`docs/business/requirements.md`) and the interaction
> decisions in `docs/development/tech-lead-ui-review-2026-08-27.md` (save=form-POST,
> activate/compare=AJAX, inline Monaco marker + banner, merge-preview keeps last-valid result,
> Common-Config-Set picker locked after creation). Structural principle from the Rojet reference
> (`docs/license-server/design/ui-12-one-editor.md`): one editor instance per concept, Compare is a
> MODE of that editor, never a second component; no field shown twice.
>
> **v2 addition (2026-09-01, `ux-ui-designer`):** placement for the "Generate Template" action
> (FR-40–FR-44). Placement reasoning:
> - **Global page** — "Generate Template" joins the existing `[ Editing ] [ Compare selected
>   versions ]` toggle row as a **third mode of the same single editor box**, exactly the structural
>   principle this file already established for Compare ("one editor instance per concept... never a
>   second component"). It is the least-disruptive placement available: zero new boxes, zero change
>   to the Save/Save & Activate row or the version-history table (FR-40's "MUST NOT alter or replace
>   the behavior of any existing button"), and it reuses a row the user already knows toggles the
>   same box's display mode.
> - **Env page** — deliberately **NOT** folded into the center override panel's own
>   `[ Editing ] [ Compare ]` row, even though that would mirror the Global page. Reason: FR-41
>   explicitly generates the merged template from the two Config Sets' ACTIVE versions, never from
>   the live "Merged result" panel's in-progress draft — but the "Merged result" panel sits two
>   columns away showing what LOOKS like the same thing from an unsaved buffer. Nesting "Generate
>   Template" inside the override panel's own mode row would visually suggest it's yet another mode
>   of that one panel, inviting exactly the active-vs-draft confusion FR-41/FR-16 call out by name.
>   Instead it is a **page-level action button**, positioned above the 3-panel table, and its output
>   **temporarily replaces the entire 3-panel table** with one full-width read-only panel (the
>   generated template spans the whole merged result, not one column) plus an explicit "← Back to
>   3-panel view" affordance — never a modal/overlay, which would hide the "this reads ACTIVE
>   versions, not what you're mid-editing" context the surrounding page provides.
> - **Output presentation (both pages)**, per FR-42: a read-only Monaco `language: 'json'`,
>   `readOnly: true` instance (reusing the exact editor engine already mandated by FR-32/36, not a
>   new component) plus a single "Copy to clipboard" button. No secret-highlighting, no download
>   button (both explicitly deferred). Disabled+labeled per FR-40 when no active version exists yet.
>
> **v3 addition summary (2026-09-01, superseded — see v4 below):** an earlier pass added a
> transient, non-persisted "Common Config Set version" preview selector (then-FR-48–FR-50) to the
> env page. That selector is now superseded (see the v4 note ahead of Page 2's edit view) by the
> persisted, per-version **base-chain editor** (FR-55–FR-58) — kept here only as a pointer so a
> reader scanning this header knows where the current design lives.
>
> **v4 addition (2026-09-01, `ux-ui-designer` — multi-base config chains):** the env page's static
> "Overrides Common Config Set: […] locked" text and the superseded FR-48 selector are both replaced
> by an ordered, repeatable, reorderable **base-chain editor** (FR-55–FR-58), and the former single
> "Global (read-only)" panel becomes "Effective base (read-only, collapsed)" with an "Inspect chain"
> drawer. Full detail, the collapsed-vs-stacked-panels decision and its justification, and the new
> mobile/tablet/desktop responsive rule for the base-chain editor are all in Page 2's edit-view
> section below.
>
> **v6 addition (2026-09-03, `ux-ui-designer` — base-chain visualization redesign, replaces the v4
> collapsed-panel + drawer design, FR-71–FR-74):** a real-browser audit found the "Effective base
> (read-only, collapsed)" panel introduced in v4 was **dead code** — `EnvConfigSetPage/index.jelly`'s
> `#globalEditor` div is a static placeholder string that `recomputeMerge()` never writes into (only
> `#mergedEditor` gets live content), so the panel never actually showed the folded base chain despite
> the v4 spec saying it would. The owner has now given a fuller redesign, replacing this area (and the
> "Inspect chain" drawer built on top of it) outright rather than patching the bug in place:
> 1. **Base-chain rows become their own nested accordion.** Each `BaseConfigReference` row in the
>    base-chain editor (FR-55) is now itself a collapsible accordion item. Expanding one shows a
>    READ-ONLY view of that specific reference's own resolved content (whichever version it currently
>    resolves to — active or pinned) — the same per-reference inspection the old "Inspect chain" drawer
>    offered, but always-correct because it is driven by the identical live `doComputeMerge`/
>    `previewMerge` response the rest of this page already recomputes from (FR-71), not a second stale
>    code path. Reordering/add/remove controls stay available regardless of any row's expand state,
>    since order remains merge-significant (FR-8). This single change is what actually fixes the dead
>    "Effective base" panel — there is no longer a static-placeholder panel of any kind anywhere on
>    this page.
> 2. **Editor layout changes from 3 side-by-side columns to a 2-up-then-1-full-width arrangement.**
>    LEFT = "Merged bases" (read-only, live fold of the full draft chain — this is what "Effective
>    base" was supposed to be, now genuinely live) | RIGHT = "Env override" (the only editable panel,
>    unchanged role) sit in a top row of two columns; BELOW them, full-width, sits "Merged result"
>    (read-only, live = Merged bases ⊕ Env override draft) (FR-73). See the placement-decision note
>    ahead of the new diagram for the grid reasoning and the responsive rule.
> 3. **A new "Discard all changes" action** reverts both the Env-override draft and the base-chain
>    editor's draft rows back to the last-saved state in one action (FR-74), sitting alongside Save/
>    Save & Activate.
> 4. **The base-chain project picker now proactively filters by content type**, not only rejecting a
>    mismatch at Save time (FR-72, additive to FR-61's unchanged hard rejection) — see the placement
>    decision note ahead of the base-chain diagram.
>
> The superseded v4 text (collapsed "Effective base" panel + "Inspect chain" drawer + the original
> 3-column table) is removed below rather than kept as dead history, since it described a design that
> was never correctly implemented in the first place (unlike the FR-48–FR-50 supersession above, which
> did ship and needed a paper trail) — the bug report is preserved in this note instead.

> **v5 addition (2026-09-02, `ux-ui-designer` — multi-format Config content JSON/XML/YAML,
> FR-59–FR-70, NFR-9):** grounded against the CURRENT shipped accordion Jelly (`CommonConfigSetPage/
> index.jelly`, `ConfigTemplatesRootAction/index.jelly`, `EnvConfigSetPage/index.jelly`), not the
> pre-accordion flat layout. Four placement decisions, all inside the existing accordion structure
> (`<details>`/`<summary>`: Secrets manifest, Version history, Editor):
> - **Root list page (FR-67)** — new `Type` column inserted immediately after `Name`, before `Active
>   version`: `Name | Type | Active version | Last modified`. See Page 1 — list view below.
> - **Content-type picker (FR-59, FR-68)** — lives on the COMMON page's "Editor" `<details>` section
>   (the only place the save form exists), as its own labeled row directly below the existing
>   `[ Editing ] [ Generate Template ]` mode-toggle row and above the Monaco editor box — NOT inside
>   the `ctsync-editor-header-row` (Save/Save & Activate would crowd it) and NOT folded into the
>   "Change note" form row (both are one-line f:entry fields today, and the picker needs to be
>   visually adjacent to the editor it configures, not buried below it). Reasoning: the picker
>   determines the Monaco editor's `language` mode (json/xml/yaml) and which diagnostics/format
>   provider attaches to it (FR-63/FR-64) — it must sit immediately above the box it configures, the
>   same "control governs the thing right below it" relationship the mode-toggle row already has with
>   the editor. Visible+enabled only while `it.exists == false` (`getConfigSet() == null`, zero saved
>   versions, mirrors the page's own existing `<j:if test="${!it.exists}">` "does not exist yet"
>   banner); becomes a disabled/read-only display of the committed value the moment a first version
>   exists — same pattern FR-35's now-superseded "locked" picker used. See the updated Global Config
>   Set edit view below.
> - **XML/YAML diagnostics + auto-format (FR-63/FR-64)** — need **zero new UI chrome** beyond what
>   Monaco already renders natively for JSON today (inline squiggle + gutter dot at the offending
>   line/column, plus the existing `saveBanner` fallback) — both pages' editor boxes already have
>   this exact behavior wired for JSON; XML (client-side `DOMParser`) and YAML (server round-trip)
>   just become two more `setModelMarkers` producers feeding the identical rendering path. **One
>   exception, my call:** YAML's debounced server round-trip (unlike XML's synchronous
>   `DOMParser`) has a real, human-perceptible gap between "user stopped typing" and "diagnostics
>   updated" — worth a small, subtle, non-blocking affordance so the operator isn't misreading
>   "no squiggle yet" as "valid," reusing the exact visual language the env page's Merged-result
>   panel already established for its own debounced recompute ("keeps showing the last valid
>   result + a small non-blocking indicator, never blanks"). See the annotated editor box below.
> - **Cross-chain type mismatch (FR-61)** — confirmed: no new UI chrome. Surfaces through the
>   env page's existing `saveBanner` mechanism (the same one already showing "Save blocked: invalid
>   JSON…" today), with message shape `Save blocked: mismatched content types in base chain —
>   <projectKey> (<TYPE>), <projectKey> (<TYPE>)[, …] must all share one content type` (FR-61's "naming
>   every conflicting project and its ContentType" restated as literal banner text). See the
>   base-chain editor section below.

---

## Page 1 — Global "Config Templates" — list view (FR-30)

```
┌─ Manage Jenkins ▸ Config Templates ──────────────────────────────────────────────┐
│                                                                                    │
│  Config Templates                                          [ + New Config Set ]  │
│                                                                                    │
│  ┌─ Name ────────────────┬─ Type ─┬─ Active version ─┬─ Last modified ──────────┐ │
│  │ apilealtad-common      │ JSON   │ v7                │ 2026-08-20 by ib       │ │
│  │ andatti-common         │ XML    │ v3                │ 2026-07-11 by ib       │ │
│  │ shared-secrets-common  │ YAML   │ v1                │ 2026-09-01 by ib       │ │
│  └────────────────────────┴────────┴───────────────────┴────────────────────────┘ │
│    (click a row -> opens the edit page below)                                    │
└────────────────────────────────────────────────────────────────────────────────────┘
```

Behavior: table only, one action ("New"), each row opens the same edit page used for creation —
same interaction shape as Jenkins' own Manage Jenkins → Managed Files list. **New `Type` column
(FR-67):** a plain read-only text cell (`JSON`/`XML`/`YAML`), reading `cs.contentType` — the value
already frozen at that Config Set's first save (FR-59); no icon/badge/color, consistent with every
other column on this table being plain text. Position is fixed at `Name | Type | Active version |
Last modified`, matching the plan's own column order exactly (`docs/development/
multi-format-content-plan-2026-09-02.md`).

---

## Page 1 — Global Config Set edit view (FR-31, FR-32, FR-33)

```
┌─ Config Templates ▸ apilealtad-common ────────────────────────────────────┐
│                                                                            │
│  [ if invalid content: ⚠ Save blocked: invalid XML — see marker below ]   │
│                                                                            │
│  ┌─ Editor ────────────────────────────────────────  [Save] [Save&Act.] ┐│
│  │  [ ● Edit ]  [ ○ Compare selected versions ]  [ ○ Generate Template ] ││
│  │                                                                       ││
│  │  Content type:  ( ● JSON )  ( ○ XML )  ( ○ YAML )    ← FR-59/FR-68,  ││
│  │  ⓘ choose once — locked forever after the first Save    see note    ││
│  │                                                                       ││
│  │  1 │ {                                                             │ ││
│  │  2 │   "database": {                                               │ ││
│  │  3 │     "host": "db.internal.local",           ← Monaco, language │ ││
│  │  4 │     "password": "__SECRET__"          ⚠ ← follows the picker  │ ││
│  │  5 │   }                                     above (json/xml/yaml) │ ││
│  │  6 │ }                                        inline error marker  │ ││
│  │    │                                          at offending line    │ ││
│  └────────────────────────────────────────────────────────────────────┘  │
│  (^ SAME box switches to Monaco diff mode when a history row is          │
│     clicked below, and to a THIRD read-only mode when "Generate          │
│     Template" is clicked — never a second editor)                        │
│                                                                            │
│  Change note (required): [__________________________________]           │
│                                                                            │
│  ┌─ Version history ──────────────────────────────────────────────────┐  │
│  │ [ ] v7  2026-08-20  ib  ● active    [Activate]  [select→compare]   │  │
│  │ [ ] v6  2026-08-18  ib  —           [Activate]  [select→compare]   │  │
│  │ [ ] v5  2026-08-10  ib  —           [Activate]  [select→compare]   │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

**Content-type picker, after the first version exists (FR-59, FR-68) — same row, disabled/read-only:**

```
│  Content type:  JSON  🔒 (locked — set at first Save, immutable)          │
```

Behavior:
- Exactly ONE editor box on this page. "Compare" and "Generate Template" are both modes on that
  same box (Monaco diff mode / read-only generated-template mode), not a second box.
- Save = classic form POST → returns to the list page. Activate/Compare/Generate Template = AJAX,
  updates this page in place (row highlights change, editor swaps mode) — no page reload.
- Save button is disabled while the content fails to parse for its declared type; the exact parse
  error also gets an inline Monaco marker (squiggle + gutter dot) at its line/column, plus the
  one-line banner at the top of the page as a fallback in case the error line has scrolled out of
  view — unchanged behavior, now dispatching through `validateSyntaxOrFail(content, contentType)`
  (FR-65) instead of a JSON-only parser.
- `"__SECRET__"` is a literal marker in the content — the real value never appears here (resolved
  only at deploy-time substitution from a Jenkins credential). For XML this renders as element/
  attribute text (e.g. `<password>__SECRET__</password>`); for YAML as a scalar
  (`password: __SECRET__`) — same placeholder string, format-appropriate leaf syntax.
- **Content-type picker (FR-59, FR-68), placement decision:** a row of three radio options (or an
  equivalent `<select>` — implementer's call, radios drawn here since there are exactly 3 fixed
  options and no search/scroll need) sitting directly below the `[ Edit ] [ Compare ] [ Generate
  Template ]` mode-toggle row and directly above the Monaco box, inside the same "Editor" `<details>`
  section (`editorDetails` in the shipped Jelly) — never inside the Secrets manifest or Version
  history sections, since it's inseparable from the editor it configures. Reasoning: (1) it drives
  the Monaco `language` mode and which diagnostics/format provider attaches (FR-63/FR-64), so it
  must sit immediately above the box it configures — the same visual relationship the mode-toggle
  row already has with the editor; (2) it must NOT sit inside the `ctsync-editor-header-row` next to
  Save/Save & Activate, which would visually suggest it's submitted independently of the rest of the
  draft rather than being one more field on the same save; (3) it must NOT be folded into the
  "Change note" form row below the editor, since a user should decide the type before typing content,
  not after. **Enabled only while `it.exists == false`** (the page's own existing "This Config Set
  does not exist yet — Save creates it" banner condition, `getConfigSet() == null`, zero saved
  versions) — the instant a first version exists, this same row switches to the disabled/read-only
  display shown above (mirrors FR-35's original "locked" picker convention already used on the env
  page pre-base-chain). Changing the radio/select while enabled MUST re-create the Monaco editor
  instance with the newly chosen `language` (json/xml/yaml) so diagnostics/format match what will
  actually be validated on Save — an implementation detail worth stating here since it's easy to
  build a picker that only affects the submitted field and silently leaves the editor's language
  stale.

**XML/YAML diagnostics + format (FR-63, FR-64) — zero new UI chrome, one exception:** both formats
reuse the identical inline-marker rendering the JSON editor already has today (squiggle + gutter dot
at the offending line/column, `saveBanner` as the scrolled-out-of-view fallback) — XML's client-side
`DOMParser` check and YAML's server round-trip both just call `monaco.editor.setModelMarkers` the
same way the existing JSON diagnostics do. The one addition, for YAML only, because its diagnostics
round-trip the server with a debounce (unlike XML's synchronous in-browser check):

```
│  Content type:  ( ○ JSON ) ( ○ XML ) ( ● YAML )        ⏳ Checking YAML…   │
│  1 │ database:                                                            │
│  2 │   host: db.internal.local                                            │
│  3 │   password: __SECRET__                                               │
```

- `⏳ Checking YAML…` is a small, muted, non-blocking text/spinner pair appearing to the right of the
  Content-type row, visible ONLY during the debounce window between "user stopped typing" and the
  `doValidateContent` response landing — same "last valid state retained + small non-blocking
  indicator, never blanks" visual language already established for the env page's Merged-result
  panel (see States summary table). It disappears the instant the response lands, whether or not it
  found errors (errors then render as the normal inline squiggle/banner). **My call, with reasoning
  stated per this task's instructions:** worth building, because YAML is the one format whose
  diagnostics genuinely lag typing by a network round-trip — without this, a user who types malformed
  YAML and immediately looks for a squiggle that hasn't arrived yet could misread "no squiggle" as
  "valid so far" for a few hundred ms, which is a real (if small) UX foot-gun the other two formats
  don't have (JSON's diagnostics are synchronous/instant; XML's `DOMParser` check is also
  synchronous, just in-browser). It costs one small span of markup and reuses an interaction pattern
  this codebase already has, so the cost is low relative to closing that gap. Applies identically on
  the env page's own editor panel (same debounce mechanism, same placement convention: directly above
  whichever Monaco box is doing the typing).
- "Format document" (FR-64) needs no new UI at all for any of the three formats — it is invoked the
  same way JSON's `editor.action.formatDocument` already is today (on load, via the existing
  `formatEditorContent()` retry-until-changed helper, which already tolerates a silent multi-attempt
  loop with zero visible affordance for JSON). XML/YAML's custom
  `registerDocumentFormattingEditProvider` round-trips the same debounced server call as diagnostics,
  but since formatting is already silent/best-effort for JSON today, there is no inconsistency in
  keeping it equally silent for the other two formats — no "formatting…" indicator is proposed.

**"Generate Template" mode (FR-15a, FR-40, FR-42, FR-44)** — same box, third toggle state:

```
┌─ Editor  [ ○ Edit ]  [ ○ Compare selected versions ]  [ ● Generate Template ] ──┐
│  ⓘ Common-only template from ACTIVE v7 — NOT this box's unsaved edits, if any   │
│                                                                                  │
│  1 │ {                                                                          │
│  2 │   "database": {                                                           │
│  3 │     "host": "#{Database.Host}#",                                          │
│  4 │     "password": "#{Database.Password}#"      ← identical token shape,     │
│  5 │   }                                             secret or not (FR-42)     │
│  6 │ }                                                                          │
│                                                                                  │
│  [ 📋 Copy to clipboard ]                        (read-only Monaco, no cursor  │
│                                                    editing — FR-42)             │
└──────────────────────────────────────────────────────────────────────────────┘
```

Behavior:
- Clicking "Generate Template" calls a `doGenerateTemplate`/`generateTemplate` JS-proxy pair
  (FR-44's two-method-pair shape, mirroring `doActivateVersion`/`activate`), swaps the box into a
  read-only Monaco instance (`readOnly: true`) seeded with the walked-and-tokenized ACTIVE version
  content, and shows the inline banner above the box stating it reflects the ACTIVE version, not any
  unsaved edit currently sitting in "Edit" mode (FR-16).
- "Copy to clipboard" uses the standard browser Clipboard API and copies the exact editor text —
  no added marker/comment on secret-bound lines, since JSON has no comment syntax and any marker
  would break paste-ready validity (FR-42).
- Clicking "Edit" (or "Compare") from this mode returns the box to its normal state; no explicit
  "back" button is needed beyond the existing toggle row, consistent with how Compare already
  returns to Edit today.
- The "Generate Template" toggle button itself is disabled with the label "no active version yet"
  when the Config Set has no active version (FR-40) — it never silently no-ops on click.

---

> **v3 addition (2026-09-01, `ux-ui-designer`):** the Env Config Set edit view below is redrawn to
> (a) document the section order the page's Jelly view already implements — title/description →
> env-layer version history → editor section (3-panel merge, Save/Save & Activate inline with the
> section heading, same convention as the Global page) → Secrets manifest — bringing this file back
> in sync with the actual implementation, since the previous revision of this diagram had drifted
> (it showed the version-history table at the bottom, which no longer matches the shipped page), and
> (b) add the **Common Config Set version selector** the original plan
> (`C:\Users\retro\.claude\plans\jenkins-woolly-dragonfly.md`) called for and which was never
> implemented. **Interpretation of "config version dropdown," stated explicitly for owner
> confirmation:** the URL route `/configTemplates/<project>/<env>/` already fixes WHICH common
> Config Set this env layer pairs with (one common Config Set per project, OQ-2) — that pairing has
> its own, separate, locked-after-creation picker (`Overrides Common Config Set: […] 🔒`, FR-35),
> unchanged here. The dropdown this pass adds is a **different** control: it selects WHICH VERSION
> of that already-paired common Config Set the Global (left) and Merged result (right) panels
> preview — a read-only comparison aid, not a second pairing mechanism and not a way to change what
> Save/Save & Activate/Generate Template actually operate against (those always use the common
> Config Set's true ACTIVE version, per FR-16 — see FR-48–FR-50 below). If this is not the intended
> reading of "config version dropdown," flag it before `tech-lead`/implementation proceeds.

> **v4 addition (2026-09-01, `ux-ui-designer` — multi-base config chains, FR-55–FR-58, SUPERSEDED by
> v6 — see the note ahead of Page 1's list view above):** the v3 selector immediately above
> (**"Common Config Set version:"**) is superseded, per `docs/business/requirements.md`'s FR-48–FR-50
> supersession note — it described a transient, non-persisted preview toggle that no longer matches
> the owner-approved, persisted base-chain model. The single "Overrides Common Config Set: […] 🔒
> locked" picker AND the FR-48 version selector are BOTH replaced below by one **ordered, repeatable,
> reorderable base-chain editor** (FR-55). This original pass's own left-panel proposal (a collapsed
> "Effective base" panel + an "Inspect chain" drawer) is what a real-browser audit later found was
> never actually wired to live data — see the v6 note for the fix, applied in the diagrams below.

> **v5 flag (2026-09-02, `ux-ui-designer` — multi-format Config content):** FR-67 mandates the new
> `Type` column ONLY on the root "Config Templates" list page (Page 1). This env-level list page
> below is deliberately left WITHOUT a `Type` column here — an ENV Config Set has no own
> `ContentType` field to read (FR-60: it always resolves one live, by reading its base chain), so a
> column here would need a resolve-the-chain call per row just to render a list, not a plain field
> read like Page 1's column is. This is a scope decision worth flagging to `tech-lead`/
> `business-analyst`, not a silent omission: if operators want inherited-type visibility at this list
> level too, it is a small follow-up FR (a resolved, not stored, `Type` column here) rather than
> something this wireframe pass should invent unasked.

---

## Page 2 — Env level — list view (FR-34)

```
┌─ apilealtad ▸ dev ▸ Env Config Sets ──────────────────────────────────────┐
│                                                    [ + New Env Config Set ]│
│  ┌─ Name ──────────────┬─ Active version ─┬─ Last modified ──────────┐   │
│  │ apilealtad-dev        │ v4                │ 2026-08-19 by ib       │   │
│  └───────────────────────┴───────────────────┴─────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

Identical shape to the global list, scoped to one (project, environment) pair.

---

## Page 2 — Env Config Set edit — nested-accordion base-chain editor + Merged-bases/Env-override/
Merged-result layout (FR-35\*, FR-36\*, FR-37, FR-38\*, FR-39, FR-51–FR-58, FR-71–FR-74)

\* FR-35/36/38 are amended in place by FR-55/57/73 (base chain + the new layout replace the single
common-Config-Set pairing/preview and the old 3-column table) — cross-referenced, not restated
where already covered above.

Section order (matches the Global page's now-corrected convention — title/description → version
history → editor section, Save/Save & Activate inline with the heading → the editor surface →
Secrets manifest; the env page's structural addition is the **nested-accordion base-chain editor**
where the old locked picker used to sit):

**Base-chain editor — each row is its own accordion item (FR-55, FR-71).** Row #1 in the chain
pins this chain's content-type context; every subsequent row's project picker is filtered to only
list COMMON Config Sets sharing that resolved type (FR-72 — additive UX on top of FR-61's unchanged
`doSave`-time hard rejection, which stays as the defense-in-depth backstop for any caller that
bypasses this picker):

```
┌─ apilealtad ▸ dev ▸ apilealtad-dev ──────────────────────────────────────────────────┐
│                                                                                        │
│  Base chain (ordered — later rows win on overlapping keys, folded left→right, FR-8/    │
│  FR-55). Any COMMON-role Config Set from ANY project may be referenced (NFR-8). Row #1's│
│  project sets this chain's content type — later rows' project pickers only offer       │
│  matching-type COMMON Config Sets (FR-72).                                             │
│  ┌────────────────────────────────────────────────────────────────────────────────────┐│
│  │ ▸ #1  [ apilealtad-common          ▾ ]   ●Active  ○Pin                    ▲ ▼ ✕    ││ ← collapsed
│  ├────────────────────────────────────────────────────────────────────────────────────┤│
│  │ ▾ #2  [ shared-secrets-common       ▾ ]  ○Active  ●Pin  [ v3 · 2026-08-01 ·        ]││ ← expanded
│  │        (filtered: JSON-typed COMMON Config Sets only)     "freeze billing keys" ▾ ]  ││
│  │                                                                             ▲ ▼ ✕    ││
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐  ││
│  │  │ Resolved content (read-only) — shared-secrets-common, PINNED v3               │  ││
│  │  │ {                                                                             │  ││
│  │  │   "billing": { "apiKey": "__SECRET__" }                                       │  ││
│  │  │ }                                                                             │  ││
│  │  │ (this is THIS row's own individually-resolved content, live — recomputed from │  ││
│  │  │  the same doComputeMerge/previewMerge response feeding Merged bases below,    │  ││
│  │  │  never a static/cached copy — this is what fixes the old dead "Effective      │  ││
│  │  │  base" panel, FR-71)                                                          │  ││
│  │  └──────────────────────────────────────────────────────────────────────────────┘  ││
│  └────────────────────────────────────────────────────────────────────────────────────┘│
│  [ + Add base ]                                                                        │
│  ⓘ Row 2 overwrites row 1's overlapping keys; this env's own override (right panel     │
│    below) is applied LAST, on top of the fold (FR-8)                                   │
```

**Content-type context source, decision stated explicitly:** the FIRST row's project — always
present, since FR-56 guarantees a brand-new chain already defaults to one row (this env's own
project, Active) — establishes the type every later row's picker filters against, read from that
row's resolved COMMON Config Set's own already-immutable `ContentType` (FR-59). Reasoning: (1) it
needs zero new UI control — no separate "pick a content type for this chain" selector before any
rows exist, since a row always already exists (FR-56); (2) it matches the existing "control governs
the thing after/below it" convention this page's own row-ordering already uses (later rows read
context from earlier ones, never the reverse); (3) changing row #1's project re-establishes the
type context and re-filters every later row's options live, client-side, with FR-61's `doSave`
check remaining the authoritative backstop if a row was ever populated before this filtering
existed (e.g. via Script Console) or if a race lets a stale option slip through.

```
│                                                                                        │
│  Env override version history (independent of Global's own history)                  │
│  ┌────────────┬────────────────┬───────┬───────┬─────────┬─────────────┬──────────┐  │
│  │ Version    │ Created        │ By    │ Note  │ Active  │ Base chain  │ Actions  │  │
│  ├────────────┼────────────────┼───────┼───────┼─────────┼─────────────┼──────────┤  │
│  │ v4         │ 2026-08-19     │ ib    │ ...   │●Active  │[▸ 2 bases]  │[Activate]│  │ ← disabled
│  │ v3         │ 2026-08-12     │ ib    │ ...   │Inactive │[▸ 1 base]   │[Activate]│  │
│  └────────────┴────────────────┴───────┴───────┴─────────┴─────────────┴──────────┘  │
│  (row click selects it → Env-override panel below switches to Compare mode;           │
│   clicking [▸ N bases] instead expands that version's own frozen chain, read-only,    │
│   in an inline detail row — see the expanded example after this diagram, FR-58)       │
│                                                                                        │
│  Editor                                              [ Generate Template ]           │
│  (page-level action, top-right — NOT a mode of any one panel below, unchanged         │
│   reasoning from the earlier annotation in this document)                            │
│                                                                                        │
│  ┌─ Merged bases (read-only, live) ─────────────┬─ Env override ──────────────────────┐│
│  │  fold of every accordion row above,          │  (EDIT — the only editable panel    ││
│  │  left→right, BEFORE this env's own           │  on this page)                       ││
│  │  override is applied (FR-57, FR-73) —         │                                      ││
│  │  this panel REPLACES the old dead            │  Save  [Save & Activate]             ││
│  │  "Effective base" panel                       │  [Discard all changes]  (FR-74)      ││
│  │                                               │                                      ││
│  │ {                                             │ {                                    ││
│  │  "database": {                                │  "database": { "host": null    ←rm  ││
│  │   "host": "db.internal.local",                │  },                                  ││
│  │   "port": 5432                                │  "featureFlags": {                   ││
│  │  },                                            │   "newCheckout": true                ││
│  │  "billing": {                                 │  }                                   ││
│  │   "apiKey":"__SECRET__"                       │ }                                    ││
│  │  }                                            │                                      ││
│  │ }                                              │ ℹ Comparing v3  [Load into editor]   ││
│  │                                                │  [Back to editing] (Compare mode     ││
│  │ (recomputed live on every base-chain OR       │   only)                              ││
│  │  override edit, incl. unsaved add/remove/     │                                      ││
│  │  reorder — never stale/static, FR-57/FR-73)   │ (sparse overlay only — not a full    ││
│  │                                                │  copy of the merged bases)           ││
│  └───────────────────────────────────────────────┴──────────────────────────────────────┘│
│                                                                                        │
│  ┌─ Merged result (read-only, live; = Merged bases ⊕ current override draft) ────────┐ │
│  │ {                                                                                   │ │
│  │  "database": { "port": 5432, "password": "__SECRET__" },                          │ │
│  │  "featureFlags": { "newCheckout": true },                                         │ │
│  │  "billing": { "apiKey": "__SECRET__" }                                            │ │
│  │ }                                                                                   │ │
│  │ [i] showing last VALID merge while you type an incomplete edit; recomputed        │ │
│  │  whenever EITHER the override OR the base-chain editor above changes (FR-57)      │ │
│  └─────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                        │
│  Change note (required): [__________________________________]                        │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

**Layout-arrangement decision, stated explicitly (FR-73):** two columns on top (Merged bases |
Env override), one full-width row below (Merged result) — not the prior 3-column side-by-side
table. Reasoning:
1. This is exactly the owner's stated LEFT/RIGHT/BELOW structure, and it reads correctly as data
   flow: the two TOP panels are the two INPUTS being combined (the folded base chain on the left,
   this env's own override on the right), and the panel BELOW is their OUTPUT — a visual grammar a
   3-way side-by-side row never expressed (all three read as peers there, even though Merged result
   is actually derived from the other two).
2. It gives the Env-override panel — the only editable one, and the one operators spend the most
   time in — a full half of the available width instead of one-third, a real usability win once the
   old dead "Effective base" panel's placeholder is replaced with a genuinely useful live Merged-bases
   panel worth looking at (an operator now has a real reason to want both top panels visible at a
   readable width, not just the override editor).
3. Reduces to the tablet/mobile stacking rule this page already uses (top-to-bottom: Merged bases,
   Env override, Merged result) with one fewer breakpoint-specific column-count decision than a
   3-column table needs, since 2-then-1 collapses to 3-stacked directly with no intermediate state.

**Discard all changes (FR-74):** a new action next to Save/Save & Activate. Clicking it (after a
confirm prompt, since it is destructive to any unsaved work) reverts BOTH the Env-override editor's
draft content AND the base-chain accordion's draft rows back to the currently-loaded saved version's
content and `baseChain` — in place, no page reload — closing the gap where a partially-reordered/
edited chain plus a half-typed override had no single "start over" affordance short of navigating
away and losing the change-note field too.

**Env version-history row expanded (FR-58, read-only, does not open any editor) — unchanged from the
prior pass:**

```
│  ├────────────┼────────────────┼───────┼───────┼─────────┼─────────────┼──────────┤│
│  │  ▾ v4's frozen base chain (read-only — recorded at that version's own save time,│
│  │    per FR-51; not editable here — editing only happens in the live editor above,│
│  │    then saved as a NEW version):                                               │
│  │    1. apilealtad-common — ACTIVE (resolved to v7 at that save)                 │
│  │    2. shared-secrets-common — PINNED v3 ("freeze billing keys")                │
│  └──────────────────────────────────────────────────────────────────────────────────┘│
```

**Multi-format additions on this page (FR-60–FR-66) — zero new panels/columns, confirmed:**
- **Env override panel (the only editable Monaco on this page)** gets the identical diagnostics/
  format/YAML-checking treatment as the Global page's editor (see "XML/YAML diagnostics + format"
  above) — its `language` mode is never independently chosen here (FR-60: an ENV Config Set has no
  own picker), it's read off the resolved base chain's shared `ContentType`. The same
  `⏳ Checking YAML…` small indicator, when applicable, sits directly above this panel only (not
  Merged bases or Merged result, already covered by the "last valid + non-blocking indicator"
  convention this page established for its own recompute debounce).
- **Cross-chain content-type mismatch (FR-61)** — confirmed no new UI chrome. On `doSave`, if the
  resolved base-chain rows don't all share one `ContentType`, the save is blocked and the EXISTING
  `saveBanner` (the same element already rendering "Save blocked: invalid JSON…" today) shows:
  `Save blocked: mismatched content types in base chain — apilealtad-common (JSON), shared-secrets-
  common (XML) must all share one content type.` — naming every conflicting `projectKey` + its
  `ContentType`, per FR-61's literal wording. This is the SAME banner element, not a second one;
  fires from the same `doSave` call path the JSON-syntax error banner already uses. FR-72's picker
  filtering is a proactive UX layer on top of this same rule, not a replacement of it.
- **Merged bases / per-accordion-row / Merged result panels' Monaco instances** — `language` mode
  also follows the resolved chain's shared `ContentType` (they render the same content type as the
  Env-override panel, since a mismatched chain is rejected before it could ever reach these panels
  showing two formats at once).

**Responsive behavior (mobile ≤480px / tablet ≤1100px / desktop, continuing this page's existing
stacking convention):**

- **Desktop (>1100px):** exactly as drawn above — base-chain accordion items full-width, Merged
  bases | Env override side by side on top, Merged result full-width below.
- **Tablet (≤1100px):** all three panels stack vertically in order — Merged bases → Env override →
  Merged result — each full-width (the 2-then-1 top row collapses directly into the same 3-stacked
  order it would have needed anyway at mobile width, so there is no separate tablet-only column
  arrangement to define). The base-chain accordion's per-row controls stay on one line per row (it
  fits — project picker, mode toggle, pinned-version picker, reorder/remove).
- **Mobile (≤480px):** each base-chain accordion row's collapsed header stacks its own controls
  vertically (project picker, then mode toggle, then pinned-version picker when Pin is active, then
  the ▲▼✕ controls on one row beneath) before its own expand/collapse toggle — the same "row becomes
  a card" pattern used for the version-history table at this width; the expanded read-only content
  view (when open) renders full-width beneath that card, unchanged in content, just narrower.

Behavior:
- **Panel order, fixed**: Merged bases (read-only, live, top-left on desktop/tablet-first) | Env
  override (the ONLY editable panel on this page, top-right on desktop) — both above — Merged result
  (read-only, live, full-width below) (FR-73).
- The base-chain editor (FR-55, FR-71) replaces both the old locked "Overrides Common Config Set"
  picker and the superseded FR-48 version-preview selector. Each row is its own accordion item: a
  project picker (filtered per FR-72 for every row after the first), an Active/Pin toggle, and —
  only when Pin is selected — a version picker populated from that project's own version history
  (version + note + timestamp). Expanding a row reveals that SPECIFIC reference's own resolved
  content, read-only, live (FR-71) — collapsing/expanding never affects reorder/remove/add, which
  remain available regardless of any row's expand state. Add-row, remove-row (✕), and reorder (▲▼)
  controls are always available; reordering immediately affects the Merged bases panel and the
  Merged result panel (FR-55, FR-57).
- **A brand-new env Config Set's base-chain editor defaults to exactly one row** — this env's own
  project, Active — reproducing today's single-base pairing/behavior unchanged for any operator who
  never adds a second row (FR-56).
- **Merged bases panel** shows the FOLDED result of every row in the current draft chain, in order,
  BEFORE the env override is applied — always live, computed from the same response driving the
  per-row accordion inspectors and the Merged result panel below (FR-71/FR-73) — never the static
  placeholder the prior "Effective base" panel shipped as.
- Editing the Env-override panel, OR any change to the base-chain editor (add/remove/reorder/toggle/
  pin version), triggers a debounced call to the server-side `doComputeMerge`/`doPreviewMerge`
  endpoint (reuses the same `EffectiveConfigResolver.resolveChain` Java logic the pipeline steps
  use — no second, divergent merge implementation) that recomputes the Merged bases panel, every
  expanded accordion row's own resolved-content view, and the Merged result panel, all against the
  FULL current draft chain (FR-57, FR-71). While the Env-override panel's content is transiently
  invalid mid-edit, the Merged result panel **keeps showing the last valid merge** with a small
  non-blocking indicator — it never blanks or shows an error state.
- `null` in the override removes that key from the merged result (RFC 7396 semantics), evaluated
  after the full base chain has been folded; a key simply absent from the override inherits the
  folded chain's value unchanged (FR-9).
- Save/Save & Activate/Discard all changes sit inline together (FR-51, FR-74) and Save/Save &
  Activate persist the current draft `baseChain` (the base-chain editor's rows) together with the
  override content, both frozen into the new version's own immutable record (FR-51).
- The version-history table above (env layer's own) belongs ONLY to this env override — rolling
  this back never touches any base Config Set's own separately-versioned history (FR-39/FR-11).
  Each row's `[▸ N bases]` control expands that PAST version's own frozen chain, read-only (FR-58)
  — it is display-only and never opens an editor; changing a chain is only ever done in the live
  base-chain editor on the current draft, then saved as a new version.

**"Generate Template" — page-level action (FR-15b, FR-41, FR-42, FR-44), unchanged placement and
behavior from the prior pass, restated against the new layout:**

Deliberately placed as its own button above the editor area, not folded into the Env-override
panel's `[ Editing ] [ Compare ]` row — see the reasoning note at the top of this document. Clicking
it temporarily replaces the Merged-bases/Env-override/Merged-result editor area in its entirety with
one full-width read-only panel, so it is never visually confusable with the Merged result panel's
live draft preview sitting nearby:

```
┌─ apilealtad ▸ dev ▸ apilealtad-dev ──────────────────────────────────────────────────┐
│                                                                                      │
│  Base chain (2 rows) — see the editor above                [ ← Back to editor view ] │
│                                                                                      │
│  ⓘ Effective template from the base chain's ACTIVE/PINNED versions, per this env's   │
│    own ACTIVE version's frozen chain (FR-51/FR-52) ⊕ apilealtad-dev ACTIVE v4 —      │
│    NOT the Env-override panel's or base-chain editor's in-progress unsaved edits     │
│    (FR-16)                                                                          │
│                                                                                      │
│  ┌─ Generated template (read-only) ──────────────────────────────────────────────┐  │
│  │  1 │ {                                                                        │  │
│  │  2 │   "database": {                                                          │  │
│  │  3 │     "port": 5432,                                                        │  │
│  │  4 │     "password": "#{Database.Password}#"     ← identical token shape,     │  │
│  │  5 │   },                                            secret or not (FR-42)    │  │
│  │  6 │   "featureFlags": { "newCheckout": "#{FeatureFlags.NewCheckout}#" },     │  │
│  │  7 │   "billing": { "apiKey": "#{Billing.ApiKey}#" }                          │  │
│  │  8 │ }                                                                        │  │
│  └─────────────────────────────────────────────────────────────────────────────┘  │
│  [ 📋 Copy to clipboard ]                                                          │
└──────────────────────────────────────────────────────────────────────────────────┘
```

Behavior:
- Calls a `doGenerateTemplate`/`generateTemplate` JS-proxy pair (FR-44), server-side walking the
  SAME `EffectiveConfigResolver.resolveChain` output already used by the pipeline steps and the
  live-merge preview (FR-16) — built from the env version's own ACTIVE version's frozen `baseChain`
  (FR-52's synthesized-default rule applies if that chain is empty), never the base-chain editor's
  or override panel's current unsaved buffer, even while both sit right there mid-edit.
- "← Back to editor view" restores the normal Merged bases / Env override / Merged result layout
  exactly as it was (no data loss — the base-chain editor's and override panel's in-progress edits,
  including Discard-all-changes' undo target, are untouched underneath, since this view never
  disturbed them).
- The "Generate Template" button is disabled with the label "no active version yet" when the env
  Config Set has no active version, OR when any base in its resolved chain has no active/pinned
  version to resolve (FR-40/41's disabled-affordance rule, generalized from "either Config Set" to
  "any Config Set in the chain").
- Does not alter Save/Save & Activate/Discard all changes, Activate, Compare, the base-chain editor,
  or the Merged-bases/Env-override/Merged-result panels in any way (FR-41).

---

## States summary (functional, not visual)

| State | Where it shows |
|---|---|
| Empty (no Config Sets yet) | List pages — empty table + "New" CTA |
| Loading | List pages on first load; Merged-result panel during the debounced recompute |
| Valid JSON | Save enabled, no marker/banner |
| Invalid JSON (syntax only — no schema check) | Save disabled, inline Monaco marker + top banner |
| Save in flight | Save button shows a busy state, form POST in progress |
| Compare mode | Same editor box, content replaced by Monaco diff view |
| Merged-result: valid | Live JSON tree, no indicator |
| Merged-result: override transiently invalid | Last valid tree retained + small non-blocking indicator |
| Generate Template: no active version | Button disabled, label "no active version yet" (FR-40); on the env page also disabled if any chain entry has no active/pinned version to resolve |
| Generate Template: active mode (Global page) | Same editor box, 3rd toggle state, read-only Monaco + Copy button |
| Generate Template: active mode (Env page) | Merged-bases/Env-override/Merged-result editor area replaced by one full-width read-only panel + Copy + "← Back to editor view" |
| Base-chain editor: default (new env Config Set) | Exactly one row — this env's own project, Active (FR-56) |
| Base-chain editor: row set to Pin | Version picker appears on that row, populated with that project's version history (version + note + timestamp) |
| Base-chain editor: row set to Active | Version picker hidden/removed for that row |
| Base-chain editor: add/remove/reorder | Merged bases panel and Merged result panel recompute immediately against the full draft chain (FR-57, FR-73) |
| Base-chain accordion row: collapsed (default) | Header row only (project/mode/version-if-pinned/reorder/remove); no resolved-content view shown (FR-71) |
| Base-chain accordion row: expanded | Read-only, live resolved-content view for THAT specific reference — recomputed on every base-chain/override change, never a static/cached copy (FR-71; this is the fix for the audit-found dead "Effective base" panel) |
| Base-chain project picker: row #1 | Unfiltered — every COMMON-role Config Set across every project (NFR-8); this row's resolved `ContentType` becomes the filter context for later rows (FR-72) |
| Base-chain project picker: row #2+ | Filtered to COMMON Config Sets sharing row #1's resolved `ContentType`; FR-61's `doSave` mismatch rejection remains the authoritative backstop (FR-72) |
| Discard all changes: clicked | Confirm prompt, then Env-override draft + base-chain draft rows both revert to the currently-loaded saved version's content/`baseChain`, in place (FR-74) |
| Env version history row: base-chain collapsed (default) | `[▸ N bases]` control visible, no detail shown |
| Env version history row: base-chain expanded | Read-only inline detail listing each frozen `BaseConfigReference` — project, mode, resolved/pinned version (FR-58) |
| Viewport: tablet (≤1100px) | Merged bases / Env override / Merged result stack vertically full-width, same order as the top-row-then-below arrangement collapses to; base-chain accordion rows keep one-line headers |
| Viewport: mobile (≤480px) | Base-chain accordion row headers become stacked cards; expanded resolved-content view renders full-width beneath its card |
| Content-type picker: zero saved versions (Global page) | Enabled radio/select row (JSON/XML/YAML, default JSON), directly above the Monaco box (FR-59, FR-68) |
| Content-type picker: ≥1 saved version (Global page) | Same row, disabled/read-only, showing the committed value + lock icon (FR-59) |
| Editor language mode | Follows the Config Set's `contentType` (Global page: its own; Env page: resolved from its base chain, FR-60) — json/xml/yaml, drives which diagnostics/format provider attaches |
| Invalid content (XML/YAML, syntax-only) | Same as "Invalid JSON" row above — Save disabled, inline Monaco marker + top banner, now format-dispatched via `validateSyntaxOrFail` (FR-63, FR-65) |
| YAML diagnostics: checking (debounce in flight) | Small `⏳ Checking YAML…` text above the editing Monaco box only (Global page's editor, Env page's Env-override panel) — never blocks typing, disappears when the round trip lands (FR-63, my-call affordance) |
| Cross-chain content-type mismatch (Env page `doSave`) | Existing `saveBanner` — "Save blocked: mismatched content types in base chain — …" naming every conflicting project + type (FR-61); no new banner element |
| Root list page: `Type` column | Plain text cell, `Name \| Type \| Active version \| Last modified` (FR-67) |
| Env-level list page: no `Type` column (v5 flag) | Deliberately absent — ENV Config Sets have no own stored `ContentType` to render without a chain-resolve call per row; flagged for `tech-lead`/`business-analyst`, not silently added |
