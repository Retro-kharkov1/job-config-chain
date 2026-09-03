# Wireframe — Config Templates Admin UI (Global + Env level)

> **Relabeling note (2026-08-27, owner correction):** despite the filename/title, this document is
> **interaction/process-flow documentation** (navigation between pages, save/activate/compare round-trips,
> the live-preview sequence diagram) — it does NOT show spatial layout (where regions sit on the page) and
> should not be treated as "the wireframe." It remains valid and useful as process documentation. The
> actual spatial-layout wireframe is `wireframe-layout.md` in this same folder — use that file for "where
> does X sit on the screen" questions.
>
> Status: DRAFT for approval. Plain structural wireframe only (Markdown + Mermaid), per the space rule
> `wireframe-before-visual-design` — no color, no styled boxes, no HTML/CSS. Third attempt after two
> rejections (styled HTML/CSS mockup, then plain ASCII-art). Covers FR-30–FR-39 from
> `docs/business/requirements.md` and the OQ-9 / Finding 1–5 decisions from
> `docs/development/tech-lead-ui-review-2026-08-27.md`. Structural principle carried over from the Rojet
> reference (`ui-12-one-editor.md`): **one editor instance per concept, comparison is a mode/action on an
> existing element, never a duplicate second component**, and no field is shown twice on the same page.

<site-map>

## 1. Site map — how the two pages relate

Two independent admin screens, reached from Jenkins' own "Manage Jenkins" navigation (mirroring the
Managed Files entry point per FR-30). The env-level screen references (never duplicates) a Common Config
Set chosen from the global list.

```mermaid
flowchart TD
    MJ["Manage Jenkins"] --> GL["Global list:\nConfig Templates\n(FR-30)"]
    GL -->|"New"| GE["Global edit page\n(FR-31/32/33)"]
    GL -->|"open existing row"| GE
    GE -->|"Save (form POST)"| GL

    MJ --> ProjFolder["Config Project folder\n(existing Jenkins folder/job context)"]
    ProjFolder --> EL["Env list\n(per environment, FR-34)"]
    EL -->|"New / open existing"| EE["Env edit page\n3-panel merge view\n(FR-34-39)"]
    EE -->|"Save (form POST)"| EL

    EE -. "picker selects\nwhich Common Config Set\nto override (FR-35)" .-> GL
```

</site-map>

<page-1-global>

## 2. Page 1 — Global "Config Templates" — list view (FR-30)

Same interaction shape as Jenkins' own Manage Jenkins → Managed Files list: one table, one "New" action,
each row opens the same edit page used for creation.

```mermaid
flowchart TD
    subgraph ListPage["Config Templates — list page"]
        Title["Page title: Config Templates"]
        NewBtn["[ + New Config Set ]"]
        subgraph Table["Table (one row per Common Config Set)"]
            Col["Columns: Name | Active version # | Last modified"]
            Row1["apilealtad-common   v7   2026-08-20"]
            Row2["andatti-common      v3   2026-07-11"]
        end
    end
    NewBtn --> Row1
    Row1 -->|"click row"| EditPage["→ Global edit page (§3)"]
    Row2 -->|"click row"| EditPage
```

</page-1-global>

<page-1-edit>

## 3. Page 1 — Global Config Set edit view (FR-31, FR-32, FR-33)

One page, one Monaco editor instance (`language: 'json'`, FR-32), one version-history table beneath it.
"Compare" is a **mode** of the same version-history selection, not a second editor — pick two rows and the
existing editor area switches to Monaco's built-in diff mode (tech-lead Finding 5).

