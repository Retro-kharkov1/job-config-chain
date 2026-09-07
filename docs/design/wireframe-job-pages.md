# Wireframe — Job-scoped Config Templates (Pages 3 and 4)

> Spatial-layout wireframe, ASCII pseudographics only, per the space rule
> `wireframe-before-visual-design` — no color, no styled boxes, no HTML/CSS, no Mermaid (Mermaid is
> for process/flow; this file answers "where does X sit on the screen"). Companion to
> `wireframe-layout.md`, which covers Pages 1 and 2 (the global/admin screens under Manage Jenkins).
>
> Covers FR-75, FR-76, FR-77, FR-77a, FR-78, FR-78a. Written 2026-09-06, AFTER the screens were
> implemented — the implementation went straight from FR text to Jelly with no layout pass, which is
> exactly what the space rule exists to prevent. This file is therefore both a wireframe and a
> correction: differences between it and the shipped page are drawn as intended, not as built, and
> are listed under "Deltas against the shipped page" at the end.

<reference-pattern>

## Reference pattern

Jenkins core's own **Configure** screen is the model, per the owner: an item in the job's own
sidebar leading to that item's own full page, and the same pattern repeated one level down for each
environment. What is copied is the KIND of affordance and the URL shape, not a position in the
sidebar — core renders plugin actions via `actions.jelly` strictly after its own
`<p:configurable/>` block, so a plugin item can never sit above Configure.

Verified against `jenkins-core-2.568.3`:
- `hudson/model/AbstractProject/sidepanel.jelly` — `<l:tasks>` … `<p:configurable/>` … `<st:include page="actions.jelly"/>`
- `hudson/model/Job/configure.jelly` — `<f:form>` … `<f:saveApplyBar/>`; core's Configure page has
  NO `<h1>` repeating its own name, only the job's breadcrumb and title bar.

</reference-pattern>

<page-3-job>

## Page 3 — Job ▸ Config Templates (FR-76)

Reached from the job's own sidebar. Three states of ONE page; the association form is present in
all three, so no state is a dead end.

### State A — no association yet

```
┌─ Dashboard ▸ my-app-deploy ▸ Config Templates ───────────────────────────────────────┐
│                                                                                        │
│ ┌ Status          ┐  ┌─ Config Templates association ─────────────────────────────┐   │
│ │ Changes         │  │                                                             │   │
│ │ Workspace       │  │  ⓘ This job is not tied to a Config Templates project yet. │   │
│ │ Build Now       │  │                                                             │   │
│ │ Configure       │  │  Project key                                                │   │
│ │ Delete Pipeline │  │  [ my-app_______________________ ]                          │   │
│ │ ─────────────── │  │                                                             │   │
│ │ Config Templates│  │  Environment                            (optional)          │   │
│ │        ^ this   │  │  [ ______________________________ ]                         │   │
│ └─────────────────┘  │                                                             │   │
│                      └─────────────────────────────────────────────────────────────┘   │
│                                                                   [ Save ]             │
│                                                                                        │
│                      No environments to show until a project key is set.               │
│                                                                                        │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### State B — project set, no environment chosen

```
┌─ Dashboard ▸ my-app-deploy ▸ Config Templates ───────────────────────────────────────┐
│                                                                                        │
│  ┌─ Config Templates association ───────────────────────────────────────────────┐     │
│  │                                                                               │     │
│  │  Project key                                                                  │     │
│  │  [ my-app_______________________ ]                                            │     │
│  │                                                                               │     │
│  │  Environment                            (optional)                            │     │
│  │  [ ______________________________ ]                                           │     │
│  │                                                                               │     │
│  └───────────────────────────────────────────────────────────────────────────────┘     │
│                                                                    [ Save ]            │
│                                                                                        │
│  ┌─ Environments ───────────────────────────────────────────────────────────────┐     │
│  │                                                                               │     │
│  │  ┌─ Environment ─┬─ Active version ─┬─ This job ──────────────┐              │     │
│  │  │ dev            │ v4               │ [ Use for this job ]   │              │     │
│  │  │ qa             │ v2               │ [ Use for this job ]   │              │     │
│  │  │ prod           │ none             │ [ Use for this job ]   │              │     │
│  │  └────────────────┴──────────────────┴────────────────────────┘              │     │
│  │    (click an environment name -> Page 4, that env under THIS job)            │     │
│  │                                                                               │     │
│  │  Open an environment that does not exist yet:                                 │     │
│  │  [ staging_____________ ]  [ Open ]                                           │     │
│  │                                                                               │     │
│  └───────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                        │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### State C — project + environment set