```mermaid
flowchart TD
    subgraph EditPage["Global edit page — apilealtad-common"]
        Breadcrumb["Config Templates / apilealtad-common"]
        subgraph EditorArea["Editor area (ONE Monaco instance)"]
            ModeToggle["View: [ Edit ]  [ Compare selected versions ]"]
            Editor["Monaco JSON editor\n(syntax highlight, bracket match,\nlanguage: 'json')"]
        end
        NoteField["Change note (required, FR-5): [ ..text.. ]"]
        SaveBar["[ Save ]  (disabled while JSON invalid, FR-33)   [ Save & Activate ]"]
        subgraph History["Version history (FR-31: activate + compare)"]
            HRow1["v7  2026-08-20  ib  ● active   [Activate] [select for compare]"]
            HRow2["v6  2026-08-18  ib  —          [Activate] [select for compare]"]
            HRow3["v5  2026-08-10  ib  —          [Activate] [select for compare]"]
        end
    end
    HRow2 -->|"pick 2 rows + click Compare"| ModeToggle
    ModeToggle -->|"Compare mode"| DiffEditor["Same editor area now shows\nMonaco diff mode\n(selected v6 vs v3, read-only)"]
    ModeToggle -->|"Edit mode"| Editor
    SaveBar -->|"classic form POST\n(Finding 3)"| ListPage2["→ back to list page (§2)"]
    HRow1 -->|"Activate (AJAX, Finding 3)"| HRow1
```

</page-1-edit>

<page-1-save-validation>

## 4. Save validation states (FR-33) — inline Monaco marker + page-level summary

Chosen approach (tech-lead Finding 2): **inline Monaco marker is primary** (squiggle + gutter dot at the
exact line/column of the JSON parse error, since Monaco supports this natively and any standard JSON
parser reports line/column) **plus a one-line page-level banner as a summary/fallback**, so the failure is
visible even if the user has scrolled the editor away from the error line. Save stays disabled until valid.

```mermaid
stateDiagram-v2
    [*] --> Editing
    Editing --> SyntaxValid: JSON parses
    Editing --> SyntaxInvalid: JSON parse error
    SyntaxValid --> Editing: further edits
    SyntaxInvalid --> Editing: further edits

    state SyntaxValid {
        [*] --> SaveEnabled
        SaveEnabled: Save button enabled\nno markers, no banner
    }
    state SyntaxInvalid {
        [*] --> SaveBlocked
        SaveBlocked: Save button disabled\ninline Monaco marker at error line/col\n+ page banner: "Save blocked: invalid JSON — see marker below"
    }
```

</page-1-save-validation>

<page-2-env-list>

## 5. Page 2 — Env level — list view (FR-34)

Identical shape to §2, scoped to one (Config Project, environment) pair — same table columns, same "New"
action, listing Env Config Sets instead of Common Config Sets.

```mermaid
flowchart TD
    subgraph EnvListPage["Env list page — apilealtad / dev"]
        NewBtn2["[ + New Env Config Set ]"]
        subgraph Table2["Table"]
            ERow1["apilealtad-dev   v4   2026-08-19"]
        end
    end
    NewBtn2 --> ERow1
    ERow1 -->|"click row"| EnvEditPage["→ Env edit page (§6)"]
```

</page-2-env-list>

<page-2-edit>

## 6. Page 2 — Env edit page — 3-panel merge view (FR-35, FR-36, FR-37, FR-38)

One page. A picker at the top selects the paired Common Config Set (FR-35, set once at creation, no second
pairing mechanism). Below it, three panels left-to-right modeled on a git 3-way merge view: **Global
(read-only)** | **Env override (the only editable Monaco instance on this page)** | **Merged result
(read-only, live-recomputed)**. The Merged panel is driven entirely by the env-override panel's content —
it is not a separate editor a user types into.

```mermaid
flowchart LR
    subgraph EnvEditPage["Env edit page — apilealtad-dev"]
        direction TB
        Picker["Overrides Common Config Set: [ apilealtad-common ▾ ]  (immutable after creation, FR-35)"]
        subgraph Panels["Three panels, left to right"]
            direction LR
            subgraph GlobalPanel["Global (read-only)\nMonaco, readOnly:true"]
                GContent["active content of\napilealtad-common v7"]
            end
            subgraph OverridePanel["Env override (editable)\nMonaco, language:'json'\n★ the only editable panel"]
                OContent["sparse merge-patch overlay\n(FR-37 — never a full copy)"]
            end
            subgraph MergedPanel["Merged result (read-only)\nMonaco, readOnly:true\nlive-recomputed (FR-38)"]
                MContent["EffectiveConfigResolver output\nsee sequence in §7"]
            end
        end
        Picker --> Panels
        NoteField2["Change note (required): [ ..text.. ]"]
        SaveBar2["[ Save ]  (blocked on invalid JSON, same rule as §4)   [ Save & Activate ]"]
        subgraph EnvHistory["Env-layer version history — INDEPENDENT of Global's (FR-39)"]
            EHRow1["v4  2026-08-19  ib  ● active   [Activate] [select for compare]"]
            EHRow2["v3  2026-08-15  ib  —          [Activate] [select for compare]"]
        end
    end
    OverridePanel -->|"onDidChangeModelContent\n(debounced)"| MergedPanel
    SaveBar2 -->|"classic form POST"| EnvListPage2["→ back to env list (§5)"]
```