Identical to State B, except the associated row is marked instead of offering the button:

```
│  │  ┌─ Environment ─┬─ Active version ─┬─ This job ──────────────┐              │     │
│  │  │ dev            │ v4               │ ✓ used by this job     │              │     │
│  │  │ qa             │ v2               │ [ Use for this job ]   │              │     │
│  │  └────────────────┴──────────────────┴────────────────────────┘              │     │
```

</page-3-job>

<page-4-job-env>

## Page 4 — Job ▸ Config Templates ▸ &lt;environment&gt; (FR-77, FR-77a)

The env editor itself, rendered under the job. It is NOT a second editor: the page delegates to the
very same `EnvConfigSetPage` object the global URL renders (FR-77a), so the whole editor body below
the banner is byte-for-byte Page 2's env edit view — see `wireframe-layout.md` for its internals.
Only the frame around it differs.

### State A — normal (job is associated with a project)

```
┌─ Dashboard ▸ my-app-deploy ▸ Config Templates ▸ dev ─────────────────────────────────┐
│                                                                                        │
│  ┌──────────────────────────────────────────────────────────────────────────────┐     │
│  │ my-app / dev — opened from job "my-app-deploy"                                │     │
│  │ Same Config Set as the global page. Canonical: /configTemplates/my-app/dev/   │     │
│  └──────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                        │
│  ══════════ delegated, identical to Page 2 env edit view ══════════                   │
│                                                                                        │
│  ┌─ Version history ──────────┐  ┌─ Secrets manifest ─────────────┐                   │
│  │ ...                        │  │ ...                            │                   │
│  └────────────────────────────┘  └────────────────────────────────┘                   │
│  ┌─ Base chain ───────────────────────────────────────────────────┐                   │
│  │ ...                                                            │                   │
│  └────────────────────────────────────────────────────────────────┘                   │
│  ┌─ Merged bases ──┬─ Env override ──┬─ Merged result ──┐                             │
│  │ (read-only)     │ (editable)      │ (read-only)      │                             │
│  └─────────────────┴─────────────────┴──────────────────┘                             │
│                                                                                        │
│  ═══════════════════ end of delegated region ═══════════════════                      │
│                                                                                        │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

The banner is the ONLY thing this page adds. It exists to satisfy FR-77a's disclosure obligation:
the operator must be able to see that this data is one Config Set reachable from two URLs, and
which one is canonical.

### State B — job has no association, but an environment URL was requested

```
┌─ Dashboard ▸ my-app-deploy ▸ Config Templates ▸ dev ─────────────────────────────────┐
│                                                                                        │
│  ┌──────────────────────────────────────────────────────────────────────────────┐     │
│  │ This job is not tied to a Config Templates project, so there is no            │     │
│  │ environment configuration to show here yet.                                   │     │
│  │                                                                               │     │
│  │ ← Back to this job's Config Templates                                         │     │
│  └──────────────────────────────────────────────────────────────────────────────┘     │
│                                                                                        │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

No editor is rendered. Guessing a project would mean silently editing the wrong Config Set, which
FR-77 forbids outright.

</page-4-job-env>

<deltas>

## Deltas against the shipped page (2026-09-06)

The pages were implemented before this wireframe existed. These differences are drawn above as
INTENDED and are not yet true of the running code:

1. **No `<h1>Config Templates</h1>` on Page 3.** The shipped page repeats the words three times —
   sidebar item, browser title, and an `h1`. Core's own Configure page has no such heading; the
   breadcrumb and title bar already name the screen. Drop the `h1`.
2. **"Use for this job" instead of "Associate".** The shipped button says `Associate`, which names
   the plugin's internal concept rather than what the operator is doing.
3. **"✓ used by this job" instead of "associated"** in the This-job column, for the same reason.
4. **Environment field marked `(optional)`** on the association form — FR-75 makes a blank
   environment a valid, meaningful state (common layer only), and nothing on the shipped form says
   so.
5. **Banner wording on Page 4** states the canonical URL explicitly. The shipped page exposes
   `getCanonicalUrl()` but the view does not render it, so FR-77a's disclosure is currently unmet.

</deltas>