</page-2-edit>

<live-preview-sequence>

## 7. Live merge-preview round trip (FR-38, OQ-9 resolution) — `doPreviewMerge`

Server-side only, per the tech-lead's OQ-9 resolution: every recompute — including this live UI preview —
calls the same `EffectiveConfigResolver` Java class the pipeline steps use, via a debounced Stapler
`doPreviewMerge` AJAX call (same pattern as Jenkins core's own `doCheckXxx` live-validation). While the
override text is transiently invalid JSON mid-edit, the Merged panel keeps showing the **last valid**
result instead of blanking or erroring (tech-lead Finding 1).

```mermaid
sequenceDiagram
    participant User
    participant OverrideEditor as Env override panel (Monaco)
    participant Debounce as Debounce timer (~250-400ms)
    participant Stapler as Stapler doPreviewMerge endpoint
    participant Resolver as EffectiveConfigResolver (Java)
    participant MergedPanel as Merged result panel (Monaco, readOnly)

    User->>OverrideEditor: types a change
    OverrideEditor->>Debounce: onDidChangeModelContent
    Debounce->>Stapler: doPreviewMerge(overlayJsonText)
    alt overlay text is valid JSON
        Stapler->>Resolver: merge(global active content, overlay)
        Resolver-->>Stapler: merged JSON
        Stapler-->>MergedPanel: { ok: true, merged: <json> }
        MergedPanel->>MergedPanel: render new merged result
    else overlay text is transiently invalid JSON
        Stapler-->>MergedPanel: { ok: false, error: <message> }
        MergedPanel->>MergedPanel: KEEP last valid merged result\n+ small non-blocking "unparseable" indicator\n(never blank, never hard error)
    end
```

</live-preview-sequence>

<compare-interaction>

## 8. Compare interaction (both pages, FR-7 / FR-31 / FR-34 / FR-39)

Same mechanic on both the global edit page (§3) and the env edit page (§6): select two rows in whichever
version-history table is on that page, then the page's one editor area switches into Monaco's diff mode —
no separate diff page, no modal.

```mermaid
flowchart LR
    A["Select version A\n(checkbox/radio in history row)"] --> C["Compare button enabled\n(needs exactly 2 selected)"]
    B["Select version B"] --> C
    C -->|"click Compare (AJAX)"| D["Editor area (§3 or §6)\nswitches to Monaco diff mode\nA (left) vs B (right), read-only"]
    D -->|"click Edit"| E["Editor area returns to\nnormal edit mode\n(current draft content restored)"]
```

</compare-interaction>

<states-summary>

## 9. States covered

- **Global list (§2):** default (rows present), empty (no Config Sets yet — table shows only the "+ New"
  action), loading (table skeleton while fetching), no error state needed (read-only GET).
- **Global edit (§3):** create-new (blank editor, no history section), edit-existing (editor pre-filled,
  history populated), valid/invalid JSON (§4), save-in-flight (Save button disabled + spinner during form
  POST), save-success (redirect to list, per Finding 3), compare mode vs. edit mode (§8).
- **Env edit (§6):** same create/edit/valid/invalid/save states as global edit, applied to the override
  panel only; additionally the Merged panel has its own valid/last-valid-retained/loading-recompute states
  (§7), and the picker has a locked (post-creation) state (FR-35, Finding 4).

</states-summary>
