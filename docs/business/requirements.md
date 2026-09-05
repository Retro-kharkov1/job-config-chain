# config-template-sync — Business Requirements Specification

> **Consolidation note (2026-09-03):** this file is now the SINGLE canonical requirements document for
> this plugin, living in the repo itself (per this owner's standing rule: personal-repo projects keep
> their documentation in the repo, not in a separate Copilot Space folder). It was assembled by copying
> the full FR-1–FR-74 history from the space-local copy (`D:\Repos\.copilot-knowledge\spaces\
> config-template-sync\docs\business\requirements.md`, now a redirect stub — do not edit that copy
> going forward) and appending the job-association feature's FR-71–FR-74, renumbered to **FR-75–FR-78**
> to resolve a numbering collision (two independent BSA passes on 2026-09-02/03 each reached FR-71–FR-74
> in their own file, for two unrelated features — the base-chain-visualization redesign here, and the
> job-association feature that was drafted directly in the repo). The one code reference to the old
> job-association numbers (`ConfigTemplatesJobPropertyFormPersistenceTest.java`) has been updated to
> match. See the renumbered section near the end of §4 for the job-association FRs.
>
> **Update (2026-09-05):** FR-75–FR-78 in the "Job association" block were REWRITTEN (content, not just
> numbers this time) per an owner correction: the "Config Templates Association" section must not live
> inside `/job/<name>/configure` — that URL was cited only as an example of the "own sidebar item → own
> page" pattern. The association now has its own dedicated page at `/job/<name>/configTemplates`,
> rendered directly (no `sendRedirect2`) with three content states (no association / project-only /
> project+environment). The existing job-sidebar "Config Templates" item (already contributed by
> `ConfigTemplatesJobActionFactory`) is the single navigation affordance into this feature and is not
> duplicated; individual environments do NOT get their own sidebar entries, only in-page links. The
> pattern repeats one level down per environment via a JOB-SCOPED page,
> `/job/<name>/configTemplates/<environment>` (owner explicitly rejected reusing the existing global
> `/configTemplates/<projectKey>/<environment>` page for this — "нет" when asked directly), specified in
> the rewritten **FR-77**. A new **FR-77a** makes the resulting two-URLs-one-Config-Set situation a
> numbered, testable data-safety requirement (one shared save/validate path, one persisted entity, canonical
> URL stays the global page). FR-76/77 also spell out, bullet by bullet, which UI conventions MUST mirror
> `/job/<name>/configure` exactly (sidebar mechanism, URL shape, breadcrumbs, post-save behavior, form
> furniture). **Update (2026-09-05, same day, second pass):** these "mirror Configure" points were
> re-verified against Jenkins core 2.568.3 itself (unpacked `jenkins-core-2.568.3.jar` inside the running
> test container) and are now stated in FR-75/FR-76/FR-77 as confirmed facts rather than open questions:
> `hudson/model/Job/configure.jelly`'s `f:descriptorList`/`h.getJobPropertyDescriptors(it)` is the exact
> mechanism that must stop surfacing `ConfigTemplatesJobProperty` for it to disappear from `/job/<name>/configure`
> (FR-75); `hudson/model/AbstractProject/sidepanel.jelly` confirms plugin `Action`s always render via
> `actions.jelly`, strictly after core's own `<p:configurable/>` block (Configure/Build Now/Delete) — so a
> plugin sidebar item can never be placed above or among those core items, only in the same KIND of
> `l:task`-equivalent slot (FR-76); and the `/job/<name>/configure` → `/job/<name>/configTemplates` URL
> mirroring was confirmed live (FR-76/FR-77). A new **FR-78a** records the migration note and explicitly
> supersedes the old FR-78 acceptance criteria. FR-79 and everything after it are unchanged and keep their
> existing numbers.
>
> Status: DRAFT v2 (BSA pass 2, 2026-08-27 — folds in the admin-UI specification). v1 was written from
> scratch against the raw design discussion (`C:\Users\retro\.claude\plans\jenkins-woolly-dragonfly.md`,
> earlier revision) and the task description of the already-pushed MVP. **Limitation (still applies to
> §8):** this BSA pass could not read the pushed repository code directly — no GitHub-authenticated tool or
> shell access was available in this session (the repo is private; an anonymous fetch returned 404).
> Section 8 (`mvp-assessment`) is therefore based on the MVP's *documented* design (domain model, RFC 7396
> merge, two pipeline steps, build-version pinning, 38 green tests) as described in the plan doc and task
> brief, not on a line-by-line code read. Before this draft is treated as validated, `tech-lead` (who has
> repo access) should do one confirmatory pass over §8 against the actual source.
>
> **v2 addition:** FR-30–FR-39 (§4), a new "UI reference groundings" subsection (§2), an out-of-scope
> update (§6), and OQ-9 (§7) formalize the owner-approved two-level admin UI specification from the latest
> `jenkins-woolly-dragonfly.md` (Managed-Files-pattern global + env screens, Monaco JSON editor, 3-panel
> live merge view). The existing OQ-1–OQ-8 and §8 MVP assessment are unchanged by this pass.
>
> **v3 addition (2026-09-01):** FR-15/FR-16 (§4, "Template generation") are fully specified — previously
> deferred/out-of-scope wording is replaced with concrete, testable requirements, grounded in the token
> convention and merge/flatten semantics already implemented in `merge/TokenExtractor.java` and
> `merge/JsonPaths.java`. New FR-40–FR-44 (§4) formalize the UI surface (buttons, presentation, permission
> gate) the same way FR-30–FR-39 formalized the rest of the admin UI. §6 is updated to drop the now-resolved
> "generate template has no UI trigger" deferral. OQ-10 (§7) is added for one genuinely owner-level scope
> call left open. Exact wireframe placement of the new buttons is explicitly NOT done here — see the SDLC
> follow-up note at the end of §4's new UI subsection.
>
> **v4 addition (2026-09-01, versioning-parity audit):** FR-31 is corrected in place — its "compare any two
> selected historical versions" wording was stale and did not match the approved, already-implemented
> click-to-compare design. New FR-45–FR-47 (§4) make the Compare-mode exit-path state machine and the
> Save/Activate busy-disabled/no-op-prevention interaction states explicit and testable, closing gaps found
> when the plugin's Save/Activate/Compare UX was audited against a reference versioning spec. See
> `docs/business/versioning-parity-audit-2026-09-01.md` for the full audit.
>
> **v5 addition (2026-09-01, `ux-ui-designer` — env page layout-parity + version-dropdown redesign):** new
> FR-48–FR-50 (§4) formalize the "Common Config Set version" preview selector called for in the original
> plan (`C:\Users\retro\.claude\plans\jenkins-woolly-dragonfly.md`) but never implemented — a dropdown,
> distinct from the existing project-pairing picker (FR-35), that lets the env page's Global (read-only) and
> Merged result panels preview against any historical version of the paired common Config Set, without
> affecting the true active version, Save/Save & Activate, or Generate Template (FR-16 continues to govern
> those). Wireframe placement is in `docs/design/wireframe-layout.md` (Page 2 edit view, v3 addition), which
> also re-syncs that file's env-page section order with the page's actual shipped Jelly layout (title →
> env-layer version history → editor/3-panel section with Save/Save & Activate inline → Secrets manifest) —
> the diagram had drifted out of sync with the implementation.
>
> **v6 addition (2026-09-01, `business-analyst` — multi-base config chains with per-version pinning):**
> formalizes the owner-approved plan in `C:\Users\retro\.claude\plans\jenkins-woolly-dragonfly.md` that
> generalizes the rigid one-common-Config-Set base model into an ordered, per-version-pinnable chain of
> base Config Sets. New entities `BaseConfigReference` and `ResolvedBaseVersion` (§3). FR-8/FR-9/FR-10/
> FR-11 are amended in place (dated inline notes, not renumbered) to describe an ordered chain folded
> left-to-right instead of exactly one common Config Set. FR-23–FR-27 (Deployment Binding / build-version
> pinning) are amended in place to generalize from a single `commonVersionNumber` int to a
> `List<ResolvedBaseVersion>`. New FR-51–FR-58 (§4) cover base-chain persistence, the backward-compatibility
> default, the no-recursive-layering rule, the hard reproducibility guarantee, and the base-chain editor UI.
> New NFR-8 (§5) states the v1 decision that cross-project base references are unrestricted. **FR-48–FR-50
> are marked SUPERSEDED in place** (not deleted) — the transient, non-persisted preview-only common-version
> selector they described is replaced by the persisted, per-version base-chain model this pass formalizes.
> Wireframe placement of the base-chain editor is explicitly NOT done here — see the SDLC follow-up note
> at the end of §4's new subsection; route to `ux-ui-designer` next.
>
> **v7 addition (2026-09-02, `business-analyst` — multi-format Config content: JSON/XML/YAML):** formalizes
> the owner-approved plan in `docs/development/multi-format-content-plan-2026-09-02.md`, which layers a
> pluggable `TreeNode`/`TreeFormat` content model on top of the now-shipped base-chain feature (FR-8–FR-11
> amendments, FR-51–FR-58, NFR-8). New entity **`ContentType`** (§3, enum `JSON`/`XML`/`YAML`); the "Config
> Set" entity is amended in place to note it now carries an immutable `contentType`. New FR-59–FR-70 (§4)
> cover: content-type chosen once at first-save on a COMMON Config Set and thereafter immutable (defaulting
> to `JSON` when unspecified); ENV Config Sets never choosing independently, always inheriting the type of
> their resolved base chain; the cross-chain type-consistency invariant layered onto the base-chain model
> (FR-8/FR-51 area) — every Config Set in one resolved base chain MUST share one `ContentType`, enforced at
> both `doSave` (UI, fail-loud `Failure`) and inside `BaseChainResolver`-consuming pipeline code (fail-loud
> `AbortException`), mirroring the base-chain feature's own two-tier fail-loud/forgiving split; full 3-format
> Monaco editor parity (live diagnostics + working auto-format-on-load for XML and YAML, not just syntax
> highlighting — owner-confirmed scope, not a reduced v1); validator consolidation (one shared,
> format-dispatching syntax validator replacing the previously-duplicated JSON-only call sites); the root
> list page's new `Type` column and the COMMON page's content-type picker; and full backward compatibility
> (every pre-existing Config Set implicitly becomes `ContentType.JSON` on load, zero data migration, zero
> behavior change). New **NFR-9** (§5) records the SnakeYAML/JDK-DOM dependency-footprint decision. Owner
> explicitly confirmed both the 3-format scope (JSON+XML+YAML, no deferral) and the full editor-parity scope
> (diagnostics + auto-format, not highlighting-only) — neither is a BSA judgment call, both are restated here
> as firm decisions. Wireframe placement of the content-type picker, the `Type` column, and the XML/YAML
> diagnostics/format UX is explicitly NOT done here — `docs/design/wireframe-layout.md` needs a follow-up
> `ux-ui-designer` pass covering both the root list page and the COMMON Config Set edit page; route there
> next, same SDLC convention as the FR-40/41 and FR-55–58 follow-up notes below.
>
> **v8 addition (2026-09-03, `ux-ui-designer` — base-chain visualization redesign):** a real-browser audit
> found the env page's "Effective base (read-only, collapsed)" panel (FR-36/FR-38's v4 amendment) was dead
> code — never actually wired to live data despite the spec. New FR-71–FR-74 (§4) formalize the owner's
> fuller redesign that replaces it: each base-chain row (FR-55) becomes its own accordion item with a
> read-only, always-live per-reference resolved-content view (FR-71); the base-chain project picker
> proactively filters by content type, additive to FR-61's unchanged hard rejection (FR-72); the env editor
> area's layout changes from 3 side-by-side columns to Merged-bases/Env-override on top and Merged result
> full-width below (FR-73); and a new "Discard all changes" action reverts both the override draft and the
> base-chain draft together (FR-74). Wireframe detail is in `docs/design/wireframe-layout.md`'s Page 2
> section (2026-09-03 pass) — route to `tech-lead` for contract review before implementation.
>
> **v9 addition (2026-09-03, `business-analyst` — pipeline-level resolution overrides + `setupConfigTemplate`):**
> formalizes a pipeline-level design worked out directly with the owner, layered on top of the now-shipped
> base-chain (FR-51–FR-58) and pinning (FR-23–FR-27/54) features. New FR-79–FR-85 (§4) add two optional
> parameters, `useBase` and `version`, to both `configTemplateValidate` and `configTemplateSubstitute` — an
> explicit resolution override that bypasses env-override/base-chain machinery entirely when `useBase=true`
> (resolving directly against the projectKey's own COMMON Config Set), and pins the env Config Set (when
> `useBase=false`) or the COMMON Config Set (when `useBase=true`) to one exact version number when `version`
> is supplied, taking precedence over any `buildVersion` Deployment Binding for that same call. New
> FR-86–FR-89 close a confirmed domain-model gap: `baseChain == null` (legacy data) and a hypothetical
> `baseChain == []` (deliberately emptied) are indistinguishable after `ConfigSetVersion.readResolve()`
> today, so there is no way to express "this env Config Set is truly standalone." A new `explicitlyStandalone`
> boolean field (new entity note, §3) closes this without touching what empty/absent `baseChain` already
> means for existing data (FR-52 unchanged). New FR-90–FR-95 add a new pipeline step, `setupConfigTemplate`,
> that stores its parameters scoped to the current build (`Run`) so a same-build, zero-argument
> `configTemplateValidate()`/`configTemplateSubstitute()` call can read them back — purely additive
> convenience, explicit call-site parameters always win, and detected as forbidden inside a `parallel {}`
> block (mechanism researched, not asserted without grounding — see FR-95 and new **OQ-11**). Grounded against
> the actual pushed step/support code (`ConfigTemplateValidateStep.java`, `ConfigTemplateSubstituteStep.java`,
> `StepSupport.java`, `ConfigSetVersion.java`) to confirm exact current parameter names/signatures before
> specifying new ones. Route to `tech-lead` for contract review before implementation — no wireframe/UI
> dependency (this is pipeline-step-only, no admin-UI change).

<overview>

## 1. Elevator pitch / problem statement

Teams that deploy the same application to multiple environments (dev/qa/uat/prod, or multiple customer
instances) frequently need to inject environment-specific values into a structured, nested configuration
file (e.g. `appsettings.json`, `application.yml`) at deploy time. A common Jenkins pattern is: keep a
`#{Token.Path}#`-style placeholder in the repo's config file, and substitute real values from a Jenkins-side
store at deploy time (via Config File Provider, folder credentials, or similar).

The recurring failure mode with that pattern is **structural drift**: the placeholder tokens live in the
application repo (nested JSON shape) while the substitution values live in Jenkins as a flat, unstructured
list unrelated to that shape. Every new setting must be added by hand in two disconnected places — a
token in the app repo, an entry in the Jenkins store — with no tooling to keep them in sync. Over time this
produces two silent failure classes: **missing keys** (a token in the repo has no matching store value, so
substitution silently fails or ships an unsubstituted placeholder) and **orphaned keys** (a store value no
longer referenced by any token, quietly consuming maintenance attention and space). It also has no answer
for "what config did build 1.2.3 actually ship with" once "the current active config" has since changed —
rolling back the server's code without rolling back its config to match is a latent outage.

**Problem statement:** any Jenkins user who deploys a structured-config application to multiple
environments needs their config-token store to have the *same shape* as the file it feeds, to be able to
*generate* the exact template a developer should paste into that file, to *validate* that repo tokens and
store keys agree before every deploy, to *version and roll back* config changes independently of code
deploys, and to guarantee that *rolling back a build replays the config that build actually shipped with*
— all without introducing an external database or a git-backed store, because the tool must install and
run inside Jenkins itself, portable across arbitrary Jenkins instances and arbitrary consuming projects.

**Primary users / stakeholders:**
- **DevOps/Platform engineer** — owns the Jenkins-side config store per project; adds/edits/versions keys;
  wires the pipeline steps into a Jenkinsfile.
- **Application developer** — needs to know exactly what token to place in their app's config file for a
  new setting, and needs deploys to fail loudly (not silently) if their token has no matching value.
- **Release/deploy pipeline (automated actor)** — validates and substitutes at deploy time; must fail fast
  and clearly on drift.
- **Operator / on-call** — rolls back a bad config change, or rolls back the server to an older build and
  needs the matching historical config to come back automatically.
- **Plugin maintainer (the owner, longer-term: any external adopter)** — needs the plugin to be installable
  on any Jenkins instance/project without code changes, and to never leak references to any one specific
  consuming project.

**Out of scope for this problem statement:** this plugin is not a secrets manager (it never stores real
secret values — see NFR-2) and not a general-purpose config UI/feature-flag system; it exists specifically
to close the "structural drift between a nested config file and a flat Jenkins-side value store" gap.

</overview>

<user-flows>

## 2. Core user flows

### UF-1 — DevOps engineer adds a new config key
**Actor:** DevOps/Platform engineer. **Trigger:** a new setting must be introduced to an application's
config (e.g. a new feature flag, connection string, or per-env override).
1. Engineer opens the relevant `ConfigSet` (common, or a specific env) and edits its content, adding the
   new key at the correct nested path (or, for an env-layer `ConfigSet`, adding it to the sparse override).
2. Engineer marks the value as secret or non-secret. If secret, engineer supplies the Jenkins credential ID
   that will supply the real value at substitution time — the literal value is never entered here.
3. Engineer saves as a new version, with a mandatory change note, and optionally activates it immediately.
4. System confirms the save (new version number, note, author, timestamp) and, if activation was
   requested, confirms which version is now active for that `ConfigSet`.
5. Engineer requests "Generate template" for the effective (merged) config of a given project+env.
6. System returns a ready-to-paste JSON block, same shape as the target config file, secret leaves and
   plain leaves alike rendered as `#{Dotted.Path}#` tokens.

### UF-2 — Application developer needs the exact template to paste into their app's config file
**Actor:** application developer, possibly with no knowledge of the plugin's internal model.
1. Developer (or the DevOps engineer on their behalf) requests the generated template for their
   project+env (UF-1 step 5–6, or via a documented pipeline/CLI entry point).
2. Developer pastes the returned block into the target config file (e.g.
   `appsettings.Production.json`) at the matching location.
3. Developer commits that repo change. No further manual token-writing should be required — the token
   text is copy-pasted verbatim from the system's own output, eliminating hand-typo drift.

### UF-3 — Deploy pipeline validates config drift before deploying
**Actor:** the deploy pipeline (automated), triggered on every deploy run, before real substitution.
1. Pipeline invokes the validate step with the project's common `ConfigSet`, env `ConfigSet`, and the
   target config file path.
2. System computes the effective (merged common+env) config, flattens it to a set of expected keys,
   extracts the actual `#{...}#` tokens present in the target file.
3. If any token in the file has no matching key in the effective config ("missing"): **the build fails**,
   naming every missing token explicitly.
4. If any key in the effective config has no matching token in the file ("orphaned"): the build **does not
   fail**, but a clear warning is emitted naming every orphaned key.
5. Pipeline proceeds to the next stage only if step 3 found nothing.

### UF-4 — Deploy pipeline substitutes real values at deploy time
**Actor:** the deploy pipeline (automated), after validation passes.
1. Pipeline invokes the substitute step with the same common/env `ConfigSet`s, target file, and (optionally)
   the build/version identifier being deployed.
2. System computes the effective merged config, resolves every secret leaf's real value from the Jenkins
   credential named in that `ConfigSet`'s secrets manifest, resolves any build-stamp values, and replaces
   every `#{Path}#` token in the target file with its real value.
3. If, after substitution, any `#{...}#`-shaped token remains in the file, the step fails loudly (this
   guards against a validate/substitute mismatch or a manifest gap that validation didn't catch).
4. On success, the system records (creates or updates) a deployment binding for that build/version
   identifier, capturing exactly which common-version-number and env-version-number were used — so a later
   rollback of the server to this build can replay the same config (see UF-6).
5. Pipeline proceeds to deploy the now-substituted file.

### UF-5 — Operator rolls back a bad config change
**Actor:** operator/on-call, after discovering a just-activated config version is broken.
1. Operator selects the `ConfigSet` (common or env layer) and its version history.
2. Operator picks a prior version and requests "Activate" (rollback).
3. System flips the `active` flag back onto the chosen historical version and off the currently active
   one — no new version is minted, no content is duplicated.
4. System confirms which version is now active, and confirms the resulting diff versus what was active a
   moment ago, so the operator can visually verify what changed.
5. Operator triggers a new deploy (or a substitute-only re-run) so the rolled-back config takes effect on
   the running server. The system's own rollback action does not by itself push anything to a live server —
   this must be explicit, and the operator must be told so (see Open Questions, OQ-6).

### UF-6 — Operator rolls back a server to an older build and needs the matching old config to come back automatically
**Actor:** operator/on-call, rolling back the *application build*, not just editing config.
1. Operator redeploys (or reactivates) an older build/version identifier through the normal deploy
   pipeline.
2. Pipeline's substitute step (UF-4) receives that older build identifier.
3. System looks up whether a deployment binding already exists for that exact build identifier.
   - If yes: substitution uses the **pinned** common+env version numbers recorded in that binding, not
     whatever is currently marked "active" (which may postdate — and be invalid for — the older build).
   - If no binding exists (e.g. very first deploy of that build, or binding history was pruned): the system
     falls back to "currently active," and must tell the operator explicitly that it did so, since this is
     the case the pinning feature exists specifically to avoid (see Open Questions, OQ-3 on retention/no-op
     visibility).
4. System confirms which config versions were actually used for this substitution run (pinned vs.
   fallback-to-active), so the operator has an auditable answer to "what config did this deploy actually
   use."

### UI reference groundings (added 2026-08-27, folding in `jenkins-woolly-dragonfly.md`)

The §6 out-of-scope note originally deferred the Stapler/Jelly admin UI to "milestone 2." The owner has now
specified that UI concretely, grounded in two existing reference systems rather than a from-scratch mockup
(two earlier free-form mockups — styled HTML/CSS and plain ASCII — were both rejected). Recorded here so a
future reader knows *why* the UI design in FR-30–FR-40 looks the way it does, not just what it says:

- **Interaction-pattern reference — Jenkins' own built-in Manage Jenkins → Managed Files screen.** The
  global "Config Templates" page (FR-30) reuses that screen's list → create/edit interaction shape (the
  plugin replaces the *storage format* Managed Files uses, not the *interaction pattern* a Jenkins admin
  already knows). Versioning, rollback, and compare are additions on top of that familiar shape — Managed
  Files itself has none of the three.
- **Editor-technology reference — Rojet's Monaco-based editor.** Confirmed via a direct code read of
  `D:\Repos\Rojet\license-server-frontend`: Rojet has **no separate "JSON visualizer" component** — its
  label editor and version-diff view are both the **Monaco Editor** (`monaco-editor@^0.56.0`) via
  `MonacoEditorComponent`/`MonacoDiffEditorComponent`, configured with a custom `language: 'ezpl'` mode (see
  `docs/license-server/design/ui-12-one-editor.md` in that repo). This plugin reuses the same editor
  *engine and configuration approach* — Monaco configured with its built-in `language: 'json'` mode instead
  of a custom language — not the Angular *component* itself, since this plugin is server-side Jenkins/Jelly,
  not Angular. Monaco is vendored as a static JS/CSS asset under the plugin's `src/main/webapp/` and wired
  into the Jelly views.

</user-flows>

<entities>

## 3. Key entities and relationships

- **Config Project** (implicit grouping key, e.g. `sample-app`) — not necessarily a first-class persisted
  object, but the unit that ties one common Config Set to one or more env Config Sets together. Every
  Config Set name/key must make this grouping unambiguous (see OQ-2 on naming convention).

- **Config Set** — a named, independently-versioned collection of configuration structured to mirror the
  shape of one target application config file (or the portion of it this tool manages). Exactly two kinds,
  distinguished by role, not by type:
  - **Common Config Set** — the full nested baseline shape+values shared across all environments for a
    given Config Project.
  - **Env Config Set** — a sparse overlay (only the keys that differ from common) scoped to one specific
    deployment environment (e.g. dev/qa/prod) of that Config Project.
  A Config Set also owns a **secrets manifest**: a list of dotted paths within it that are secret, each
  mapped to the Jenkins credential ID that supplies the real value at substitution time. **(Amended
  2026-09-02 — multi-format Config content:** a Config Set additionally owns an immutable `contentType`
  (§`ContentType` below), chosen once at first-save time and never changeable afterward (FR-59). Only a
  COMMON-role Config Set ever chooses its own `contentType`; an ENV-role Config Set never chooses
  independently — it inherits the type of whichever COMMON Config Set(s) appear in its resolved base chain
  (FR-60). See "Relationships" below for the cross-chain consistency guarantee this depends on.)**

- **`ContentType`** *(new entity, added 2026-09-02)* — an enum (`JSON`, `XML`, `YAML`) naming the structured
  format a Config Set's content, and every version of it, is written in. Set once on a COMMON Config Set at
  first-save and frozen thereafter (FR-59); an ENV Config Set never sets its own `ContentType` and always
  reads it by resolving its base chain (FR-60). Every pre-existing Config Set persisted before this feature
  existed is treated, on load, as `ContentType.JSON` with no data rewrite (FR-69). Drives which `TreeFormat`
  adapter (Gson/JSON, JDK-DOM/XML, SnakeYAML/YAML) parses, merges, and serializes that Config Set's content
  end to end — merge (FR-8), validation-on-save (FR-33/FR-65), template generation (FR-15), and the Monaco
  editor's language mode and diagnostics/format wiring (FR-63/FR-64) all key off it.

- **Config Set Version** — an immutable, append-only snapshot of one Config Set's content at a point in
  time: a version number (monotonically increasing, never reused, never renumbered), an author, a
  timestamp, a free-text change note, and the content itself (full JSON for common; merge-patch overlay for
  env). Exactly one version per Config Set is "active" at any moment — active is a pointer, not a copy.
  Rollback = repointing "active" onto an older version; it never mutates or deletes history.
  **(Amended 2026-09-01 — multi-base config chains, see below):** an ENV-role Config Set Version additionally
  carries its own **`baseChain`** — an ordered list of `BaseConfigReference` entries, frozen into that
  specific version's own immutable record at save time (FR-51). A COMMON-role Config Set Version's
  `baseChain` MUST always be empty (FR-53) — only env versions may declare one.

- **`BaseConfigReference`** *(new entity, added 2026-09-01)* — one entry in an env Config Set Version's
  ordered `baseChain`, expressing operator *intent* at save time: a `projectKey` (which base Config Set to
  overlay), a `pinMode` (`ACTIVE` — always track whatever is active for that project at merge/build time, or
  `PINNED` — always use one specific historical version), and a `pinnedVersionNumber` (meaningful only when
  `pinMode` is `PINNED`). Order within the list is significant — later entries overwrite earlier entries' on
  overlapping keys when the chain is folded (FR-8).

- **`ResolvedBaseVersion`** *(new entity, added 2026-09-01)* — one entry in a `ConfigDeploymentBinding`'s
  frozen `resolvedBaseChain`, expressing an already-concrete *result*: a `projectKey` + `versionNumber` pair,
  never a pin mode. Deliberately a distinct type from `BaseConfigReference` so "what was asked for" (intent,
  possibly `ACTIVE` at the time) and "what was frozen" (a concrete version number, forever) are never
  conflated — this distinction is what makes the reproducibility guarantee (FR-54) possible.

- **`explicitlyStandalone`** *(new field on `ConfigSetVersion`, added 2026-09-03 — pipeline-level resolution
  overrides)* — a boolean, meaningful only on an ENV-role `ConfigSetVersion`, defaulting to `false`,
  persisted as part of that version's own immutable record (mirroring how `baseChain` is frozen into a
  version at save time, FR-51). Closes a confirmed gap in the existing model: today, `baseChain == null`
  (legacy data predating the base-chain feature, per `ConfigSetVersion.readResolve()`) and a hypothetical
  `baseChain == []` (an operator deliberately saving zero base-chain rows) are indistinguishable — both
  collapse to `Collections.emptyList()` on load, and FR-52's rule then treats an empty list as "implicitly
  one self-referencing `ACTIVE` entry." `explicitlyStandalone` is a **new, additive** flag, not a
  reinterpretation of emptiness itself — it does not change what an empty/absent `baseChain` already means
  for any existing or newly-created version that leaves it `false` (FR-52 is completely unchanged for that
  case). When `true`, it declares — as a deliberate, first-class, save-time choice — that this env Config Set
  Version has zero base configs and its own content IS the entire effective configuration; see FR-86–FR-89.

- **Effective (Merged) Configuration** — the result of folding an env Config Set Version's resolved
  `baseChain` left-to-right (each later base Config Set's active-or-pinned content merge-patched onto the
  running accumulator, RFC 7396) and then applying the env Config Set's own override content as the final
  RFC 7396 JSON Merge Patch on top of that fold. Not persisted as its own entity — always computed fresh from
  the chain's resolved versions (live-`ACTIVE` or `PINNED`, or the fully-frozen `ResolvedBaseVersion` list on
  a Deployment Binding) at the moment it's needed (validate, substitute, generate-template, or a UI "full
  picture" view). A `null` value at a path in the env overlay removes that key from the effective result; an
  absent path inherits the folded chain's value unchanged. **(Amended 2026-09-01:** generalized from "the env
  version's content over exactly one common Config Set's active version" to "the env version's content over
  an ordered chain of one-or-more base Config Sets' active-or-pinned content" — see FR-8's amendment.)

- **Secret Placeholder** — a reserved marker value stored in place of any real secret at any leaf marked
  secret in the manifest, in both common and env content, at every version, with no exception. The real
  value only ever exists transiently, resolved from a Jenkins credential at substitution time, and is never
  written back into any Config Set Version's persisted content.

- **Deployment Binding** — a record, keyed by Config Project + environment + a Run-identity key, of exactly
  which config versions were used the last time that key was substituted for real (not a validate-only or
  dry-run template generation). Its purpose is singular: let a later rollback of the *application build*
  automatically replay the config that build actually shipped with, instead of whatever is "active" at
  rollback time. One binding exists per (project, environment, key) tuple; it is updated (not duplicated)
  each time that same key is substituted again for real. **(Amended 2026-09-01:** generalized from a single
  `commonVersionNumber` int to an ordered `List<ResolvedBaseVersion>` (`resolvedBaseChain`) — one frozen
  `(projectKey, versionNumber)` pair per entry in the base chain that was actually resolved at substitution
  time — plus the unchanged `envVersionNumber`. A binding created before this feature (single int) is
  treated, on load, as a one-element `resolvedBaseChain` — see FR-23's amendment.) **(Amended 2026-09-03 —
  automatic build-identity pinning:** the key is no longer an author-supplied `buildVersion` string.
  Ordinarily it is the current Jenkins `Run`'s own `run.getExternalizableId()` (FR-96), written
  automatically on every real substitution (FR-97, default-on, no opt-in). For a deliberate cross-Run
  rollback/redeploy, the key instead comes from an explicitly-supplied `redeployFromRun` target identity
  (FR-100). The underlying persisted field/shape (`ConfigDeploymentBinding.buildVersion`, a `String`) is
  UNCHANGED — only what populates it changes, from a manually-typed literal to a Jenkins-native, mechanically
  derived or explicitly-targeted Run identity. No data migration is needed for existing binding records; they
  keep matching by their existing string value exactly as before, they are simply never again written to by
  a hand-typed value going forward.)

**Relationships:**
- One Config Project ↔ one Common Config Set, ↔ many Env Config Sets (one per environment).
- One Config Set ↔ many Config Set Versions (1:N, append-only); exactly one is "active" at a time.
- **(Amended 2026-09-01):** an Env Config Set no longer implicitly pairs with exactly one Common Config Set
  via shared `projectKey` alone. Each Env Config Set Version instead carries its own ordered `baseChain` (1:N
  Config Set Version ↔ `BaseConfigReference`, order-significant) — which, for full backward compatibility,
  defaults to exactly one self-referencing entry (this env's own `projectKey`, `ACTIVE`) when empty/absent
  (FR-52), reproducing today's one-common-Config-Set pairing unchanged for any operator who never touches the
  base-chain editor. A chain entry may reference any Config Set of COMMON role, in any Config Project
  (NFR-8) — never another ENV Config Set, and never recursively through another chain (FR-53).
- One (Config Project, Environment) pair ↔ many Deployment Bindings (one per distinct build identifier ever
  substituted for real). One Deployment Binding ↔ many `ResolvedBaseVersion` entries (1:N, order-significant,
  mirroring the env version's resolved `baseChain` at the moment of that real substitution).
- Effective Configuration is a derived, non-persisted view over (the env Config Set Version's resolved
  `baseChain`, folded left-to-right, active/pinned/frozen per context) ⊕ (the env Config Set's own
  active/pinned override content, applied last).
- **(Added 2026-09-02 — multi-format Config content):** every Config Set participating in one resolved base
  chain (the env Config Set itself plus every COMMON Config Set its chain references, transitively resolved
  per entry) MUST share exactly one `ContentType` — you cannot RFC-7396-merge-equivalent-fold JSON with
  XML/YAML content. This is a new invariant layered onto the relationship above, not a replacement of it —
  see FR-61/FR-62.

</entities>

<functional-requirements>

## 4. Functional requirements

Each requirement is atomic and testable. IDs are stable for traceability into design/test artifacts.

**Config Set & versioning**

- **FR-1**: The system MUST allow creating a Config Set of either role (common or env) identified by a
  unique key, scoped to a Config Project + (for env role) an environment name.
- **FR-2**: The system MUST store each Config Set Version as an immutable, append-only record; saving a new
  version MUST NOT alter or delete any prior version's content.
- **FR-3**: The system MUST allow exactly one active version per Config Set at any time — activating a
  version MUST atomically deactivate whichever version was previously active for that same Config Set.
- **FR-4**: The system MUST assign version numbers that are monotonically increasing and never reused, even
  after a version is superseded or a Config Set's active pointer is rolled back.
- **FR-5**: The system MUST require a non-empty change note and capture an author and timestamp on every
  saved version.
- **FR-6**: Rollback (reactivating a historical version) MUST NOT create a new version or duplicate
  content — it repoints the active pointer only, and full version history (including versions that predate
  the currently active one) MUST remain visible and re-activatable indefinitely (see OQ-4 on retention).
- **FR-7**: The system MUST let a user (or an automated caller) retrieve a diff between any two versions of
  the same Config Set.

**Common + env merge**

- **FR-8**: For an env Config Set, the system MUST treat its content as an RFC 7396 JSON Merge Patch applied
  LAST, on top of the effective result of folding its version's ordered **base chain** — one or more base
  Config Sets' (active or pinned) content, folded left-to-right, each later base overwriting the running
  fold's overlapping keys — to produce the effective configuration; never as a full standalone duplicate of
  any base's unrelated keys. **(Amended 2026-09-01 — multi-base config chains:** generalizes the original
  wording ("the corresponding common Config Set's ... content") from exactly one implicit base to an ordered
  chain of one-or-more `BaseConfigReference` entries (§3); a chain of exactly one entry pointing at this env's
  own project, `ACTIVE`, reproduces the original pre-chain behavior unchanged — see FR-52's backward-
  compatibility rule. See `merge/EffectiveConfigResolver#resolveChain` in the approved plan for the fold
  algorithm: RFC 7396 application applied repeatedly needs no new algorithm.)**
- **FR-9**: A `null` value at a path in an env overlay MUST remove that key from the effective configuration
  (RFC 7396 semantics), evaluated after the full base chain has been folded (FR-8); an absent path MUST
  inherit the folded base chain's value unchanged. **(Amended 2026-09-01:** "common's value" generalized to
  "the folded base chain's value" — see FR-8.)**
- **FR-10**: The system MUST compute the effective configuration on demand from the current active (or
  pinned) version of every Config Set named in the env version's resolved base chain, plus the env layer's
  own active (or pinned) version — it MUST NOT persist the effective configuration, or any intermediate fold
  state, as a separate, independently-updatable copy that could drift from its sources. **(Amended
  2026-09-01:** generalized from "both layers" (exactly one common + one env) to "every layer named in the
  chain, plus env" — see FR-8.)**
- **FR-11**: The system MUST allow rolling back the env layer and any individual base Config Set named in its
  chain independently — rolling back one MUST NOT require or force a change to any other's active version.
  **(Amended 2026-09-01:** generalized from "the common layer" (necessarily singular) to "any individual base
  Config Set named in its chain," since a chain may now reference more than one base — the independence
  guarantee itself is unchanged, just restated for N bases instead of exactly one.)**

**Multi-base config chains (base-chain domain model, added 2026-09-01)**

These formalize the plan's decisions-made section
(`C:\Users\retro\.claude\plans\jenkins-woolly-dragonfly.md`) as testable rules, closing FR-8's generalization
above with the persistence, backward-compatibility, and layering-safety guarantees it depends on.

- **FR-51**: An env Config Set Version MUST persist its own `baseChain` (the ordered list of
  `BaseConfigReference` entries — project + `ACTIVE`/`PINNED` + optional pinned version number — chosen by the
  operator at save time) as part of that version's own immutable, append-only record (FR-2). The pin choice
  made when a specific version was saved MUST remain visible in that version's own history forever, and MUST
  NOT be inferable only from the parent Config Set's current/mutable state.
- **FR-52**: An env Config Set Version whose `baseChain` is empty or absent MUST be treated, for every merge,
  validate, substitute, and template-generation purpose, as exactly equivalent to a single-element chain of
  `[BaseConfigReference(this env's own projectKey, ACTIVE)]`. This is the explicit backward-compatibility
  rule: every env Config Set Version saved before this feature existed, and any newly-created one whose
  operator never touches the base-chain editor, MUST behave identically to today's single-base model, with no
  data migration or rewrite required. (Corresponds to `StepSupport.effectiveBaseChain` in the approved plan —
  both pipeline steps and the UI MUST call this synthesized-default logic rather than reading a version's raw
  `baseChain` field directly.)
- **FR-53**: A Config Set of COMMON role MUST NOT be permitted to carry a non-empty `baseChain` on any of its
  versions — saving a COMMON-role version with a non-empty `baseChain` MUST be rejected (fail the save, not
  silently truncated to empty) . This enforces the no-recursive-layering decision: only ENV Config Set
  Versions may declare a base chain, and every chain entry always resolves to a referenced Config Set's own
  flat, unchained content — never expanded recursively through that referenced set's own (nonexistent) chain.

**Secrets**

- **FR-12**: The system MUST NOT allow a real secret value to be persisted in any Config Set Version's
  stored content, in either layer, at any time — every leaf declared secret in the manifest MUST hold only
  the reserved placeholder marker.
- **FR-13**: The system MUST resolve real secret values only at substitution time, exclusively from the
  Jenkins credential ID declared for that path in the owning Config Set's secrets manifest, and MUST NOT
  write the resolved value back into any persisted Config Set content.
- **FR-14**: The system MUST reject (fail validation, or refuse the save) a Config Set Version whose content
  contains a literal secret-shaped value at a path declared secret in its manifest — see OQ-1 for how
  "looks like a real secret" is detected/enforced, since this can't be fully automated with certainty.

**Template generation**

- **FR-15**: The system MUST generate, on request, a copy-paste-ready JSON template, in exactly the nested
  shape of its source content, with every leaf value (secret-manifest-bound or not) replaced by its
  `#{Dotted.Path}#` token — the identical token syntax already produced/consumed by
  `TokenExtractor.TOKEN_PATTERN` (`#\{([^}]+)\}#`) for validate (FR-17) and substitute (FR-21), so a
  generated template's tokens are guaranteed round-trippable through that same drift-detection logic with
  zero translation. Template generation MUST be available at two scopes:
  - **(a) Common-only template** — walks a common Config Set's active version content alone (its own
    nested shape, unmerged), for a DevOps engineer who wants the project-wide baseline template independent
    of any one environment.
  - **(b) Effective (merged) template** — walks the EFFECTIVE configuration (FR-8/FR-9/FR-10: the paired
    common Config Set's active version ⊕ the env Config Set's active version, RFC 7396) for a given
    (Config Project, environment) pair. This is the actual paste-ready template for a target environment's
    config file (UF-2) and is the primary, most-used entry point of the two.
  - **Leaf/shape edge cases** (inherited from the already-implemented `JsonPaths.flatten`/`JsonPaths.get`
    semantics, restated here so they are an explicit, testable part of template generation rather than an
    incidental side effect): an empty JSON object at any path contributes no token and renders as `{}`
    unchanged; an array-valued leaf is tokenized as a single whole-leaf token at its own path (per OQ-8,
    arrays are never recursed into element-by-element); a `null`-valued leaf in a common Config Set's own
    content is tokenized like any other leaf (the RFC 7396 "null removes" rule only applies to an ENV
    overlay's relationship to common, per FR-9 — it does not apply within a single Config Set's own content
    being walked for FR-15a).
- **FR-16**: Template generation MUST NOT introduce a second, divergent computation for "what this
  configuration looks like":
  - The common-only template (FR-15a) MUST walk a `ConfigSet`'s already-stored active version content
    directly — no separate re-fetch or re-serialization path that could drift from what FR-2/FR-3 already
    define as "the active version's content."
  - The effective template (FR-15b) MUST call the same `EffectiveConfigResolver` class the pipeline steps
    (FR-10) and the env-level live-merge-preview endpoint (FR-38) already use.
  - Template generation MUST ALWAYS operate on the CURRENTLY ACTIVE version(s) of the relevant Config
    Set(s) (or, when a pinning context is explicitly supplied by an automated caller, the PINNED versions
    per FR-25) — **never an unsaved/in-progress editor buffer**, even when invoked from a Config Set's own
    edit page while that page has unsaved edits pending. This is a deliberate, explicit divergence from
    FR-38's live-merge-preview behavior (which intentionally DOES read the unsaved override buffer, for
    in-progress-editing UX): a generated template is a paste-ready artifact a developer will actually
    consume and commit, so it must always reflect a real, saved, versioned state — never a hypothetical,
    unsaved one that might be discarded a moment later.

**Validation (drift detection)**

- **FR-17**: The pipeline validate step MUST compute the effective configuration, flatten it to a set of
  expected dotted paths, extract the actual `#{...}#`-shaped tokens present in the named target file, and
  compare the two sets.
- **FR-18**: The validate step MUST fail the build and name every token present in the target file with no
  matching key in the effective configuration ("missing keys").
- **FR-19**: The validate step MUST NOT fail the build for a key present in the effective configuration with
  no matching token in the target file ("orphaned keys") — it MUST instead emit a non-fatal warning naming
  every such key.
- **FR-20**: Validation MUST run, and MUST be able to fail the build, strictly before the substitute step
  runs in a well-formed pipeline (this is a pipeline-authoring convention this plugin must make possible,
  not something the plugin can itself enforce ordering-wise — see NFR list and OQ-7).

**Substitution (deploy-time)**

- **FR-21**: The substitute step MUST compute the effective configuration, resolve every secret leaf's real
  value via its manifest-declared credential, replace every matching `#{Path}#` token in the target file
  with its real value, and leave non-matching text in the file untouched.
- **FR-22**: The substitute step MUST fail (not silently succeed) if any `#{...}#`-shaped token remains
  unresolved in the target file after substitution completes.
- **FR-23**: On a successful real substitution run (not a dry-run/validate-only/template-generation call),
  the system MUST create or update a Deployment Binding for that (project, environment, Run-identity key)
  recording the exact ordered list of `ResolvedBaseVersion` entries (one concrete `(projectKey,
  versionNumber)` pair per entry in the resolved base chain, §3) and the env-version-number used. **(Amended
  2026-09-01 — multi-base config chains:** generalizes the original single `commonVersionNumber` int to
  `List<ResolvedBaseVersion> resolvedBaseChain`, matching `ConfigDeploymentBinding`'s new field; a binding
  persisted before this change (single int) is treated, on load, as a one-element `resolvedBaseChain` via
  `readResolve()` — no data-rewrite migration is required. See FR-54 for the reproducibility guarantee this
  generalization exists to serve.)** **(Amended 2026-09-03 — automatic build-identity pinning:** "supplies a
  build/version identifier" is struck — a Run-identity key is now ALWAYS present for every real substitution
  (FR-96), so the binding write is unconditional (default-on, FR-97) rather than gated on the caller having
  passed anything. The one exception is unchanged from the FR-79–85 pass: an explicitly-supplied `version`
  parameter still suppresses this write for that call, per FR-82/FR-98.)**
- **FR-24**: The substitute step MUST report which config version numbers were actually applied for every
  real substitution run, enumerating every base in the resolved chain by `projectKey`+`versionNumber` (not
  only a single common version), plus the env-version-number. **(Amended 2026-09-01: generalized from "which
  config version numbers" (implicitly two) to "every base in the resolved chain, plus env.")** **(Amended
  2026-09-03: "The substitute step MUST accept an optional build/version identifier; when supplied," is
  struck — reporting now happens unconditionally on every real run, since a Run-identity key (FR-96) is
  always present; there is no longer a caller-supplied identifier whose presence gates this reporting.)**

**Build-version pinning (rollback safety)**

- **FR-25**: When a substitute run's Run-identity key matches an existing Deployment Binding, the system MUST
  substitute using that binding's frozen `resolvedBaseChain` (already concrete `ResolvedBaseVersion` entries
  — no `ACTIVE`/`PINNED` re-resolution of any reference) plus its recorded env-version-number, instead of
  resolving any reference in the env version's own current `baseChain` live. **(Amended 2026-09-01:
  generalized from "the pinned common+env version numbers" (a single pinned int) to "the binding's frozen
  `resolvedBaseChain`" (a list) — the resolution rule (frozen data wins over live "active") is otherwise
  unchanged.)** **(Amended 2026-09-03 — automatic build-identity pinning:** "supplies a build/version
  identifier for which a Deployment Binding already exists" is generalized to "a substitute run's Run-identity
  key matches an existing Deployment Binding" — that key is now either (a) the current Run's own
  automatically-derived identity (FR-96), the ordinary case, since a Run substituting a second time (e.g. a
  Pipeline restart-from-stage) naturally re-finds its own prior binding, or (b) an explicitly-supplied
  `redeployFromRun` target identity (FR-100), for the deliberate cross-Run rollback case UF-6 describes. This
  rule is otherwise unchanged: whichever key applies, a match means "use the frozen chain," full stop.)**
- **FR-26**: When a substitute run's Run-identity key has no existing Deployment Binding, the system MUST
  fall back to resolving the env version's own current base chain live (via the `effectiveBaseChain` rule,
  FR-52) AND MUST surface (in build output/log) that it did so, naming every reference resolved this way
  (`projectKey` + the version number it resolved to), so the operator is never left assuming a pin was
  honored when it wasn't. **(Amended 2026-09-01: generalized from "fall back to the currently active
  versions" (singular common) to "resolve the env version's current base chain live," and made the fallback
  log line explicitly per-reference instead of implicitly per-layer.)** **(Amended 2026-09-03 — automatic
  build-identity pinning:** this warning is now reserved for the two genuine-gap cases only: (a) an explicit
  `redeployFromRun` target was supplied and no binding exists for it (FR-103) — this is very likely an
  operator typo/wrong build number and MUST be surfaced loudly; (b) a binding that once existed under the
  current Run's own automatic identity was somehow lost. It MUST NOT fire for a brand-new Run's own first-ever
  real substitution under its own automatic identity — that case has no binding by definition (this Run has
  never substituted before) and is not a "fallback" from anything an operator expected; see FR-27's amendment
  for that non-warning path.)**
- **FR-27**: On a substitute run's very first real substitution under its own automatically-derived Run
  identity (FR-96) — which, by definition, cannot yet have an existing Deployment Binding — the system MUST
  resolve the env version's current base chain live (FR-52) with no pinning behavior and MUST NOT emit FR-26's
  fallback warning for this specific, expected case (there was nothing an operator could have expected to be
  pinned yet for a Run that has never substituted before). **(Amended 2026-09-01: generalized from "use the
  currently active versions" (singular common) to "resolve the current base chain live.")** **(Amended
  2026-09-03 — automatic build-identity pinning:** this FR's original meaning — "caller didn't opt into
  pinning by omitting the `buildVersion` parameter" — no longer applies, since pinning is now default-on
  (FR-97) and there is no longer an identifier a caller can omit to opt out (see OQ-3's updated resolution,
  §7). FR-27 is repurposed, as above, to carve out the one case where "no matching binding" is normal and
  silent rather than a warned-about fallback.)**
- **FR-54**: Re-substituting under the same Run-identity key after any base Config Set referenced by that
  key's Deployment Binding has since had its active version change MUST produce byte-identical merged
  (effective) output to the original substitution run for that key — this is the core reproducibility
  guarantee the FR-23/FR-25 generalization exists to serve, and it is a hard, testable MUST, not descriptive
  prose. Acceptance criterion (mirrors the plan's own verification scenario): substitute under a given
  Run-identity key; change a referenced base Config Set's active version; re-substitute under the identical
  key a second time; assert the two runs' merged output strings are byte-identical (not merely
  semantically-equivalent JSON) — this MUST be covered by an explicit end-to-end pipeline-step test, not
  inferred from unit tests of the fold algorithm alone. **(Amended 2026-09-03 — automatic build-identity
  pinning:** "the same `buildVersion`" is generalized to "the same Run-identity key" throughout — the
  acceptance criterion is unchanged in substance; it now exercises FR-96's own-Run automatic key (the direct,
  literal analogue of the old test: re-running `configTemplateSubstitute` a second time within logic that
  simulates the same `Run`) AND MUST gain a second, additive test scenario exercising FR-100's
  `redeployFromRun` cross-Run path — substitute under Run A's own key, then substitute under a DIFFERENT
  Run B supplying `redeployFromRun` targeting Run A, and assert Run B's merged output is byte-identical to
  Run A's original output, even after Run A's base Config Set's active version has since changed. This second
  scenario is the one that actually proves UF-6's cross-Run rollback case works, not just the same-Run case
  the original FR-54 test covered.)**

**Automatic build-identity pinning — replaces manual `buildVersion` (added 2026-09-03)**

These formalize an owner-confirmed amendment to the already-shipped build-version-pinning feature above
(FR-23–FR-27/FR-54). Grounded against the actual pushed code: `ConfigTemplateSubstituteStep`'s
`@DataBoundSetter String buildVersion` field and its exact usage in `Execution.run()`
(`hasBuildVersion`/`existingBinding` branching), `ConfigDeploymentBinding`'s `buildVersion` field and
`ConfigDeploymentBindingRepository.find`/`.save`'s `(projectKey, environment, buildVersion)` key shape — none
of which need a persisted-schema change; only what supplies the `buildVersion` string changes.

- **FR-96**: `configTemplateSubstitute` MUST NOT accept an author-supplied `buildVersion` parameter (removed
  — see the "Migration / breaking change" note below). Instead, on every invocation, the step MUST derive its
  own Run-identity key internally, with zero pipeline-author involvement, as `run.getExternalizableId()` —
  Jenkins core's own guaranteed-unique-per-build identifier (`Run#getExternalizableId()`, format
  `<jobFullName>#<buildNumber>`, the same identifier `Run.fromExternalizableId(String)` resolves back into a
  `Run`) — obtained from the `Run` object already present in this step's `StepContext`
  (`getContext().get(Run.class)`, already required context per the existing `DescriptorImpl
  .getRequiredContext()`, no new required-context class needed for this part). This key is used everywhere
  FR-23–FR-27/FR-54 refer to "the Run-identity key."
- **FR-97**: On every successful real substitution run (FR-23, unchanged trigger condition — not a
  dry-run/validate-only/template-generation call), the system MUST unconditionally create or update a
  Deployment Binding keyed by the current Run's own automatically-derived identity (FR-96) — this is
  **default-on**, not opt-in: there is no parameter to omit to skip this, and no separate opt-out flag is
  introduced (see OQ-3's updated resolution in §7 for why). The sole exception, carried over unchanged from
  the FR-79–85 pass, is FR-98 below.
- **FR-98**: An explicitly-supplied `version` parameter (FR-79–82) on a `configTemplateSubstitute` call MUST
  continue to suppress the Deployment Binding write for that specific call (both the lookup AND the save —
  restating FR-82's own already-established interpretation, now generalized off "when `buildVersion` is also
  supplied" to "regardless of whether a Run-identity binding would otherwise exist or be written," since a
  binding write is no longer conditional on anything being supplied). Reasoning is unchanged from FR-82: a
  deliberate one-off `version` override must never corrupt a Run's own "natural" binding history.
- **FR-99**: **Finding — an automatically-derived, single-Run-scoped key cannot by itself serve UF-6's
  cross-Run redeploy scenario, and this MUST NOT be silently assumed to work.** `run.getExternalizableId()`
  is, by construction, unique to exactly the one `Run` that computed it. A Jenkins "Replay" of a pipeline, an
  ordinary rebuild, or a separate promotion/rollback job that redeploys an already-built artifact from an
  older build ALL allocate a brand-new build number for the new `Run` — meaning a rollback/redeploy job's own
  automatically-derived key can NEVER equal the original build's key. If UF-6 is read as "any later
  redeploy — including from a different Run — must be able to replay an older build's config automatically,
  with zero extra input," the automatic-Run-identity mechanism (FR-96/FR-97) alone does not satisfy it: it
  only ever serves the narrower case of the SAME Run substituting more than once (e.g. a Pipeline
  restart-from-stage). FR-100–FR-103 close this gap with a narrow, explicit override reserved for exactly
  this deliberate case.
- **FR-100**: `configTemplateSubstitute` MUST accept a new optional parameter, `redeployFromRun` (String,
  `@DataBoundSetter`, absent by default), whose value is either a bare integer (interpreted as a build number
  of the CURRENT job) or a full `<jobFullName>#<buildNumber>` string (for redeploying a different job's build
  — the same shape `run.getExternalizableId()`/`Run.fromExternalizableId()` already use). When supplied, the
  Deployment Binding lookup (FR-25) MUST use the identity this parameter resolves to instead of the current
  Run's own automatically-derived identity (FR-96) — this is the one, narrow, still-manual input this
  amendment retains, and it is retained deliberately: expressing "roll back to a specific prior build" is
  inherently a deliberate, rare, operator-driven act of naming a target, not a routine per-deploy burden — it
  is supplied only on the rollback/redeploy call, never on the vastly more common ordinary-deploy call, and
  the value supplied is a small integer Jenkins itself already displays prominently in its own build-history
  UI, not an arbitrary free-text string the pipeline author must independently invent and keep consistent.
  Exact parameter split (a single string vs. two separate `redeployFromJob`/`redeployFromBuildNumber` fields)
  is a `tech-lead` implementation-shape decision, not mandated here.
- **FR-101**: **Precedence ordering**, extending FR-82/FR-98's existing rule: an explicit `version` (FR-79–82)
  MUST take precedence over everything below it and suppresses all binding lookup/save for that call,
  regardless of `redeployFromRun`. Absent `version`: an explicit `redeployFromRun` (FR-100) MUST take
  precedence over the current Run's own automatic identity for the BINDING LOOKUP (FR-25) — i.e. when
  `redeployFromRun` is supplied, the step looks up the TARGET Run's binding, not its own. Absent both: the
  current Run's own automatically-derived identity (FR-96) governs, per FR-25/26/27 as amended.
- **FR-102**: On a successful real substitution where `redeployFromRun` was supplied and a matching binding
  was found and used (FR-25/FR-101), the system MUST, in addition, still create/update a fresh Deployment
  Binding keyed by the CURRENT Run's own automatically-derived identity (FR-96/FR-97's default-on rule is not
  suspended by `redeployFromRun` — it composes with it), capturing the identical resolved chain that was just
  pinned from the redeploy target. This lets a future rollback name THIS run as its own redeploy target,
  chaining reproducibility forward instead of only ever being able to point back at the original source
  build.
- **FR-103**: If `redeployFromRun` is supplied but no Deployment Binding exists for the identity it resolves
  to, the system MUST emit FR-26's fallback warning (naming the unresolved `redeployFromRun` value explicitly,
  not only the generic "no binding" message) and fall back to resolving the CURRENT env version's own current
  base chain live (FR-52) — it MUST NOT silently substitute the target Run's live-active config nor abort the
  build, since a missing target binding here is very likely an operator-supplied wrong build number and the
  operator needs an unambiguous, actionable signal (NFR-7), not a build failure that could itself block an
  urgent rollback attempt.

**Migration / breaking change — removal of `buildVersion`.** Any existing Jenkinsfile calling
`configTemplateSubstitute(..., buildVersion: '<literal>')` WILL break once this amendment ships, because
`buildVersion` is being removed as a real, typed pipeline parameter (FR-96), not merely deprecated in place.
Two options were weighed:
1. **Hard removal (recommended).** Deleting the `@DataBoundSetter setBuildVersion` outright means any
   surviving call site fails loudly and immediately (a `NoSuchMethodError`-class Pipeline DSL failure naming
   the unrecognized `buildVersion` argument) the next time that Jenkinsfile runs.
2. **Silent-ignore deprecation period.** Keep the setter accepting the value but never read/act on it for one
   release, so old call sites keep compiling and running with no error.
**Recommendation: hard removal.** This codebase's own NFR-7 ("fail loud, never fail silent") is explicit and
already owner-approved elsewhere in this document, and option 2 is a textbook violation of it: a Jenkinsfile
would keep passing `buildVersion: '1.2.3'` believing pinning is still keyed off that value, while the step
silently does something else entirely (auto-derives its own key) underneath — exactly the "operator
unknowingly relying on behavior that silently didn't apply" failure mode OQ-3/FR-26 already exist to prevent
in the adjacent pinning-fallback case. A loud, immediate break that names the exact removed parameter is
easier to diagnose and fix (delete one now-meaningless argument from the call site) than a silent behavior
change that could go unnoticed for a full release cycle. This plugin is also not yet on a public Update
Center (§6) with an unknown population of external adopters to protect against a hard break — the known
call-site surface is small and directly controllable, which is exactly the situation where paying the
one-time break cost now is cheaper than carrying a deprecation shim.

**SDLC follow-up:** FR-96–FR-103 have no wireframe/UI dependency (pipeline-step-only, mirroring FR-79–95's
own note) and can proceed to `tech-lead` for contract review immediately — but see the v10 header note's
flag: `tech-lead`'s existing FR-82 analysis in
`docs/development/tech-lead-pipeline-resolution-controls-review-2026-09-03.md` §3a was written against the
now-superseded opt-in `hasBuildVersion` model and needs a re-read against FR-96–FR-103's default-on model
before implementation.

**Portability / multi-tenancy**

- **FR-28**: The system MUST support any number of independent Config Projects, each with its own Config
  Sets, versions, and Deployment Bindings, isolated from one another, on a single Jenkins instance.
- **FR-29**: Nothing in the plugin's persisted data model, code, or generated output (including generated
  templates) MUST require, assume, or hardcode any specific consuming project's name, key names, or file
  paths — every project-specific value MUST be supplied as configuration/parameters at the point of use.

**Admin UI — global level ("Config Templates")**

These formalize the UI specification captured informally in `jenkins-woolly-dragonfly.md`. Where an FR is
purely a UI-visible surface over an already-approved domain rule from §4 above, it is cross-referenced
rather than restated.

- **FR-30**: The system MUST provide a global "Config Templates" admin screen, modeled on Jenkins' own
  Manage Jenkins → Managed Files interaction pattern (list of named Config Sets → create/edit page), listing
  every Config Set of common role and allowing creation of a new one and opening any existing one for edit.
  See "UI reference groundings" in §2 for why this specific pattern was chosen.
- **FR-31**: The global Config Set edit page MUST surface, as visible UI actions, the version-history list,
  the "activate" (rollback) action, and a "compare" action, click-driven from the version-history table
  (no checkbox selection step), that diffs the editor's current draft (including unsaved edits) against
  the single history row most recently clicked — this is a UI-visible surface over FR-2 (append-only
  versions), FR-3 (exactly one active version), FR-6 (rollback repoints without duplicating), and FR-7
  (diff between two states); it MUST NOT introduce any new version-management semantics beyond what
  FR-2/3/6/7 already define. **(Corrected 2026-09-01: earlier wording described comparing "any two
  selected historical versions," which does not match the approved, already-implemented click-to-compare
  design — draft-vs-one-clicked-version, not version-vs-version. FR-45–FR-47 below formalize the exact
  compare state machine and the remaining interaction-state gaps this correction surfaced.)**
- **FR-32**: The global Config Set edit page's content editor MUST be a Monaco Editor instance configured
  with `language: 'json'`, providing syntax highlighting and structural editing aids (bracket/brace
  matching, auto-closing pairs) — not a plain text box. See "UI reference groundings" in §2 (Rojet Monaco
  reference).
- **FR-33**: On save, the system MUST validate that the editor's content is syntactically valid JSON and
  MUST block the save (with an error shown in the UI) if it is not. This validation is syntax-only — the
  system MUST NOT perform JSON Schema or field-type/shape validation against any per-project schema, since
  no such schema concept exists in the domain model (explicitly scoped down by the owner; do not expand
  without a new decision).

**Admin UI — env level (inside a project's environment folder)**

- **FR-34**: The system MUST provide an env-level admin screen, scoped to one (Config Project, environment)
  pair, following the same list/edit/version/rollback/compare shape as FR-30/FR-31, applied to that
  environment's Env Config Set instead of a Common Config Set.
- **FR-35**: The env-level screen MUST provide a picker to select which Common Config Set (global "Config
  Templates" entry) the env layer overrides. This UI-visible selection is the (project, environment) pairing
  already defined in §3 Entities and FR-1 — the picker MUST NOT introduce a second, independent pairing
  mechanism.
- **FR-36**: The env-level screen MUST display a read-only preview of the selected Common Config Set's
  (active) content, positioned as the leftmost of the three panels described in FR-38.
- **FR-37**: The env-level screen's editable panel (center of the three panels in FR-38) MUST hold and
  persist only the sparse RFC 7396 merge-patch overlay for that environment — never a full duplicated copy
  of the global content. This is a UI-visible surface over FR-8/FR-9/FR-10 (already-approved merge model);
  the same Monaco/`language: 'json'` editor and JSON-syntax-only save validation from FR-32/FR-33 apply here.
- **FR-38**: The env-level screen MUST lay out three panels left to right — **Global (read-only)** | **Env
  override (editable)** | **Merged result (read-only)** — modeled on a git 3-way merge view. The Merged
  result panel MUST recompute and re-render on every edit to the Env override panel, without requiring an
  explicit "preview" action, applying RFC 7396 merge-patch semantics (a `null` value at a path in the
  override removes that key from the merged result; an absent path inherits the global value unchanged) —
  the same `EffectiveConfigResolver` merge logic already specified for the pipeline steps (FR-8/FR-9/FR-10),
  now also driving this live UI preview, so there MUST NOT be a second, divergent merge implementation for
  the UI (see OQ-9 on where this recomputation executes).
- **FR-39**: The env-level override MUST have its own, independent version history (save/list/activate/
  compare), separate from the Common Config Set's own version history — this is a UI-visible surface over
  FR-11 (independent rollback of each layer): activating a version of one MUST NOT require or force a change
  to the other's active version.

**Admin UI — template generation (formalizes FR-15/FR-16 as a UI-visible action; added 2026-09-01)**

These give FR-15/FR-16 a concrete UI trigger, closing the gap §6 previously flagged ("no specified UI
trigger yet" for generate-template). Where an FR is a UI-visible surface over an already-approved FR-15/16
rule, it is cross-referenced rather than restated, following the same convention FR-30–FR-39 already use.

- **FR-40**: The global Config Set edit page (FR-31) MUST provide a "Generate Template" UI action. It MUST
  be visible whenever the Config Set has at least one active version, and MUST be disabled (with a short
  explanatory label, e.g. "no active version yet") when it does not — a Config Set with no active version
  has nothing to template, and silently doing nothing on click would violate NFR-7's fail-loud principle
  applied to a UI affordance. Invoking it MUST retrieve the common-only template (FR-15a) for this Config
  Set and present it per FR-42. It MUST NOT alter or replace the behavior of any existing button on this
  page (Save, Save & Activate, Activate, Compare).
- **FR-41**: The env-level Config Set edit page (FR-34) MUST provide an equivalent "Generate Template" UI
  action, generating the effective merged template (FR-15b) computed from the common and env Config Sets'
  ACTIVE versions — explicitly NOT from the live 3-panel Merged-result preview's in-progress buffer (see
  FR-16's active-not-draft rule), even though both panels visually show "the merged result" and sit near
  each other on the same page. It MUST NOT alter or replace the behavior of any existing button or panel on
  this page (Save, Save & Activate, Activate, Compare, or the three merge panels of FR-38).
- **FR-42**: Generated-template output MUST be presented in a read-only Monaco Editor instance
  (`language: 'json'`, `readOnly: true` — reusing the same editor engine/configuration already mandated by
  FR-32/FR-36, not a new UI component) plus a "Copy to clipboard" action using the standard browser
  Clipboard API. Secret-manifest-bound leaves MUST render as the identical `#{Dotted.Path}#` token text as
  any other leaf in the copyable output — no distinguishing marker, comment, or visual difference in the
  copied text itself (JSON has no comment syntax, and any such marker would corrupt paste-ready validity).
  An optional, purely cosmetic, on-screen-only (never part of the copied text) highlight of secret-bound
  rows in the preview is permitted as a future nice-to-have but is NOT a requirement of this increment.
  Downloading the template as a file is explicitly deferred (not part of this increment) — copy-to-clipboard
  alone satisfies UF-2's "paste into appsettings.json" flow; see the out-of-scope update in §6.
- **FR-43**: Generating a template, at either scope, MUST require the same `Jenkins.ADMINISTER` permission
  already enforced on every other Config Set admin action on these pages (FR-30/FR-34's existing gate) — no
  new, weaker permission tier is introduced for template output, since a template's key-path structure alone
  can itself be sensitive information about an application's configuration surface even though it never
  contains a real value.
- **FR-44** *(implementation guidance, not a testable behavioral requirement — included so an implementer
  does not have to rediscover an already-documented trap)*: this plugin's existing Stapler dispatch pattern
  requires every classic `doXxx` method's URL segment to never collide with a `@JavaScriptMethod`-annotated
  sibling exposed on the same object (documented in `ConfigSetPage#doActivateVersion`'s javadoc, with a
  confirmed real incident from the activate/compare/preview-merge endpoints). Template generation SHOULD
  follow the same two-method-pair shape (a classic `doXxx` method plus a distinctly-named JS-proxy sibling)
  already used for `doActivateVersion`/`activate`, `doDiffVersions`/`compareVersions`, and
  `doComputeMerge`/`previewMerge`. Exact method and URL-segment names are a `tech-lead`/implementer decision,
  not mandated here.

**Admin UI — compare-mode state machine and busy/disabled states (added 2026-09-01, versioning-parity
audit)**

These close gaps found when this plugin's Save/Activate/Compare UX was audited against a reference
versioning specification the owner asked it to mirror structurally. Where an FR is a UI-visible surface
over an already-approved rule, it is cross-referenced rather than restated, following the same convention
as FR-40–FR-44.

- **FR-45**: Compare mode (FR-31/FR-39) MUST expose exactly three ways to leave it and return to Edit mode,
  and all three MUST leave the draft in the identical state — exactly what it was immediately before the
  compared history row was clicked (no partial state, no merge):
  - **(a) An explicit "Back to editing" button**, always visible alongside the compare banner while in
    Compare mode, distinct from and in addition to the "Load into editor" button.
  - **(b) Re-clicking the already-selected history row.** This MUST have an effect identical to (a) — it
    MUST NOT be treated as a substitute that makes (a) optional; both affordances MUST exist
    simultaneously.
  - **(c) "Load into editor"** is a separate, fourth affordance, not one of the three no-op exits: it MUST
    copy the compared version's content into the draft (replacing whatever was there) and then return to
    Edit mode. It MUST NOT activate the compared version — activation only ever happens via the "Activate"
    action on a history row (FR-31) or via a subsequent "Save & Activate" from the now-populated draft.
- **FR-46**: The system MUST show a busy/disabled UI state while a Save/Activate action is in flight, so a
  user cannot fire a second conflicting action before the first completes:
  - While a "Save" or "Save & Activate" submission is in progress, BOTH save buttons MUST be disabled
    until the request completes (success or failure).
  - While an "Activate" call for one history row is in progress, that row's Activate button MUST show a
    busy/disabled state, AND every other history row's Activate button MUST also disable for the same
    Config Set — only one activate call may be in flight at a time per Config Set.
- **FR-47**: The "Activate" button for the version that is currently active MUST render disabled — clicking
  Activate on the already-active row is a no-op that MUST be prevented at the control level, not merely
  tolerated as a harmless server-side no-op.

**Admin UI — env-level Common Config Set version preview selector (added 2026-09-01, `ux-ui-designer` —
closes the "config version dropdown" gap from the original plan)**

> **SUPERSEDED (2026-09-01, `business-analyst` — multi-base config chains pass).** FR-48–FR-50 described a
> transient, non-persisted, preview-only "which historical common version am I looking at" selector. The
> owner-approved multi-base config chains plan replaces this with a **persisted** model: an env Config Set
> Version's pin choice (`ACTIVE` vs. `PINNED` to a specific version, per base) is chosen and saved as part of
> that version's own `baseChain` (FR-51), not toggled transiently in a preview-only dropdown that "MUST NOT
> be persisted" (the old FR-50's own words). FR-48–FR-50 are kept below, unmodified, for requirement history
> per this document's convention (see FR-31's earlier correction for precedent) — they MUST NOT be
> implemented as written. They are replaced by **FR-51–FR-53** (persistence, backward-compatibility default,
> no-recursive-layering) and **FR-55–FR-58** (the base-chain editor UI) below.

- **FR-48**: The env-level Config Set edit page (FR-34) MUST provide a "Common Config Set version" selector,
  populated with every version of the paired Common Config Set (version number + created date, the same data
  already shown in a common Config Set's own version-history table per FR-30/FR-31), defaulting to the
  Common Config Set's currently ACTIVE version. This selector is a distinct control from, and MUST NOT be
  merged with or replace, the existing "Overrides Common Config Set" picker (FR-35): FR-35's picker selects
  WHICH Common Config Set this env layer is paired with (set once at creation, then locked); FR-48's
  selector selects WHICH VERSION of that already-paired Common Config Set to preview, and remains changeable
  at any time.
- **FR-49**: Changing the FR-48 selector MUST recompute and re-render both the Global (read-only) panel
  (FR-36) — showing the selected historical version's content instead of the active version's — and the
  Merged result panel (FR-38) — recomputed as (selected common version) ⊕ (the Env override panel's current,
  possibly unsaved, draft), via the same `EffectiveConfigResolver`/`doPreviewMerge` computation path already
  used for edits to the Env override panel (FR-38, OQ-9) — this MUST NOT introduce a second, divergent merge
  implementation. Changing the selector MUST NOT alter the Env override panel's (FR-37) content in any way.
- **FR-50**: The FR-48 selector is a preview/comparison tool only. It MUST NOT change the Common Config
  Set's actual active version, MUST NOT be persisted as state belonging to the env Config Set or anywhere
  else, and MUST NOT be read by the env Config Set's Save/Save & Activate actions (FR-37) or by "Generate
  Template" (FR-41) — both of those MUST continue to always operate against the Common Config Set's true
  ACTIVE version (or, for an automated caller supplying a pinning context, the PINNED version per FR-25),
  exactly as FR-16 already mandates, regardless of the selector's current on-screen value at the moment
  either action is invoked.

**Admin UI — base-chain editor (env level, added 2026-09-01, replaces FR-48–FR-50)**

- **FR-55**: The env-level Config Set edit page (FR-34) MUST replace the static "Overrides Common Config Set:
  `<project>-common` (locked)" text (and the superseded FR-48 selector) with an **ordered, repeatable,
  reorderable base-chain editor** — one row per `BaseConfigReference` in the draft `baseChain` — where each
  row provides: a project picker (selects which Config Set of COMMON role this entry references), an
  Active/Pin toggle, and — shown only when the toggle is set to Pin — a version picker populated from that
  referenced project's own version history (version number + note + timestamp, not a bare number, matching
  the data already shown in a Config Set's own version-history table per FR-30/FR-31). Add-row, remove-row,
  and reorder (move up/down, or drag) controls MUST be provided — row order is semantically significant
  (FR-8: later rows win on overlapping keys), so reordering MUST immediately affect a subsequent save and the
  live preview (FR-57).
- **FR-56**: A brand-new env Config Set MUST default its base-chain editor (FR-55) to exactly one row — this
  env's own project, Active — so that an operator who never interacts with this feature sees no behavioral or
  visual change from today's single-base model beyond the editor's shape. This is the UI-visible expression of
  FR-52's backward-compatibility rule.
- **FR-57**: The env-level screen's live "Merged result" panel (FR-38) MUST recompute against the FULL draft
  base chain (every row currently in the FR-55 editor, in its current order — including unsaved add/remove/
  reorder edits) folded per FR-8, with the Env override panel's current draft applied last — not against a
  single base version as before. This MUST continue to call the same `EffectiveConfigResolver`/
  `doComputeMerge` server-side computation path already mandated for FR-38/FR-49 (no second, divergent merge
  implementation for the UI) — see `EffectiveConfigResolver.resolveChain` in the approved plan and OQ-9's
  server-side-computation resolution, which this generalization does not reopen.
- **FR-58**: Version history (FR-39) for an env Config Set MUST display, for each past version, that
  version's own frozen `baseChain` (read-only) — e.g. an expandable "Base configs used: projectA v3 (pinned),
  projectB (active at save time → resolved to v7)" line per chain entry — this is the UI-visible manifestation
  of FR-51 ("the pin choice lives in that version's own history"). This read-only display MUST NOT be
  editable from the version-history view — editing a chain is only ever done in the live editor (FR-55) on
  the current draft, then saved as a new version.

**SDLC follow-up required before implementation (base-chain editor):** FR-55–FR-58 mandate the base-chain
editor's controls and behavior, but the exact ASCII wireframe layout (row shape, placement of add/remove/
reorder controls, and the left preview panel's collapsed-vs-expanded-per-reference tradeoff the plan itself
leaves open as `ux-ui-designer`'s call) is explicitly NOT decided in this document, following the same
convention as the FR-40/FR-41 SDLC follow-up note below. Route to `ux-ui-designer` for a wireframe update to
`docs/design/wireframe-layout.md`'s env-page (Page 2) section before `tech-lead`/implementation proceeds on
this feature's UI half; the domain-model and deployment-binding FRs above (FR-8–FR-11 amendments, FR-23–FR-27
amendments, FR-51–FR-54) have no such dependency and can proceed to `tech-lead` immediately.

**SDLC follow-up required before implementation:** FR-40/FR-41 mandate that the "Generate Template" action
exists on both pages, but the exact ASCII wireframe placement (row position relative to the existing
Save/Save & Activate row, the version-history block, and — on the env page — the three merge panels) is a
layout decision that belongs in `docs/design/wireframe-layout.md`, owned by `ux-ui-designer`, not decided in
this document. This BSA pass deliberately stops short of picking pixel/row placement. Route to
`ux-ui-designer` for a wireframe update covering both Page 1 (global edit) and Page 2 (env edit) before
`tech-lead`/implementation proceeds on the UI half of this work; the FR-15/16 domain-level content
(computation, scopes, token semantics) has no such dependency and can proceed to `tech-lead` immediately.

**Multi-format Config content — JSON/XML/YAML (added 2026-09-02)**

These formalize `docs/development/multi-format-content-plan-2026-09-02.md`'s owner-approved decisions as
testable rules. The plan builds directly on top of the now-shipped base-chain feature (FR-8–FR-11
amendments, FR-51–FR-58) — a resolved base chain now needs every entry to share one `ContentType`, which is
a **new constraint layered onto that already-shipped chain model**, not a replacement of it. Owner
explicitly confirmed two scope points that are restated here as firm decisions, not left open for
reinterpretation during design: all three formats (JSON, XML, YAML) ship together in v1, no deferral; and
XML/YAML get full editor parity with JSON (live diagnostics + working auto-format-on-load), not a reduced
"highlighting only" v1.

- **FR-59**: A Config Set's `ContentType` (`JSON`/`XML`/`YAML`, §3) MUST be chosen exactly once, at
  first-save time, and exclusively on a COMMON-role Config Set's own save form. If unspecified at first
  save, the system MUST default it to `JSON`. Once a Config Set has been saved with ≥1 version, its
  `ContentType` MUST become immutable — no later save, by any caller (UI or pipeline), may change it — this
  mirrors `projectKey`'s own existing immutability (`ConfigSet`, §3) and is enforced the same way: the field
  simply has no effect after the Config Set's first version exists.
- **FR-60**: An ENV-role Config Set MUST NOT expose any independent way to choose its own `ContentType`. Its
  effective content type MUST always be resolved by reading its base chain (FR-8/FR-52): resolve every
  referenced COMMON Config Set and take that resolved set's `ContentType` (they are guaranteed equal to one
  another by FR-61/FR-62 before this read is ever relied upon). The same resolution path already used by
  FR-61/FR-62's type-consistency check MUST be reused here — this MUST NOT become a second, independent
  lookup.
- **FR-61**: At `doSave` time on the env-level Config Set edit page (FR-34), after parsing the draft
  `baseChain`, the system MUST resolve every referenced COMMON Config Set and compare their `ContentType`s
  against each other and against the env Config Set's own already-established `ContentType` (if it already
  has one, from a prior saved version). On any mismatch, the save MUST be blocked with a fail-loud `Failure`
  naming every conflicting project and its `ContentType`. This is the UI-path half of the cross-chain
  type-consistency invariant (§3 "Relationships"), mirroring the base-chain feature's own fail-loud
  `doSave`-time enforcement pattern (see FR-53's save-time rejection for the precedent).
- **FR-62**: Independently of FR-61, and as a defense against callers that bypass the UI (pipeline steps,
  Script Console, any future API), the system MUST re-run the identical `ContentType`-consistency check
  inside the `BaseChainResolver`-consuming pipeline code path (`StepSupport.resolveEffective` or its
  equivalent), immediately after a chain is resolved: collect the resolved chain's `ContentType`s into a
  set, and if more than one distinct value is present, fail loudly with an `AbortException` naming every
  conflicting `projectKey` and `ContentType`. This mirrors the base-chain feature's own two-tier
  fail-loud/forgiving split (`Failure` in the UI path, `AbortException` in the pipeline path — see FR-25/26's
  precedent for the same UI-vs-pipeline duality). Document this as a load-bearing invariant on
  `BaseChainResolver`'s own Javadoc, not only in this spec.
- **FR-63**: The Monaco editor on both the COMMON (FR-32) and env (FR-37) Config Set edit pages MUST provide
  live, pre-Save syntax/well-formedness diagnostics for all three content types, not JSON alone — this is
  the owner-confirmed full-parity scope, not a reduced "syntax highlighting only" v1 for XML/YAML:
  - **JSON** — unchanged, existing Monaco JSON diagnostics.
  - **XML** — diagnostics MUST be produced client-side via the browser's native `DOMParser`, surfacing any
    `parsererror` node as a Monaco marker (`monaco.editor.setModelMarkers`), driven by an
    `onDidChangeModelContent` listener.
  - **YAML** — diagnostics MUST be produced via a server round-trip (a new, debounced `doValidateContent`-
    style endpoint) that reuses the exact same `YamlTreeAdapter`/parser already used for Save-time
    validation (FR-65) and the merge engine (FR-8) — this is a deliberate choice to avoid a second,
    potentially divergent client-side YAML parser implementation, per the plan's explicit recommendation, and
    MUST NOT be re-implemented as a bundled client-side YAML parser.
- **FR-64**: The Monaco editor's "format document" action MUST work for all three content types, not JSON
  alone:
  - **JSON** — unchanged, Monaco's existing built-in `editor.action.formatDocument`.
  - **XML and YAML** — MUST be implemented as a custom
    `monaco.languages.registerDocumentFormattingEditProvider` that round-trips the editor's current content
    through the server's canonical `TreeFormat.serialize` (same debounced-AJAX shape as FR-63's YAML
    diagnostics) — this guarantees the editor's "formatted" output always matches exactly what Save-time
    validation and the merge engine will see, rather than a hand-written client-side pretty-printer per
    format that could drift from the server's own canonical serialization.
- **FR-65**: The previously-duplicated, JSON-only syntax-validation call sites (the ad hoc
  `JsonParser.parseString(...)` calls in the Config Set save path and template generation) MUST be replaced
  by one shared, format-dispatching validator — `validateSyntaxOrFail(content, contentType)` — that parses
  via the `TreeFormat` registered for that Config Set's `ContentType`, requires a top-level object/root
  element, and blocks the save with the existing `Failure("Save blocked: invalid " + type + " — " +
  message)` convention (FR-33) on any parse failure. There MUST NOT remain more than one code path in the
  plugin that performs "is this content syntactically valid for its declared type" — this generalizes FR-33
  from JSON-only to all three types without changing FR-33's fail-loud, syntax-only (no schema/field-type
  validation) scope.
- **FR-66**: The system MUST NOT silently accept a value change on the FR-15 template-generation path, the
  FR-8 merge path, or any other content-reading path that assumes JSON structure — every such path MUST
  dispatch through the Config Set's `ContentType` (via `TreeFormats.forType(...)`) rather than hardcoding a
  JSON parser call. This closes the same class of gap FR-65 closes for save-time validation, restated for
  every other content-reading call site the plan's `TreeNode`/`TreeFormat` abstraction touches (merge fold,
  secrets-manifest enforcement, template walk).
- **FR-67**: The root "Config Templates" list page (FR-30) MUST display each Config Set's `ContentType` as a
  new `Type` column (`Name | Type | Active version | Last modified`) — a UI-visible surface over FR-59, not
  a new piece of state.
- **FR-68**: The COMMON Config Set save form (FR-30/FR-32) MUST provide a `ContentType` picker
  (`JSON`/`XML`/`YAML`, default `JSON`) that is enabled only while the Config Set has zero saved versions,
  and disabled (read-only, showing the already-committed value) once it has ≥1 version — a UI-visible
  surface over FR-59's immutability rule, not a new piece of state or a second enforcement mechanism (the
  authoritative enforcement remains server-side per FR-59, this control only prevents a user from attempting
  a change the server would reject anyway).
- **FR-69**: Every Config Set persisted before this feature existed MUST be treated, on load, as
  `ContentType.JSON`, via a plain field-default assignment (not object reconstruction) — this MUST require
  zero data migration and produce zero behavior change for any existing Config Set, version, deployment
  binding, or pipeline invocation. This is the explicit backward-compatibility guarantee the plan's
  `readResolve()` design exists to serve, restated here as a testable MUST (mirrors FR-52's equivalent
  guarantee for the base-chain feature).
- **FR-70**: `ConfigSet.enforceSecretPlaceholders` (FR-14) MUST generalize from its current hardcoded
  `JsonParser.parseString(...)` call to dispatching through `TreeFormats.forType(this.contentType)` and the
  format-neutral `TreePaths.get(...)` — the secret-placeholder enforcement rule itself (FR-12/FR-14) is
  unchanged; only its parsing/path-lookup mechanism generalizes to all three content types.

**Base-chain visualization redesign (added 2026-09-03, `ux-ui-designer` — closes a real bug found by
real-browser audit, plus new owner requirements)**

A real-browser audit of the shipped `EnvConfigSetPage/index.jelly` found that the "Effective base
(read-only, collapsed)" panel FR-36/FR-38's amendment (see the v4 wireframe note) called for was never
actually wired to live data: its `#globalEditor` container is populated once with a static placeholder
string at page load and `recomputeMerge()` never writes into it (only `#mergedEditor` is updated) — so the
panel silently never reflected the live-folded base chain, contradicting the documented FR-36/FR-38/FR-57
spec. The owner has given a fuller redesign of this whole area rather than a narrow patch. FR-71–FR-74
below formalize that redesign; they amend FR-36/FR-38 in place (the collapsed single-panel description is
superseded by FR-71 and FR-73) and are additive to FR-55–FR-58 (the base-chain domain model itself, and
the version-history frozen-chain display, are unchanged).

- **FR-71**: The env-level base-chain editor (FR-55) MUST present each `BaseConfigReference` row as its own
  collapsible accordion item. Expanding a row MUST show a read-only view of THAT SPECIFIC reference's own
  resolved content (whichever version it currently resolves to — its live ACTIVE version, or its pinned
  version, per that row's own `pinMode`), computed via the same `EffectiveConfigResolver`/`doComputeMerge`
  response already driving the Merged bases and Merged result panels (FR-57, FR-73) — there MUST NOT be a
  second, divergent computation or a cached/static copy for this view (this is the load-bearing fix for the
  dead "Effective base" panel described above: replacing a static single panel with N always-live,
  individually-inspectable views closes the gap structurally rather than patching the one panel that failed
  to update). Add-row, remove-row, and reorder controls (FR-55) MUST remain available and functional
  regardless of any row's expanded/collapsed state. This FR-71 accordion, together with the Merged bases
  panel in FR-73, REPLACES the FR-36/FR-38 amendment's "Effective base (read-only, collapsed)" panel and the
  "Inspect chain" drawer described in the superseded wireframe v4 pass — both are removed, not kept
  alongside the new design.
- **FR-72**: The env-level base-chain editor's project picker (FR-55) MUST filter which COMMON-role Config
  Sets are offered, as a proactive UX improvement layered on top of FR-61's existing fail-loud `doSave`-time
  rejection (FR-61 is unchanged and remains the authoritative backstop — this FR does not weaken or replace
  it). The first row in the chain establishes the content-type context (read from that row's resolved COMMON
  Config Set's own immutable `ContentType`, FR-59); every subsequent row's project picker MUST list only
  COMMON Config Sets whose `ContentType` matches that context. Changing the first row's project (and
  therefore the content-type context) MUST re-filter every later row's available options accordingly. Since
  a brand-new chain already defaults to exactly one row (FR-56), there is always a row #1 to establish
  context from — no separate, standalone "chain content type" selector is introduced.
- **FR-73**: The env-level Config Set edit page (FR-34, FR-38) MUST lay out its editor area as two columns on
  top — LEFT "Merged bases" (read-only, live fold of the full draft base chain, replacing the former
  "Effective base" panel's role and FIXING its dead-panel bug per the audit above) and RIGHT "Env override"
  (the only editable panel, unchanged role and content per FR-37) — with, BELOW both, a full-width "Merged
  result" panel (read-only, live = Merged bases ⊕ the Env-override panel's current draft, unchanged
  computation per FR-38/FR-57). This replaces the prior 3-column side-by-side table (Global | Env override |
  Merged result) entirely; it is not an additional layout alongside the old one. Any base-chain edit
  (add/remove/reorder/toggle/pin-version) OR any Env-override content edit MUST continue to trigger the
  existing debounced recompute (FR-57) that updates the Merged bases panel, every expanded FR-71 accordion
  row's own view, and the Merged result panel together, from the one server-side computation.
- **FR-74**: The env-level Config Set edit page MUST provide a "Discard all changes" action, presented
  alongside Save/Save & Activate, that — after a confirmation step, since it is destructive to unsaved work
  — reverts BOTH the Env-override panel's draft content AND the base-chain editor's (FR-55) draft rows back
  to the currently-loaded saved version's content and `baseChain`, in place, without a page reload. This MUST
  NOT alter any already-saved version, the version-history table, or which version is currently active — it
  only discards the in-memory, unsaved draft state of both the override editor and the base-chain accordion.

**SDLC follow-up required before implementation (base-chain visualization redesign):** FR-71–FR-74 are now
specified at the wireframe level in `docs/design/wireframe-layout.md`'s Page 2 section (2026-09-03 pass,
`ux-ui-designer`) — the nested-accordion row shape, the Merged-bases/Env-override/Merged-result grid
arrangement and its responsive collapse, the Discard-all-changes button's placement, and the type-filtering
UX are all decided there. Route to `tech-lead` for contract review before implementation proceeds; these FRs
have a real dependency on the wireframe (unlike, say, FR-59–FR-62/FR-65/FR-66/FR-69/FR-70 above, which had
none), same convention as the FR-40/41 and FR-55–58 follow-up notes.

**SDLC follow-up required before implementation (multi-format Config content):** FR-63/FR-64/FR-67/FR-68
mandate new UI behavior (diagnostics providers, formatters, a new list column, a content-type picker), but
their exact placement — inside the current accordion-structured Jelly layout (`<details>`/`<summary>`:
Secrets manifest, Version history, Editor) that both Config Set edit pages already use — is explicitly NOT
decided in this document, following the same convention as the FR-40/41 and FR-55–58 follow-up notes above.
Route to `ux-ui-designer` for a `docs/design/wireframe-layout.md` update covering the root list page's new
`Type` column, the COMMON page's content-type picker placement, and the XML/YAML diagnostics/format UX,
before `tech-lead`/implementation proceeds on this feature's UI half. The domain-model, validator-
consolidation, and type-consistency FRs above (FR-59–FR-62, FR-65, FR-66, FR-69, FR-70) have no such
dependency and can proceed to `tech-lead` immediately, per the plan's own SDLC sequencing note.

**Job association (added 2026-09-02, renumbered 2026-09-03 from a standalone FR-71–FR-74 to avoid the
collision noted in the consolidation note at the top of this document — no requirement content changed,
only the numbers; FR-75–FR-78 REWRITTEN 2026-09-05 per owner correction, content changed this time — see
the consolidation note at the top of this document and the two rationale paragraphs immediately below)**

**Owner correction (2026-09-05):** the previous FR-75–FR-78 had the "Config Templates Association" section
embedded inside the job's own `/job/<name>/configure` form, with the sidebar action immediately
redirecting away from `/job/<name>/configTemplates` to one of three destinations under the plugin's own
`/configTemplates/...` URL space. The owner clarified `/job/<name>/configure` was cited only as an EXAMPLE
OF THE PATTERN ("own sidebar item → own full page"), never as the location the association should live in.
Nothing about this feature is rendered inside `/job/<name>/configure` any more, and it never gets an
`f:optionalBlock`/`f:descriptorList` row-group there again — the association gets its own page instead,
reached from its own sidebar item, exactly the way `/job/<name>/configure` is reached from the
"Configure" sidebar item.

**Second owner clarification (same day):** "Configure" is the example of the pattern, and the pattern
repeats ONE LEVEL DOWN, per environment — the job's own page is not a single flat form covering
association-plus-editing in one block; it presents the job's association at the top, then hands off to a
per-environment "own item → own page" screen for the actual environment Config Set, rather than cramming
environment content inline. FR-77 below spells out that per-environment screen.

**Third owner clarification (2026-09-05, same day):** the per-environment screen MUST itself stay
job-scoped — `/job/<name>/configTemplates/<environment>` — never the existing global
`/configTemplates/<projectKey>/<environment>` page reached by ordinary navigation. Asked explicitly
whether the per-env page should be `/configTemplates/test-app/dev/`, the owner answered "нет". The
"own item → own page" pattern must hold all the way down without ever throwing the operator out of
their job's context: from the job page the operator drills into an environment and stays under
`/job/<name>/...`, exactly as every other job-scoped screen ("Configure", "Build History") does. FR-77
below specifies the job-scoped screen itself; FR-77a specifies how the resulting two-URLs-one-Config-Set
situation is kept safe and unambiguous.

A `Job` MAY opt into a Config Templates project/environment via a per-job property. That property is
viewed and edited exclusively on the job's own dedicated `/job/<name>/configTemplates` page (FR-76), never
inside `/job/<name>/configure`.

- **FR-75**: Any `Job` MAY carry a `ConfigTemplatesJobProperty` (`hudson.model.JobProperty<Job<?,?>>`)
  recording `projectKey` (string, optional/blank-able) and `environment` (string, optional/blank-able).
  This remains the persistence mechanism — native Jenkins `JobProperty` state, serialized with the rest of
  the job to `config.xml` via XStream under `$JENKINS_HOME`, exactly like every other job property; no
  external store of any kind. Both fields default to blank (`""`), never `null`, when unset. A job with no
  property, or a blank `projectKey`, is **not associated** — the default state for every job that hasn't
  opted in, and must never block normal job configuration/use. `environment` blank while `projectKey` is
  set is a valid, meaningful state (tied to the project's common Config Set only), not an incomplete
  configuration. `DescriptorImpl.doCheckProjectKey` MUST return `FormValidation.warning` (never `.error`)
  when the entered `projectKey` doesn't match an existing Config Set — a job may legitimately be wired to a
  project key before that Config Set exists, or after it's deleted; this check must never block saving.
  **Unlike the previous FR-75, this property is NOT contributed as a `JobPropertyDescriptor` row rendered
  by `/job/<name>/configure`'s `f:descriptorList`.** Verified mechanism (Jenkins core 2.568.3,
  `hudson/model/Job/configure.jelly`): the job configure page's form is
  ```
  <f:form ...>
    <f:descriptorList field="properties" descriptors="${h.getJobPropertyDescriptors(it)}" forceRowSet="true"/>
    <st:include page="configure-entries.jelly"/>
    <f:saveApplyBar/>
  </f:form>
  ```
  — the ONLY reason the "Config Templates Association" block currently appears on `/job/<name>/configure` is
  that `f:descriptorList` auto-renders every `JobPropertyDescriptor` core's own `h.getJobPropertyDescriptors(it)`
  returns, with no per-descriptor opt-out on the plugin's side. `<f:saveApplyBar/>` is what supplies core's
  Save/Apply affordances on that page — unrelated to this property once it stops being surfaced there. The
  fix is therefore to stop this descriptor being returned by `h.getJobPropertyDescriptors(it)` for
  `ConfigTemplatesJobProperty` — i.e. its `DescriptorImpl` must stop matching whatever inclusion rule that
  helper applies (commonly achieved by having the descriptor's applicability check return `false` for the
  job type, or by not registering it as a `JobPropertyDescriptor` extension at all and instead keeping only
  the plain persistence class) — so `f:descriptorList` never emits a row-group for it on `/job/<name>/configure`
  again, while `project.addProperty(...)`/`job.getProperty(...)` keep working exactly as today. Its
  `config.jelly` (if any `Describable`-driven fragment is reused at all) is rendered only on the FR-76 page,
  never on the job configure form. This sidesteps the entire `f:descriptorList`/`f:optionalBlock`/
  row-adjacency bug class documented in `docs/development/job-property-form-persistence-root-cause.md` by
  construction: that class of bug only exists for properties submitted through `Job#doConfigSubmit`'s
  `"properties"` JSON sub-object via that same `f:descriptorList` rendering, and this property is no longer
  submitted through that path at all (see FR-76's own submit handler).
  *Acceptance:* `ConfigTemplatesJobProperty` continues to round-trip via direct construction
  (`new ConfigTemplatesJobProperty(...)` + `project.addProperty(...)`) exactly as today's
  `ConfigTemplatesJobActionAssociationTest`/`ConfigTemplatesJobActionTest` already exercise; `doCheckProjectKey`
  keeps returning `.warning`, never `.error`, for an unknown key.

- **FR-76**: Every `Job` is contributed a "Config Templates" sidebar action which, when its own URL is
  requested, renders a real page of its own — it never issues an HTTP redirect. That page, gated on
  `Jenkins.ADMINISTER` (re-checked on render, independent of the sidebar-visibility gate), is the single
  place the job's association is viewed AND edited, replacing the old sendRedirect2's three destinations
  with three page STATES of this one page (see FR-78 for the precise mapping):
  - **No association (no property, or blank `projectKey`):** the page explains that this job is not yet
    tied to a Config Templates project, and presents an editable `projectKey` field (with FR-75's
    `.warning`-only validation) plus a Save action — a real form, submitted to this page's own handler
    (FR-76 below), not to `Job#doConfigSubmit`. This is the actionable next step; the page is never a bare
    "nothing here" dead end.
  - **`projectKey` set, `environment` blank:** the page shows the project name/key, the same editable
    association form as above (so the operator can change or clear the `projectKey`, or now pick/create an
    environment), AND a list of every existing env Config Set for that project, each rendered as its own
    link into its own dedicated **job-scoped** page per FR-77 (`/job/<name>/configTemplates/<environment>`
    — mirroring "Configure" one level down: item → own page, repeated per environment, never a jump into
    the plugin's global `/configTemplates/...` URL space) — plus a "New Env Config Set" creation input,
    exactly as `ProjectConfigPage` already offers today. Explicitly NOT a link to the common Config Set
    page, since the common Config Set is project-wide, not job/environment-specific — same rationale the
    previous FR-76 already established. Selecting or creating an environment here also offers a one-click
    "associate this job with `<env>`" action that updates this job's `ConfigTemplatesJobProperty.environment`
    via this page's own save handler (not a page navigation) so the job's association stays a first-class,
    job-owned setting rather than something inferred implicitly from having merely visited an env page.
  - **`projectKey` AND `environment` both set:** the page shows everything from the previous state, with
    the job's chosen environment visually distinguished (e.g. highlighted row) and its per-environment
    job-scoped link (FR-77) prominent — deep-linking straight to
    `/job/<name>/configTemplates/<environment>` even if that Config Set has never been saved yet, which per
    today's `ConfigSetPage#isExists()`/`EnvConfigSetPage/index.jelly` behavior renders a full, usable editor
    under a "does not exist yet" banner rather than a broken/empty screen (FR-77 carries the same behavior
    over to the job-scoped URL). The editable association form (to change `projectKey`/`environment`, or
    clear the association entirely) remains present and functional in this state too — this page is never a
    one-way door.
  - **No per-environment sidebar entries.** Even though Jenkins' `TransientActionFactory` mechanism can
    contribute more than one `Action` per job, individual environments do NOT each get their own job-sidebar
    item — they are reached exclusively via the links on this page (and, one level down, via links on the
    per-environment page itself, FR-77). *Justification (one sentence):* job-sidebar items are stable,
    job-identity-level entry points — exactly like "Configure" and "Build History", which stay fixed
    regardless of a job's changing state — whereas the set of environments for a job's associated project is
    dynamic project data, so it belongs as in-page navigation content rather than a variable-length sidebar
    contribution; the "own item → own page" pattern is still fully satisfied because the top level (one
    sidebar item → this one job page) is met by the existing "Config Templates" entry, and the per-
    environment "own page" step is met by FR-77's dedicated URL, reached by an ordinary link exactly the way
    "Configure" itself links onward to sub-sections without minting a sidebar item for each one.
  - **Placement, breadcrumbs, post-save behavior, and form furniture MUST mirror `/job/<name>/configure`
    itself** — Configure is the reference implementation to copy. The following is now verified directly
    against Jenkins core 2.568.3 (inspected inside the running test container), so these are stated as
    confirmed facts, not open questions:
    - *Sidebar rendering mechanism and placement (VERIFIED, core 2.568.3):*
      `hudson/model/AbstractProject/sidepanel.jelly` builds the job sidebar as:
      ```
      <l:tasks>
        <l:task contextMenu="false" href="${url}/" icon="symbol-details" title="Status"/>
        <l:task href="${url}/changes" icon="symbol-changes" title="Changes"/>
        <l:task href="${url}/ws/" icon="symbol-folder" title="Workspace" permission="${it.WORKSPACE}"/>
        <j:if test="${it.configurable}"><p:configurable/></j:if>
        <st:include page="actions.jelly"/>
      </l:tasks>
      ```
      "Configure", "Build Now", and "Delete" are emitted by `<p:configurable/>`
      (`lib/hudson/project/configurable.jelly`, itself just more `<l:task href="..." icon="..." permission="..."
      title="..."/>` entries) — a **core-only** block. Plugin-contributed `Action`s (including this plugin's
      "Config Templates" entry, via `ConfigTemplatesJobActionFactory`/`ConfigTemplatesJobAction`) are rendered
      by the subsequent `<st:include page="actions.jelly"/>`, which unconditionally comes AFTER `<p:configurable/>`
      in the markup. **Consequence, stated plainly so no implementer wastes time on it:** an Action-contributed
      sidebar item can never be placed above or among "Configure"/"Build Now"/"Delete" — that ordering is fixed
      by core and is not configurable from a plugin. "Same placement as Configure" therefore means, and can only
      mean, "the same KIND of sidebar affordance" — an `l:task`-equivalent entry with an `href`/`icon`/`title` and
      a permission gate, rendered in the standard tasks list via the plugin-actions slot — not a claim about
      ordinal position relative to core's own items. This plugin's existing mechanism
      (`ConfigTemplatesJobActionFactory`/`ConfigTemplatesJobAction`, already implemented) already satisfies this;
      no change is needed here beyond what FR-78 already specifies.
    - *URL convention (VERIFIED, live):* "Configure" is the job-relative segment `"configure"`
      (`/job/<name>/configure`). `/job/<name>/configTemplates` already mirrors this exactly — confirmed live:
      Stapler resolves it and issues a 302 to the trailing-slash form, the same behavior any job-relative
      action segment gets. No further action needed here; FR-77's `/job/<name>/configTemplates/<environment>`
      extends the identical convention one segment deeper.
    - *Breadcrumbs:* the page MUST render via the same `<l:layout>`/ancestor-based breadcrumb mechanism
      `/job/<name>/configure` itself uses, so the job's existing breadcrumb trail is shown with this page's
      own crumb appended (e.g. `... › <job> › Config Templates`) — standard Jenkins core behavior for any
      job-scoped Action page, not something this plugin must build itself; it falls out of using `<l:layout>`
      under a job-scoped URL the same way core's own job pages do. This plugin's existing Jelly views
      (`ProjectConfigPage`, `CommonConfigSetPage`, `EnvConfigSetPage`) are all global `RootAction`-family pages
      with no job ancestor in their URL, so none of them are direct precedent in this codebase for this exact
      job-scoped rendering — implementer should do a quick live check that the crumb appears once the view is
      wired under `/job/<name>/...`, but this is a routine consequence of the URL becoming job-scoped, not an
      open design question.
    - *Post-save behavior:* the association form's Save action MUST behave the way core's own
      `<f:saveApplyBar/>` behaves on `/job/<name>/configure` (the element confirmed above to supply that page's
      Save/Apply affordances) — i.e. use the same `f:saveApplyBar`-driven Save (and, if desired, Apply) UX,
      rather than an independently-invented button/redirect. Implementer should reuse `f:saveApplyBar` directly
      on the FR-76 page's form for this reason, rather than a bespoke `<f:submit>`.
    - *Form furniture:* the association-edit form MUST use the same standard Jenkins form building blocks
      Configure's own sections use — `f:entry`/`f:textbox` field rows submitted as a real `f:form`, with
      `f:saveApplyBar` per above — so it reads as a native Jenkins configuration screen. This part is also
      grounded in this repo's own code: this plugin already uses exactly this `f:entry`/`f:textbox` convention
      for its other pages' inline forms (e.g. `ProjectConfigPage/index.jelly`'s `.ctsync-inline-form`), so the
      same convention carries over directly with no open question.
  *Acceptance:* `GET /job/<name>/configTemplates` returns a rendered HTML page (HTTP 200, no redirect
  response) in all three states above; the association form's Save action persists to the same
  `ConfigTemplatesJobProperty` FR-75 defines, verifiable via `GET /job/<name>/config.xml`; every one of the
  three states offers at least one actionable next step (never a page with no way forward); a non-admin
  requesting the page gets a permission failure, never a silently empty or broken page; the rendered page
  shows the job's breadcrumb trail with its own crumb appended; no environment gets its own sidebar entry.

- **FR-77**: The "own item → own page" pattern that "Configure" exemplifies for the job as a whole is
  repeated one level down, per environment, and MUST stay job-scoped — never throwing the operator out of
  their job's context into the plugin's global `/configTemplates/...` URL space:
  - **URL shape:** `/job/<name>/configTemplates/<environment>` — a plain, job-relative multi-segment path
    (no leading `/`), exactly mirroring `/job/<name>/configure`'s own job-relative convention one level
    deeper. This explicitly REPLACES the earlier draft of this FR, which proposed reusing the existing
    global `/configTemplates/<projectKey>/<environment>` page reached by ordinary navigation — the owner
    rejected that explicitly (asked directly whether the page should be `/configTemplates/test-app/dev/`,
    answered "нет").
  - **Stapler wiring requirement (stated as a requirement, not an implementation):** `ConfigTemplatesJobAction`
    MUST expose the trailing `<environment>` path segment to a dedicated per-environment view via Stapler's
    standard nested-object dispatch convention for an extra path segment beyond an action's own URL (e.g. a
    `getDynamic(String, StaplerRequest, StaplerResponse)`-style hook, or an equivalent nested-action object
    returned for that token) — the mechanism must make `/job/<name>/configTemplates/<environment>` resolve
    to that view; which exact Stapler API accomplishes this is an implementation detail left to the
    developer, not mandated here.
  - **Environment resolution is always against the REQUESTING JOB'S OWN, CURRENT association**, read at
    request time from that job's `ConfigTemplatesJobProperty.projectKey` — never a project key embedded,
    cached, or assumed from anywhere else. This is what prevents a stale bookmarked URL from silently acting
    against a project the job is no longer associated with (see the wrong-association case below).
  - **No association / blank `projectKey` on this job:** the per-environment URL cannot resolve to any
    project at all. It MUST render an explanatory page state (not a stack trace, not a 404 with no way
    back, and never a silent create/edit against some default or global project) telling the operator this
    job has no Config Templates project set, with a link back to the FR-76 page to set one. No Config Set
    read or write happens in this state.
  - **Environment does not exist yet** (job has a `projectKey`, but no `ConfigSetVersion` has ever been
    saved for `<environment>` under it): renders the same full, usable editor under a "does not exist yet"
    banner that the global `EnvConfigSetPage`/`ConfigSetPage#isExists()` already produce today — never a
    broken/empty screen.
  - **Content and semantics** are identical to the existing global per-environment editor (Monaco,
    content-type picker, base-chain editor, Save, version history) — see FR-77a for exactly how that
    identity is guaranteed rather than merely asserted.
  - **Permission gate:** `Jenkins.ADMINISTER`, re-checked on render, exactly as FR-76's page and the
    existing global page already do.
  - **Breadcrumbs:** appends its own crumb one level under the job's `/job/<name>/configTemplates` page's
    crumb (e.g. `... › <job> › Config Templates › <environment>`), by the same `<l:layout>`/ancestor-based
    core mechanism as FR-76's page (see FR-76's breadcrumb bullet — this is a routine, verified consequence
    of the URL being job-scoped, not an open question).
  *Acceptance:* `GET /job/<name>/configTemplates/<environment>` renders a full editor for a job with a
  matching association; a job with no `projectKey` gets the explanatory no-association state, never an
  error page or a silent write; a never-saved environment renders the "does not exist yet" banner plus a
  usable editor, never a broken/empty screen; a non-admin gets a permission failure; the URL never redirects
  into `/configTemplates/...`.

- **FR-77a (two-URL data-safety requirement):** the SAME environment Config Set becomes reachable from two
  URLs once FR-77 ships: the existing global `/configTemplates/<projectKey>/<environment>` page
  (`EnvConfigSetPage` — remains canonical for the project/environment dimension, e.g. still how
  `ProjectConfigPage`'s own env list links to it) and the new job-scoped
  `/job/<name>/configTemplates/<environment>` page (a job-context view onto that same data, for any job
  whose current association's `projectKey`/`environment` match). This MUST be kept safe and unambiguous as
  follows:
  1. Both URLs MUST render the same editor with identical semantics — same fields, same validation rules,
     same Save behavior — implemented by delegating to exactly ONE shared underlying save/validate code
     path (e.g. both handlers calling into the same `ConfigSetPage`-family logic that already backs
     `EnvConfigSetPage`). There MUST NOT be a second, independently-written implementation for the
     job-scoped screen that could drift from the global one.
  2. There is exactly ONE persisted entity and ONE version history per `<projectKey>/<environment>`
     regardless of which URL was used to save it — a save via the job-scoped URL MUST be visible immediately
     via the global URL, and vice versa.
  3. The global `/configTemplates/<projectKey>/<environment>` page remains the CANONICAL URL for that
     Config Set (the system of record for the project/environment dimension, still linked to from
     `ProjectConfigPage` and elsewhere in the plugin's global navigation); the job-scoped URL is a
     job-context view onto that same data, not a competing or job-exclusive copy.
  4. The job-scoped screen MUST visibly state which Config Set it is showing (`<projectKey>`/`<environment>`,
     for job `<jobName>`) and link to the canonical global page. The global page is NOT required to
     enumerate every job associated with it, but MUST NOT present itself as if it were exclusive to one job.
  *Acceptance:* saving via the job-scoped URL, then loading the global URL for the same
  `<projectKey>/<environment>` (or vice versa), shows identical persisted content and identical version
  history; submitting the same invalid input on both URLs produces the identical validation error; no
  behavioral divergence is observable between the two URLs for the same environment.

- **FR-78**: The job-sidebar action itself is the SINGLE navigation affordance into this feature from a
  job — this rewrite does not add a second or duplicate job-sidebar control of any kind (per the owner's
  "под job там в меню кнопка на переход есть" note: the existing item is what this spec builds on, not
  something new). Its label `"Config Templates"` and icon
  `symbol-document-text-outline-plugin-ionicons-api` stay exactly as they are today, gated on
  `Jenkins.ADMINISTER` for sidebar visibility, identical to the global entry's own label/icon so it reads as
  the same feature everywhere. Its URL MUST remain the plain, job-relative segment `"configTemplates"`
  (no leading `/`), so Stapler exposes it at `/job/<name>/configTemplates` — exactly mirroring the built-in
  "Configure" entry at `/job/<name>/configure`. **Only its destination behavior changes, and this explicitly
  SUPERSEDES the previous FR-77's `sendRedirect2` requirement:** a request landing exactly on that URL MUST
  be handled by `doIndex` rendering the FR-76 page directly (re-checking `Jenkins.ADMINISTER` independently
  of the sidebar-visibility gate, as before), not issuing any HTTP redirect. The three old redirect
  destinations (generic root list / project overview / specific env page) are NOT deleted requirements —
  they become the three PAGE STATES FR-76 defines (no association / `projectKey`-only / both set) and the
  job-scoped per-environment link FR-77 defines, rendered as content and navigation on one page instead of
  being resolved server-side into a redirect target.
  *Acceptance:* the sidebar entry's label/icon/tooltip render identically to today, and there is exactly one
  "Config Templates" item in the job's sidebar (never one per environment); `GET /job/<name>/configTemplates`
  never returns an HTTP 3xx response; a non-admin sees no sidebar entry and, if they request the URL
  directly, gets a permission failure rather than a redirect.

- **FR-78a (migration note):** jobs whose `ConfigTemplatesJobProperty` was already persisted (under either
  the previous or the corrected design — persistence itself, per FR-75, is unchanged) keep working exactly
  as before: the `config.xml` property element's shape (`projectKey`/`environment` fields) is unchanged, so
  no data migration, upgrade script, or one-time job re-save is required. Only the UI surface changes: what
  used to be an embedded section on `/job/<name>/configure` plus an immediate redirect is now the FR-76
  page. **This also fully supersedes the previous FR-78's acceptance criteria** (which asserted that saving
  the association section on `/job/<name>/configure` persists the property and that reloading `/configure`
  shows it pre-filled) — those criteria describe a form that no longer exists. They are replaced by FR-76's
  and FR-78's acceptance criteria above: the association is saved via the FR-76 page's own handler, verified
  via `GET /job/<name>/config.xml`, and reloading the FR-76 page (not `/configure`) shows the same values
  pre-filled.

**Pipeline-level resolution overrides (added 2026-09-03)**

These formalize a design worked out directly with the owner, layered on top of the now-shipped base-chain
(FR-51–FR-58) and build-version pinning (FR-23–FR-27/54) features. They amend neither `configTemplateValidate`
nor `configTemplateSubstitute`'s existing required parameters (`projectKey`, `environment`, `file`, and
`configTemplateSubstitute`'s existing optional `buildVersion`) — `useBase` and `version` are purely additive
optional parameters, so every existing call site in a Jenkinsfile keeps compiling and behaving identically
with no change. Grounded against the actual pushed step code: `ConfigTemplateValidateStep`'s 3-arg
`@DataBoundConstructor` (`projectKey`, `environment`, `file`), `ConfigTemplateSubstituteStep`'s 3-arg
constructor plus its existing `@DataBoundSetter String buildVersion`, and `StepSupport`'s
`requireEnv`/`requireCommon`/`effectiveBaseChain`/`resolveEffective`/`mergedSecretsManifest` helpers both
steps already share.

- **FR-79**: Both `configTemplateValidate` and `configTemplateSubstitute` MUST accept two new optional
  parameters, added the same way `configTemplateSubstitute`'s existing `buildVersion` is (an
  `@DataBoundSetter`, not a constructor argument, so no existing call site needs to change):
  - **`useBase`** (boolean, default `false`).
  - **`version`** (optional integer; no default — an absent `version` MUST continue to mean "active," matching
    today's implicit behavior exactly).
- **FR-80**: When `useBase=false` (the default — unchanged behavior otherwise), a supplied `version` MUST
  pin the resolution of the **env** Config Set to that exact version number (`env.getVersion(version)`,
  instead of `env.getActiveVersion()`). Since that env `ConfigSetVersion` already carries its own frozen
  `baseChain` in its immutable record (FR-51), pinning the env version this way pins the ENTIRE resolved
  picture unambiguously — the pinned env version's own `baseChain` (or FR-52's/FR-87's default, as
  applicable) governs base resolution exactly as it would for any other env version; no separate "which base
  version" parameter is needed or offered.
- **FR-81**: When `useBase=true`, the step MUST completely bypass the env Config Set's override content AND
  its base-chain resolution machinery entirely — it MUST NOT call `requireEnv`/`effectiveBaseChain`/
  `resolveEffective` at all for this invocation. Instead it MUST resolve directly against THIS SAME
  `projectKey`'s own COMMON Config Set only (`requireCommon(projectKey)`), using that Config Set's content
  as the entire effective configuration for this call. A supplied `version` in this mode pins THIS project's
  own COMMON Config Set to that exact version number (`commonConfigSet.getVersion(version)`) — never any
  other project's Config Set, even though the env Config Set's own base chain (if one exists at all) might
  reference other projects. Since the base chain is bypassed entirely in this mode, every multi-project chain
  entry that might exist on the env version is irrelevant and MUST NOT be consulted or resolved.
- **FR-82**: **Precedence rule.** If `version` is explicitly supplied on a call, it MUST take precedence over
  an existing `buildVersion` Deployment Binding (FR-25) for that same call: the step MUST NOT perform the
  Deployment Binding lookup/pinned-chain branch at all when `version` is explicitly supplied, even if
  `buildVersion` is also supplied and a binding for it exists. This is explicit, immediate operator intent
  beating previously-recorded/frozen history. `buildVersion`'s own binding-freeze/replay behavior (FR-23–27/54)
  is otherwise completely unchanged and still applies in full whenever `version` is NOT explicitly supplied
  (regardless of `useBase`'s value) — including still creating/updating a Deployment Binding on a successful
  real substitution that supplies `buildVersion` without `version` (FR-23), which remains keyed off the
  chain actually resolved for that run, whatever `useBase` made that be.
- **FR-83**: When `useBase=true`, the secrets manifest used for that call MUST be built solely from the
  resolved COMMON Config Set's own manifest — equivalent to calling `StepSupport.mergedSecretsManifest` with
  a single-element `resolvedBaseConfigSets` list containing only that COMMON Config Set, and `env = null`.
  The env Config Set's manifest MUST NOT be merged in for this call, since the env layer is being bypassed
  entirely — `useBase=true` means "ignore env," not "ignore env content but still consult its secrets."
- **FR-84**: **Fail-loud on a missing explicit version.** If `version` is supplied and does not exist for the
  target Config Set (the env Config Set when `useBase=false`, the COMMON Config Set for this `projectKey`
  when `useBase=true`), the step MUST throw `AbortException` naming the exact missing version number and
  which Config Set it was looked for on (`projectKey` + role, and `environment` when applicable) — matching
  this codebase's existing NFR-7 fail-loud convention and `BaseChainResolver`'s/`StepSupport`'s existing
  missing-version error shape (e.g. `StepSupport.resolveEffective`'s "Base Config Set '<projectKey>' has no
  version <N> to resolve" pattern; `configTemplateSubstitute`'s existing frozen-binding lookup already uses
  an equivalent shape for a vanished pinned base version — mirror that message form here, do not invent a
  third).
- **FR-85**: `configTemplateValidate` invoked with `useBase=true` is an intentional, valid use case — e.g. a
  project-level CI check of the base template before any environment-specific values are relevant — and MUST
  NOT be rejected or treated as a misconfiguration. It simply changes what "effective configuration" means
  for that invocation's drift check: the target file's tokens are compared against the COMMON Config Set
  alone (FR-81), not the merged env-specific one, and FR-17–FR-19's missing/orphaned semantics apply exactly
  as they do to any other effective configuration.

**Standalone env Config Sets — no base chain (added 2026-09-03)**

These close a confirmed domain-model gap found while designing the overrides above: today there is no way
to express "this env Config Set is truly standalone — zero base configs" as a deliberate, distinct state.
See the new `explicitlyStandalone` entity note in §3 for the full gap description
(`ConfigSetVersion.readResolve()` collapses both `baseChain == null` and a hypothetical `baseChain == []` to
the same empty list, and FR-52 then treats empty as "implicitly one self-referencing `ACTIVE` entry").

- **FR-86**: An ENV-role `ConfigSetVersion` MUST be able to carry a new boolean field, `explicitlyStandalone`
  (§3), persisted as part of that version's own immutable, append-only record (FR-2/FR-51) — chosen by the
  operator (or an automated caller) at save time, defaulting to `false` when unset. A COMMON-role
  `ConfigSetVersion` MUST always have `explicitlyStandalone == false` — mirroring FR-53's restriction that
  only ENV-role versions may declare base-chain-related state at all; saving a COMMON-role version with
  `explicitlyStandalone = true` MUST be rejected the same way FR-53 rejects a non-empty `baseChain` on a
  COMMON-role version.
- **FR-87**: When an ENV-role `ConfigSetVersion`'s `explicitlyStandalone` is `true`, FR-52's "empty or absent
  `baseChain` defaults to a single self-referencing `ACTIVE` entry" rule MUST NOT apply to that version — for
  every merge, validate, substitute, and template-generation purpose, the effective configuration for that
  version IS its own content alone, with no base fold performed at all (`effectiveBaseChain` MUST return an
  empty list for this version, not the FR-52 synthesized singleton, and every caller that folds a base chain
  MUST treat an empty chain here as "zero bases to fold," not as an error or as FR-52's default).
- **FR-88**: `explicitlyStandalone` is strictly additive. It MUST NOT change what an empty or absent
  `baseChain` already means for any version that leaves `explicitlyStandalone` at its default `false` —
  including every version persisted before this feature existed (which, per `ConfigSetVersion.readResolve()`,
  deserializes with `explicitlyStandalone` absent/`false` the same way `baseChain` deserializes to empty).
  FR-52's backward-compatibility guarantee for genuinely-legacy data (single self-referencing `ACTIVE` default)
  is completely unaffected by this feature for any version that does not explicitly opt in.
- **FR-89**: The base-chain editor (FR-55) and its version-history read-only display (FR-58) MUST surface
  `explicitlyStandalone` as a first-class, visible state distinct from "chain has exactly one row pointing at
  this env's own project" — an operator looking at either the live editor or a past version's frozen chain
  display MUST be able to tell "this version declared zero bases, on purpose" apart from "this version's
  chain happens to resolve to one self-referencing entry" (whether via FR-52's default or an explicit
  single-row chain). Exact wireframe treatment (e.g. a toggle/checkbox in the FR-55 editor, and a distinct
  label in the FR-58 history display) is explicitly NOT decided here — route to `ux-ui-designer`, same
  convention as FR-55–58's own SDLC follow-up note; this FR mandates only that the distinction MUST be
  visible somewhere, not its exact placement.

**FR-104 addendum (added 2026-09-03) — env content-type picker for the explicitlyStandalone-with-no-chain
gap.** Found during live manual testing of the already-shipped FR-60/FR-86/FR-87 UI: this is a genuine
functional gap in requirements already formalized above, not a new feature — FR-60's text is left
unchanged; this is an explicit, dated amendment layered on top of it, mirroring the already-specified
FR-59 picker pattern rather than inventing a new one.

- **FR-104**: FR-60's "An ENV-role Config Set MUST NOT expose any independent way to choose its own
  `ContentType`" rule has exactly one exception: when an ENV-role Config Set Version is
  `explicitlyStandalone` (FR-86/87) — declaring zero base configs — there is no base chain at all for
  FR-60's resolution path to read a `ContentType` from. For that case only, the env-level Config Set edit
  page (FR-37) MUST expose the SAME content-type picker UX FR-59 already specifies for the COMMON page
  (JSON/XML/YAML choice, locked permanently after the first Save). Specifically:
  1. The picker MUST render/enable only while no version of this env Config Set exists yet AND the
     draft's `explicitlyStandalone` state (FR-89's editor) is checked — mirroring FR-59's own
     "only while `!exists`" gate, with the added `explicitlyStandalone` condition.
  2. Whenever `explicitlyStandalone` is unchecked (a base chain is declared), the picker MUST stay
     hidden and FR-60's original "always follows the chain" behavior MUST be completely unchanged —
     this FR is strictly additive, never a modification of the non-standalone case.
  3. Once a first version of this env Config Set exists, the picker MUST lock to a read-only display
     exactly like FR-59's, regardless of whether that version was saved `explicitlyStandalone` or not —
     a later version can never retroactively change the type, mirroring `ContentType`'s existing
     immutability contract (`ConfigSet`, §3) exactly.
  4. The resolved value MUST drive the same downstream consumers FR-63/FR-64/FR-15 already key off
     `ContentType` for (Monaco language mode + diagnostics/format wiring, save-time syntax validation,
     template generation) for the standalone-with-no-chain case specifically. The merge-preview
     (`doComputeMerge`/`previewMerge`) and save (`doSubmitSave`/`jsSave`) server paths MUST accept this
     client-supplied value as a fallback ONLY when the resolved base chain is genuinely empty; FR-52's
     synthesized default chain for a non-standalone empty draft chain MUST continue to always win, so
     this fallback MUST never be reachable for an ordinary, non-standalone env Config Set.

**FR-105 addendum (added 2026-09-04) — "choose content type at creation" moved earlier in the flow.**
Owner-requested UX refinement, not a change to FR-59's underlying rule: FR-59 already establishes that a
Config Set's `ContentType` is chosen exactly once, at first-save time, defaulting to `JSON` if unspecified,
and that the COMMON page's `#contentTypeRow` picker is the only place that choice is ever made or persisted.
FR-105 does not change any of that — it only moves the point at which the admin is first *prompted* for the
choice earlier in the navigation flow, from after they have already clicked into a brand-new project's editor
to the root list page's own "New Config Set" section, before that navigation happens.

- **FR-105**: The root list page's "New Config Set" section MUST render the same JSON/XML/YAML choice
  (with the same "choose once — locked forever after the first Save" helper wording FR-59's own picker
  uses) alongside the existing `projectKey` input, defaulting to `JSON` pre-selected. The selected value
  MUST be carried through to the destination COMMON page's URL (as a `?contentType=...` query parameter)
  when the "Open / Create" button is clicked, so that page's still-unlocked `#contentTypeRow` picker
  pre-selects that same value instead of always hard-defaulting to `JSON` on arrival.
- **FR-105a**: This is purely a smarter *default*, never a second enforcement/persistence point. The
  COMMON page's picker remains the only place the choice is actually saved (FR-59's `doSubmitSave`/`jsSave`
  contract is unchanged), and it remains fully changeable up until the first Save — a user who navigates
  there directly (bookmark, or simply changing their mind after arriving via the root page) can still pick
  any of the three types right up to that point.
- **FR-105b**: An incoming `?contentType=...` query parameter MUST be honored only while the target project
  does not yet exist (`isExists() == false`). For an already-existing (and therefore already-locked, FR-59)
  Config Set, the parameter MUST be silently ignored — the committed type remains authoritative and is never
  influenced by a stray or mismatched incoming query parameter (e.g. from an old bookmark).
- **FR-105c**: Leaving `projectKey` empty on the root page and clicking "Open / Create" MUST continue to
  behave exactly as it did before this change (no new client-side validation was added) — the only change to
  that path is the additive `?contentType=...` query parameter now also being appended.

**FR-105 layout addendum (added 2026-09-04, `ux-ui-designer` — live UI review, root list page).** FR-105
above specifies WHAT the "New Config Set" section must contain (projectKey input, JSON/XML/YAML choice,
choose-once helper text) but not how those elements are arranged; the first implementation rendered them
as one cramped inline row (input, radios, and the helper hint all side by side), which the owner flagged
live as poor UX, not a functional defect. This addendum does not change FR-105/FR-105a/FR-105b/FR-105c's
behavior contract in any way — only the layout:
- The `projectKey` input and the content-type radio group are stacked in a column, inside a bordered card
  matching the `f:section` bordered-card convention already applied to `CommonConfigSetPage`/
  `EnvConfigSetPage` the same day (`border: 1px solid var(--table-border-color, #ddd)`).
- The `projectKey` input gets a visible "Project key" label (an `f:entry` title) instead of relying on
  placeholder text alone; the placeholder now only shows the example format (`my-app`).
- The content-type micro-label ("Content type:") and the "choose once — locked forever after the first
  Save" hint both render ABOVE the radio row, label first then hint, instead of the hint trailing after
  the radios.
- The "Open / Create" button sits outside/below the bordered card, not inside it.

See `docs/design/wireframe-layout.md`'s Page 1 list-view wireframe for the redrawn ASCII layout.

**FR-106/FR-107 addendum (added 2026-09-04) — EnvConfigSetPage live-manual-review fixes: section reorg
and Activate-reloads-editor-state.** Found during live manual review of the already-shipped FR-51–58/71–89
base-chain editor UI: these are genuine functional/UX gaps in requirements already formalized above, not new
features — no prior FR text is changed, these are explicit, dated amendments layered on top of it.

- **FR-106 (section layout — accordion removed, reordered)**: The env-level Config Set edit page (FR-37)
  MUST render its four sections — Env override version history (FR-38), Secrets manifest (FR-12/13), Base
  chain (FR-51/FR-55), and Editor (Merged bases/Env override/Merged result, FR-38/FR-73) — as always-visible,
  non-collapsible framed sections (Jenkins core's own bordered-card section convention), in this exact
  top-to-bottom order: **(1)** Env override version history, **(2)** Secrets manifest, **(3)** Base chain,
  **(4)** Editor, last. This supersedes the 2026-09-02 "independently collapsible `<details>` accordion" UI
  pass referenced elsewhere in this document's own history for these four regions — that collapsible
  behavior is removed, not merely re-skinned. The Base chain section (previously nested inside the Editor
  region, ahead of the Merged-bases/Env-override/Merged-result panels — see FR-51/FR-55's original wireframe
  placement) MUST be its own top-level section, structurally independent of Editor, per this order.
  `CommonConfigSetPage`'s equivalent three regions (Secrets manifest, Version history, Editor) MUST receive
  the same accordion-to-framed-section treatment for visual consistency across both edit pages; that page's
  own section ORDER is unchanged (out of scope for this addendum — only the env page's order was
  owner-directed to change).
- **FR-107 (Activate must reload the editor's draft state)**: Activating a version on the env-level Config
  Set edit page (FR-38's `activateVersion` action) that is DIFFERENT from whichever version's content the
  base-chain editor/Env-override editor currently has loaded MUST reload that editor state (base-chain rows,
  the `explicitlyStandalone` flag, and the Env-override editor's content) from the NEWLY ACTIVATED version's
  own persisted data, then recompute the Merged bases/Env override/Merged result panels accordingly, and
  clear any active Compare-mode selection. Previously, Activate updated only the version-history table's
  active-badge/button-disabled state, deliberately leaving the editor draft untouched — this left the
  operator looking at stale draft content that no longer represented the newly-active version, risking an
  accidental Save that would overwrite the just-activated version with unrelated draft content.

**`setupConfigTemplate` build-scoped convenience step (added 2026-09-03)**

A new pipeline step, `setupConfigTemplate(projectKey:, environment:, file:, redeployFromRun:, useBase:,
version:)`, storing these values scoped to the current build (`Run`), so a subsequent zero-argument call to
`configTemplateValidate()` or `configTemplateSubstitute()` within the SAME build reads its parameters from
this stored state instead of requiring them to be repeated on every call. Purely additive convenience — both
steps MUST continue to also support being called with their full, explicit parameter set exactly as today.

- **FR-90**: The system MUST provide a new pipeline step, `setupConfigTemplate`, accepting all six parameters
  named above (`projectKey`, `environment`, `file`, `redeployFromRun`, `useBase`, `version` — the same set and
  semantics as FR-79–FR-85 define for `configTemplateValidate`/`configTemplateSubstitute`, all individually
  optional on `setupConfigTemplate` itself, since a caller MAY set only a subset here and supply the rest
  explicitly at the point of the real call). It MUST persist these values scoped to the current build
  (`Run`) — e.g. as a transient `InvisibleAction`/build-scoped side table keyed by `Run`, not as a
  `StepExecution`-local or step-argument-local value — so the stored state outlives the `setupConfigTemplate`
  step's own execution and remains readable by later steps in the same build. **(Corrected 2026-09-03:
  earlier wording listed `buildVersion` as one of the six stored parameters; `buildVersion` no longer exists
  as a `configTemplateSubstitute` parameter (FR-96) and is replaced here by `redeployFromRun` (FR-100).
  Storing `redeployFromRun` via `setupConfigTemplate` carries the exact same narrow, deliberate-override
  semantics FR-100 defines for supplying it directly — it suppresses nothing and does not change the
  default-on binding-write behavior (FR-97); it only lets a build set up its cross-Run replay target once and
  have a later zero-argument `configTemplateSubstitute()` call read it, the same convenience FR-90 already
  provides for the other five parameters.)**
- **FR-91**: A zero-argument (or partially-argument) call to `configTemplateValidate()`/
  `configTemplateSubstitute()` within the SAME build as a prior `setupConfigTemplate` call MUST read
  whichever of `projectKey`/`environment`/`file`/`redeployFromRun`/`useBase`/`version` it was not itself
  explicitly given from that stored build-scoped state, exactly as if those values had been passed directly
  on the call. **(Corrected 2026-09-03: earlier wording named `buildVersion` in this list; see FR-90's
  correction note — `redeployFromRun` is the replacement parameter.)**
- **FR-92**: Both `configTemplateValidate` and `configTemplateSubstitute` MUST continue to fully support being
  invoked with their complete, explicit parameter set exactly as specified before this feature (and as
  extended by FR-79–FR-85) — `setupConfigTemplate` is purely additive convenience, NEVER required. A
  Jenkinsfile that never calls `setupConfigTemplate` MUST behave with zero change.
- **FR-93**: **Precedence when both are present.** If `configTemplateValidate`/`configTemplateSubstitute` is
  called with its OWN explicit parameter(s) — any subset of them — in addition to an active
  `setupConfigTemplate` state for the same build, the explicitly-supplied call-site parameter(s) MUST take
  precedence over the corresponding stored setup value(s) for that specific call, evaluated **per parameter**
  (e.g. a call that explicitly supplies only `file` while relying on the stored `projectKey`/`environment`
  MUST use the explicit `file` together with the stored `projectKey`/`environment`, not fall back to a stored
  `file` value nor require every parameter to be re-supplied together). This mirrors FR-82/FR-101's "explicit
  beats implicit" precedence philosophy for `version` vs. `redeployFromRun`. **(Corrected 2026-09-03: earlier
  wording cited "`version` vs. `buildVersion`"; `buildVersion` no longer exists as a parameter (FR-96) — the
  precedence pair this sentence refers to is now `version` vs. `redeployFromRun`, per FR-101.)**
- **FR-94**: **Fail-loud on insufficient parameters.** Calling `configTemplateValidate()`/
  `configTemplateSubstitute()` with a required parameter (`projectKey`, `environment`, or `file`) available
  from NEITHER the explicit call-site arguments NOR a prior `setupConfigTemplate` call in the same build MUST
  fail loudly (`AbortException`) naming exactly which required parameter(s) are missing from both sources —
  it MUST NEVER silently proceed with a `null`/empty/default value for `projectKey`, `environment`, or `file`.
  (`redeployFromRun`, `useBase`, and `version` remain individually optional from both sources, per their own
  existing optionality — FR-94 only governs the three parameters that have always been required.) **(Corrected
  2026-09-03: earlier wording named `buildVersion` as one of the individually-optional parameters here;
  `buildVersion` no longer exists as a parameter (FR-96) and is replaced by `redeployFromRun`, per FR-90's
  correction note.)**
- **FR-95**: **Forbidden inside `parallel {}`.** `setupConfigTemplate`'s build-scoped state is ambiguous
  across concurrently-running parallel branches (e.g. a matrix deploying to multiple environments at once,
  where two branches would silently clobber or race on the same `Run`-scoped stored state). Calling
  `setupConfigTemplate` while executing inside a `parallel {}` block MUST be detected and rejected with a
  clear `AbortException` at execution time, naming the branch it was called from. Calling
  `configTemplateValidate`/`configTemplateSubstitute` with their OWN full explicit parameters (not relying on
  `setupConfigTemplate`'s stored state) inside `parallel {}` remains fully supported and safe, since no
  shared mutable state is involved in that mode — this restriction is scoped to `setupConfigTemplate` itself,
  not to the two existing steps.
  - **Implementation guidance on the detection mechanism** *(researched, not a closed decision — see
    OQ-11)*: Jenkins Pipeline's `parallel` step (`workflow-cps`, already a dependency of this plugin per
    `pom.xml`) marks each branch's flow-graph nodes with a `ThreadNameAction`
    (`org.jenkinsci.plugins.workflow.actions.ThreadNameAction`), attached via a `ParallelLabelAction`
    (a `LabelAction` implementing `ThreadNameAction`) on the branch's start node — this is the same
    mechanism Jenkins core itself uses to prefix parallel-branch log lines with the branch name
    (JENKINS-26122, `jenkinsci/pipeline-plugin` PR #80). A step wanting to detect "am I inside a parallel
    branch" would request `FlowNode.class` in its `StepDescriptor.getRequiredContext()`, then walk that
    node's ancestry (parent nodes, or `getEnclosingBlocks()`) checking `getAction(ThreadNameAction.class) !=
    null`. This is a real, citable, currently-existing Jenkins core mechanism — not invented for this
    spec — but it has NOT been verified against this exact repository's pinned `workflow-cps`/
    `workflow-support`/`workflow-step-api` dependency versions, nor prototyped against this plugin's own
    `StepExecution` shape. `tech-lead` MUST confirm the exact API surface (which class walks the graph,
    whether `getEnclosingBlocks()` alone suffices or manual parent-walking is needed, and whether adding
    `FlowNode.class` to `setupConfigTemplate`'s `getRequiredContext()` has any side effects on the step's
    existing behavior) before implementation, rather than this document mandating a specific call sequence
    it has not prototyped.
>
> **v10 addition (2026-09-03, `business-analyst` — automatic build-identity pinning, replaces manual
> `buildVersion`):** formalizes an owner-confirmed amendment to the already-shipped build-version-pinning
> feature (FR-23–FR-27/FR-54). The pipeline-author-supplied `buildVersion` string parameter on
> `configTemplateSubstitute` is **removed entirely** — it was error-prone (a manually-typed, easy-to-forget-
> or-mistype literal) — and replaced by an identifier the step derives itself from the current Jenkins `Run`
> (`run.getExternalizableId()`, Jenkins core's own guaranteed-unique `<jobFullName>#<buildNumber>` identity;
> confirmed against the actual `Run` API, not guessed). Because deriving the key is now free and automatic,
> Deployment Binding recording is also flipped from opt-in to **default-on** for every real substitution —
> this is the exact direction OQ-3 was deferring, now resolved (see OQ-3's updated resolution in §7). FR-23–
> FR-27 and FR-54 are amended in place (dated notes, same convention as the FR-8–FR-11 base-chain amendment).
> New FR-96–FR-103 (§4) add: the automatic key-derivation rule, the default-on write rule, and — the most
> important part of this pass — an explicit investigation of whether an automatic, single-Run-scoped key can
> serve UF-6's cross-Run rollback/redeploy scenario. **Finding: it structurally cannot, on its own** — a
> Jenkins "Replay" or any ordinary rebuild/promotion job always allocates a brand-new build number (a new
> `externalizableId`), so a rollback job's own auto-derived key can never equal the original build's — and a
> new, narrow, explicitly-supplied `redeployFromRun` override (FR-100–FR-103) is added specifically, and only,
> for that deliberate cross-Run case. See the "Migration / breaking change" note closing the new FR
> subsection for the hard-removal-vs-deprecation-period call on the removed `buildVersion` parameter (hard
> removal is recommended). **Dependency flag for `tech-lead`:** the pipeline-resolution-controls contract in
> `docs/development/tech-lead-pipeline-resolution-controls-review-2026-09-03.md` §3a (FR-82) already reasons
> about suppressing the Deployment Binding lookup/save when `version` is supplied, conditioned on today's
> opt-in `hasBuildVersion` flag — that conditioning no longer holds once binding writes are default-on and
> `buildVersion` no longer exists as a parameter; §3a needs a re-read against FR-96–FR-103 before
> implementation proceeds. Not rewritten here — that document is `tech-lead`'s to update.

</functional-requirements>

<non-functional-requirements>

## 5. Non-functional requirements / constraints

These were explicitly and firmly stated by the owner during the origin discussion. They are constraints,
not preferences — they MUST NOT be silently dropped, weakened, or "improved upon" during design or
implementation.

- **NFR-1 — No external database of any kind.** All persistence MUST live natively inside Jenkins' own
  storage mechanism (i.e., the same class of persistence Jenkins already uses for job configs, credentials,
  and global configuration — files under `$JENKINS_HOME`). No SQL Server, no other external RDBMS/NoSQL
  store, no separate service the plugin depends on at runtime. This was explicitly evaluated and rejected
  during the origin discussion (an external SQL database option was considered and turned down).
- **NFR-2 — Not git-backed.** Config Set content and version history MUST NOT be stored in, or require, a
  git repository. This alternative was also explicitly evaluated and rejected.
- **NFR-3 — No real secret values at rest, ever.** Reinforced from FR-12/13: this is both a functional rule
  and a non-negotiable security constraint — the plugin's own storage must never become a place where a
  real secret could leak via a backup, an XML export, or an admin UI screenshot.
- **NFR-4 — Product-agnostic naming and packaging.** The plugin's code, package names, class names,
  documentation, README, and any sample/default data MUST NOT reference any specific consuming
  client/company/project by name (e.g. no mentions of the originating client or its application) anywhere
  in the plugin repository. That context belongs only in this space's private documentation, never in the
  shipped product.
- **NFR-5 — Multi-instance, multi-project installability.** The plugin MUST be installable as a standard
  Jenkins plugin (`.hpi`) on any Jenkins instance, and MUST support managing any number of distinct Config
  Projects without code changes — it is a reusable tool, not a one-off integration hardwired to a single
  pipeline or job.
- **NFR-6 — Auditability.** Every version, activation/rollback, and deployment binding update MUST retain
  who did it and when (author + timestamp), since config drift/rollback history is the entire reason this
  tool exists.
- **NFR-7 — Fail loud, never fail silent.** Every validation failure, substitution failure, or fallback
  behavior (e.g. pin-not-found) MUST be surfaced in build output in a form an operator can act on without
  reading source code — this generalizes the "every system action needs a user-visible response" principle
  to a CI/CD context.
- **NFR-8 — Cross-project base references are unrestricted in v1 (added 2026-09-01, multi-base config
  chains).** A `BaseConfigReference`'s `projectKey` MAY name any Config Project's COMMON-role Config Set, not
  only the referencing env Config Set's own project — resolution goes through the existing
  `ConfigSetRepository.findCommon(projectKey)` lookup, which has no ownership/scoping check today, and this
  increment does not add one. No allow-list, approval workflow, or permission gate on which projects may
  reference which is built now. This is a deliberate v1 scope decision, not an oversight: `BaseConfigReference`
  stores a plain `projectKey` string, so a scoping/allow-list mechanism can be layered on top later without a
  breaking data-model change, if cross-project reference sprawl becomes a real governance problem on a given
  Jenkins instance.
- **NFR-9 — Minimize new dependency footprint for multi-format content (added 2026-09-02).** XML support
  MUST use the JDK-builtin `javax.xml.parsers`/DOM APIs — no new Maven dependency — since a Jenkins plugin
  should minimize its own classloader/update-center dependency footprint, and DOM's small, fixed operation
  set (get/set child, get/set attribute, get/set text, deep-copy) is sufficient for the `TreeNode`
  abstraction's needs. YAML support MAY add exactly one new Maven dependency, SnakeYAML, since no JDK-builtin
  YAML parser exists and this dependency is therefore unavoidable — no other new runtime dependency is
  introduced by this feature.

</non-functional-requirements>

<out-of-scope>

## 6. Explicit out-of-scope / deferred items

Stated here so implementation does not scope-creep into them yet, and so they are not silently forgotten
either — they are acknowledged future milestones, not rejected ideas.

- **Stapler/Jelly admin UI** — the global and env-level list/edit/version/rollback/compare screens
  themselves are now specified (FR-30–FR-39, added 2026-08-27) rather than fully deferred. The **"generate
  template" button** is now also specified (FR-15/16 content + FR-40–FR-44 UI surface, added 2026-09-01) —
  no longer deferred, pending only a `ux-ui-designer` wireframe-placement pass (see the SDLC follow-up note
  at the end of the template-generation UI FRs in §4) before implementation. What remains genuinely deferred
  within the UI is **any JSON Schema/field-type validation** on top of the JSON-syntax-only validation in
  FR-33 (explicitly scoped down by the owner; no per-project schema concept exists in the domain model), and
  **downloading a generated template as a file** (FR-42 explicitly scopes v1 to copy-to-clipboard only).
  Until the wireframe/UI pass lands, the generate-template flow remains also drivable via Pipeline steps
  and/or a scriptable entry point (e.g. Script Console / a thin CLI) — the UI is an additional trigger onto
  the same FR-15/16 computation, not the only one.
- **Private Jenkins Update Center** for multi-instance distribution (self-hosted `update-center.json` +
  hosted `.hpi`) — deferred to a third milestone, after there is a second real consuming instance/project to
  justify it. Single-instance installs in the meantime use direct `.hpi` upload.
- **Submission to the public Jenkins Update Center** — explicitly a further, optional step after internal
  proof, not part of the current increment.
- **Any consuming project's own pipeline migration work** (e.g. cutting over an existing project's
  Jenkinsfile to use `configTemplateValidate`/`configTemplateSubstitute`, retiring its old flat config
  store) — that is rollout work for whichever project adopts the plugin, owned by that project's own space,
  not by this plugin's requirements.
- **Secrets management itself** (issuing, rotating, or storing real credential values) — explicitly out of
  scope; this plugin only *references* Jenkins credential IDs, it never becomes a secrets store.
- **General-purpose feature-flagging or config-as-a-service beyond the token-substitution use case** — not
  a goal; scope is deliberately bounded to the structured-config-drift problem stated in §1.

</out-of-scope>

<open-questions>

## 7. Open questions for the owner

These are genuinely ambiguous in the origin discussion and are not resolved by this document — they need
an explicit owner decision before the affected requirements can be considered final.

- **OQ-1 — "Looks like a real secret" detection (FR-14).** How strictly should the system try to detect and
  reject an accidental real secret value pasted into a field marked secret? Options range from "none — the
  UI/pipeline simply always writes the placeholder marker and never accepts free text there" (structural
  prevention) to "best-effort heuristic scanning with warnings" (detection, not prevention). Structural
  prevention is safer but constrains the editing UX; heuristic detection can be bypassed. Recommend
  structural prevention as default but need owner confirmation before making it a hard requirement.
  - **RESOLVED (tech-lead, 2026-08-27):** structural prevention, not heuristic detection. A field bound to a
    secret-manifest path (in both the pipeline-step API and the Monaco-editor UI per FR-32/37) simply never
    accepts free text for that leaf — the save path only ever writes the placeholder marker there, and the
    real value is supplied out-of-band as a Jenkins credential ID. Heuristic secret-shape scanning is a
    known losing game (regex/entropy heuristics both false-positive on legitimate-looking non-secret strings
    and false-negative on secrets that don't match a known shape), and FR-14's "can't be fully automated with
    certainty" caveat is itself the tell that detection is the wrong tool — prevention removes the ambiguity
    FR-14 was written around instead of trying to police it after the fact.
- **OQ-2 — Naming convention for common/env Config Set pairs.** The plan doc's example
  (`sample-app-common` / `sample-app-dev`) is informal. Should the plugin enforce a naming convention
  (e.g. a shared "project key" field that ties one common + N env Config Sets together structurally), or
  leave pairing purely by operator-supplied string matching in pipeline step parameters (current MVP
  behavior, per the plan doc)? Unenforced pairing risks a typo silently merging the wrong env against the
  wrong common set with no error.
  - **RESOLVED (tech-lead, 2026-08-27):** enforce a shared `projectKey` field structurally rather than rely
    on free-text matching. Require `projectKey` on both the common Config Set and every env Config Set at
    creation time (FR-1's "scoped to a Config Project" becomes a real, typed field, not an implicit
    naming-convention inference), and have both pipeline steps take `projectKey` + `environment` as separate
    parameters rather than one composite string a caller has to assemble correctly by hand. This directly
    serves FR-29 (nothing may assume a project's name shape) and closes the exact failure mode this OQ
    raises — a typo silently pairing the wrong env against the wrong common set with no error — by making
    mismatch structurally impossible to express instead of just discouraged by convention.
- **OQ-3 — Build-version pinning: opt-in or default-on (FR-25–27)?** Today's design makes it opt-in (only
  activated when a `buildVersion` parameter is passed). Should the plugin instead default to always
  recording and (when available) honoring bindings, requiring an explicit opt-out for teams that don't want
  it? Opt-in risks teams "forgetting" to wire it and losing the exact safety guarantee that motivated
  building it.
  - **RESOLVED (tech-lead, 2026-08-27):** keep opt-in for v1 (current FR-25–27 behavior stands unchanged),
    but treat default-on as a planned fast-follow, not a closed question. Sequence it *after* FR-26's
    fallback-visibility guarantee (the "must surface that it fell back to active" behavior) is live and
    proven in practice — widening exposure to the pinning path (more builds silently start recording/relying
    on bindings) before its own visibility safety net is confirmed solid would multiply the blast radius of
    exactly the failure mode OQ-3 itself is worried about (a team unknowingly relying on a pin that silently
    didn't apply). Once FR-26 is verified working end-to-end, flip the default with an explicit opt-out flag
    for teams that don't want binding records accumulating.
  - **SUPERSEDED / RE-RESOLVED (business-analyst, 2026-09-03 — automatic build-identity pinning):** the
    fast-follow this OQ deferred is now executed, and in a stronger form than originally planned. FR-26's
    fallback-visibility guarantee has been live since the original resolution; the objection this OQ was
    protecting against — "widening exposure to the pinning path multiplies the blast radius of a team
    unknowingly relying on a pin that silently didn't apply" — is structurally moot now, because there is no
    longer a manual `buildVersion` a caller could forget to pass (FR-96): every real substitution
    automatically has a Run-identity key and automatically gets a binding (FR-97), so "forgetting to opt in"
    is no longer a possible failure mode at all. **Default-on, confirmed, with NO opt-out flag** — the
    originally-planned opt-out flag is deliberately dropped, not merely deferred again, for two independent
    reasons: (1) the flag's original justification was offsetting the burden/risk of an author-supplied
    identifier, which no longer exists (there is nothing left to "opt out" of supplying); (2) OQ-4 already
    resolved that Deployment Bindings are small records with no pruning need at any plausible scale, so
    "binding records accumulating" — the other stated justification for an opt-out — was never really a
    storage-cost concern to protect against. If a team surfaces a genuine, concrete reason to disable binding
    writes entirely in the future (not storage, not author burden — some other, currently unforeseen concern),
    that is a new decision to bring back to the owner then, not a flag to pre-build now against a hypothetical.
- **OQ-4 — Version history retention/pruning.** Is version history retained forever per Config Set, or is
  there an expected retention/pruning policy (count-based, age-based)? Same question for Deployment
  Bindings — do they accumulate one entry per build forever, or is there an expected cleanup horizon? This
  affects `$JENKINS_HOME` storage growth over the life of a long-running project.
  - **RESOLVED (tech-lead, 2026-08-27):** no pruning in v1, for both Config Set Versions and Deployment
    Bindings. FR-6 already requires full version history to "remain visible and re-activatable indefinitely"
    — a pruning policy would directly conflict with that FR, not just be a neutral storage optimization — and
    NFR-6 (auditability) is the entire reason this tool exists, so trimming history undercuts the tool's own
    purpose. These are small JSON/merge-patch-overlay blobs and per-build binding records, not large
    artifacts; storage growth is not a real problem at any plausible project scale for the foreseeable
    future. Revisit only if storage growth becomes a measured problem on a real instance, and if so prefer
    an age-based **archive** (move old versions out of the hot list, keep them retrievable) over any
    count-based **deletion** — deletion is irreversible and directly erodes the audit trail NFR-6 protects.
- **OQ-5 — Multi-environment credential scope.** Can the same Jenkins credential ID be legitimately reused
  across two different Config Sets/environments in the secrets manifest (e.g. a shared connection string
  credential), or must credential IDs be strictly one-to-one with a single (Config Set, path) pair? This
  affects whether the system should warn/block on detected credential-ID reuse.
  - **RESOLVED (tech-lead, 2026-08-27):** allow reuse, no warning or block. Jenkins credentials are already a
    shared, ID-is-a-reference model everywhere they're consumed (`withCredentials`, a pipeline step's
    `credentialsId` parameter, folder-scoped credential stores) — the same credential ID legitimately backing
    a shared connection string across two environments' secrets manifests is an ordinary, expected pattern,
    not a smell this plugin needs to police. Warning on reuse would just create noise DevOps engineers learn
    to ignore, which works against NFR-7 (fail loud only for things that actually need attention).
- **OQ-6 — Does "Activate/rollback" ever trigger a live deploy by itself?** UF-5 assumes rollback only
  repoints "active" and a separate deploy step must run afterward for it to take effect on a live server.
  Confirm this is intended — an alternative design could have activation optionally trigger an immediate
  redeploy, which is a materially different (and riskier) behavior that needs an explicit decision either
  way, not an assumption.
  - **RESOLVED (tech-lead, 2026-08-27):** no — activation is a pure metadata operation, never a live deploy
    trigger, exactly as UF-5 and FR-30/31's admin UI already assume. The wireframe/UI spec's own inline
    banner language ("this does not deploy") already encodes this decision; formalizing it here just makes it
    an explicit, binding answer rather than an implicit UI-copy choice. Coupling "flip the active pointer"
    to "push config to a live server" collapses two very different blast radii into a single button press and
    removes the deliberate human approval gate a real deploy should always have — a config rollback that
    accidentally also redeploys is a materially worse failure mode than a rollback that requires one extra,
    explicit step to take effect.
- **OQ-7 — Step-ordering enforcement.** FR-20 notes the plugin cannot itself force
  `configTemplateValidate` to run before `configTemplateSubstitute` in an arbitrary Jenkinsfile — should the
  substitute step defensively re-run (a lightweight form of) the same drift check internally before
  substituting, refusing to proceed if validation was skipped, or is "document the required order" judged
  sufficient? This affects whether FR-22's "fail if tokens remain" is the only safety net, or whether a
  second one is warranted.
  - **RESOLVED (tech-lead, 2026-08-27):** add a lightweight defensive re-check inside the substitute step
    itself, reusing the same flatten-and-compare logic the validate step already implements (FR-17), rather
    than relying purely on documented pipeline-authoring order. This is cheap to add (it's the same
    comparison FR-17 already performs, called from a second site), closes a real gap (nothing today stops a
    misordered or validate-skipped Jenkinsfile from reaching substitute), and is consistent with NFR-7's
    fail-loud principle — FR-22's "fail if tokens remain after substitution" is a good last-resort net but
    only catches missing keys, not orphaned-key drift or a stale effective-config mismatch that a fresh
    validate pass would have caught. Document the required order as primary guidance either way; this is a
    belt-and-suspenders addition, not a replacement for FR-20's documented convention.
- **OQ-8 — Multi-value/array content in Config Sets.** RFC 7396 Merge Patch has known limitations with JSON
  arrays (a patch always replaces an array wholesale, never merges element-by-element). Is array-valued
  config content in scope at all for v1, and if so, is "whole-array replacement only, no per-element env
  override" an acceptable and documented limitation?
  - **RESOLVED (tech-lead, 2026-08-27):** explicitly out of scope for v1, but documented, not silently
    unsupported. RFC 7396 Merge Patch's whole-array-replacement limitation (already cited in this OQ and
    baked into FR-8/9's null-removes/absent-inherits semantics) is a real, well-known constraint of the spec
    this plugin has already committed to (FR-8); solving per-element array merging on top of it is a
    materially bigger design problem (need an element-identity/key convention, a different patch format for
    array paths, or a JSON Patch–style operations list) that doesn't belong in this increment. Concrete
    deliverable: one clear paragraph in the plugin's README/help text stating that arrays are replaced
    wholesale by the env overlay layer, never merged element-by-element — so a DevOps engineer hits
    documented, expected behavior instead of a surprising silent gap the first time they put an array in a
    Config Set.
- **OQ-9 — Where does the live merge-preview computation (FR-38) execute?** Does the env-level screen's
  third "Merged result" panel recompute by round-tripping the Env override panel's current (unsaved) content
  to a new Stapler endpoint that runs the existing server-side `EffectiveConfigResolver` merge logic, or does
  it run entirely client-side via a bundled JS port of the same RFC 7396 merge-patch algorithm bundled
  alongside Monaco? A server round-trip guarantees the preview can never drift from the actual
  validate/substitute computation (FR-16's "no second, divergent code path" rule) but adds latency/network
  dependency to every keystroke-driven recompute; a client-side JS port avoids that but creates exactly the
  kind of second implementation FR-16 warns against, unless it's kept in lockstep with the Java
  implementation by some shared-source-of-truth mechanism (e.g. generated from the same spec, or covered by
  a cross-implementation parity test). Flagged in `jenkins-woolly-dragonfly.md` as worth an explicit
  `tech-lead` decision, not something to silently pick during implementation.
  - **RESOLVED (tech-lead, 2026-08-27):** server-side. The live merge-preview MUST recompute via a new,
    lightweight Stapler `doXxx` JSON endpoint that calls the exact same `EffectiveConfigResolver` Java
    class already used by the pipeline steps — not a client-side JS port of RFC 7396, even though
    well-established, spec-conformant npm implementations of RFC 7396 do exist (`json-merge-patch`,
    `tiny-merge-patch`, `json8-merge-patch`, etc.). Reasoning: FR-16's "no second, divergent code path"
    rule is explicit and already owner-approved, and a client-side port — however spec-conformant — is by
    definition a second implementation unless paired with an ongoing cross-implementation parity test
    suite, which is recurring cost this plugin doesn't need to take on. The latency concern motivating a
    client-side port doesn't hold up in practice: this is an admin JSON-editing screen (not a
    latency-sensitive consumer surface), the round-trip is in-process/local to the Jenkins controller (no
    external network hop), and a debounced call-per-edit-pause (not per-keystroke) is the same shape
    Jenkins core itself already uses for live field validation (`doCheckXxx` callbacks bound from
    `<f:validateButton>`/`<f:entry>`). Full reasoning, the concrete `doPreviewMerge` endpoint shape, and the
    Stapler JS-proxy binding mechanism are in
    `docs/development/tech-lead-ui-review-2026-08-27.md` (§1 and §3). This resolution surfaced a related gap
    not yet covered by any FR — how the preview panel should behave while the override panel's JSON is
    transiently invalid mid-edit — flagged there as a candidate new FR for a follow-up `business-analyst`
    pass, not authored into this document directly.
- **OQ-10 — Should the env-level "Generate Template" action (FR-41) also expose a way to get the
  common-only template (FR-15a) from the same env page, or must a DevOps engineer navigate back to the
  global Config Set page (FR-40) for that?** FR-41 as written gives the env page exactly one button
  producing the effective (merged) template, since that is the actually-useful artifact for a developer
  targeting that specific environment (UF-2). A secondary toggle/dropdown ("Merged" vs. "Common only") on
  the same env page is easy to imagine but adds a second output mode + a second on-page state to the same
  action, for a need (grabbing the unmerged common baseline while already looking at one specific
  environment) that is arguably rare and already fully served by navigating to the global page. This
  document resolves it to the simpler shape for v1 (one button, one output, per FR-41) rather than guessing
  the owner wants the added complexity, and flags the toggle as a candidate fast-follow, not a rejected
  idea — genuinely an owner call on whether the extra convenience is worth the extra UI surface, not
  something inferable from any already-approved FR.
- **OQ-11 — Exact API for detecting execution inside a `parallel {}` branch (FR-95).** Research (WebSearch/
  WebFetch against `jenkinsci/pipeline-plugin` PR #80 / JENKINS-26122, plus this repo's own `pom.xml`
  confirming `workflow-cps` is already a dependency) found a real, currently-existing Jenkins core mechanism:
  each `parallel` branch's flow-graph start node carries a `ThreadNameAction`
  (`org.jenkinsci.plugins.workflow.actions.ThreadNameAction`), attached via `ParallelStepExecution`'s
  `ParallelLabelAction`, and code elsewhere in Jenkins core detects "running inside a parallel branch" by
  requesting `FlowNode.class` in `StepDescriptor.getRequiredContext()` and walking that node's ancestry
  checking for a `ThreadNameAction`. This is grounded in real, citable Jenkins-core precedent, not invented
  for this spec — but it was NOT verified against this repository's exact pinned `workflow-cps`/
  `workflow-support`/`workflow-step-api` versions, nor prototyped against `setupConfigTemplate`'s own
  `StepExecution` shape, so it is flagged here rather than asserted as a closed implementation decision.
  **Needs `tech-lead` confirmation before implementation:** (a) does `FlowNode.getEnclosingBlocks()` alone
  suffice, or is manual parent-node walking required (as the PR #80 precedent does)? (b) does adding
  `FlowNode.class` to `setupConfigTemplate`'s `getRequiredContext()` have any side effects on step behavior
  or on Jenkins' own step-context wiring? (c) is there a simpler, more recent workflow-cps-provided helper
  (e.g. on `CpsThread`/`CpsThreadGroup`) that supersedes the FlowNode-walking approach found here? Not
  resolved by this document.

</open-questions>

<mvp-assessment>

## 8. Assessment of the already-pushed MVP against this spec

**Caveat (repeated from the header):** this assessment is based on the MVP's *documented* design (the plan
doc's description, and the task brief's summary: `ConfigSet`/`ConfigSetVersion` domain model with XStream
persistence, RFC 7396 merge-patch, two Pipeline steps, `ConfigDeploymentBinding`, 38 passing
unit/integration tests) — not a direct read of the pushed source, which this session could not access
(private repo, no GitHub-authenticated tool/shell available here). Ratings below distinguish "the design as
described appears to satisfy this" from "confirmed against actual code" — none of the latter are claimed
here. **Before this section is trusted for a go/no-go decision, `tech-lead` (with repo access) should
re-verify each row against the actual source and flip any "design-level: appears to satisfy" to a
code-verified status.**

| Req | Design-level assessment | Confidence |
|---|---|---|
| FR-1 (create Config Set by role/scope) | Design describes exactly this (common + env `ConfigSet`, same Java type). Appears **satisfied**. | Design-level only |
| FR-2 (append-only versions) | Design explicitly states append-only `ConfigSetVersion`. Appears **satisfied**. | Design-level only |
| FR-3 (exactly one active version) | Design states "exactly one `true` per ConfigSet, enforced in the save/activate code path." Appears **satisfied** — but "enforced in code" needs a direct test-name check (is there a test proving activating B deactivates A?) that this session could not perform. | Design-level only — **needs code verification** |
| FR-4 (monotonic version numbers) | Design states `versionNumber` = max+1, never reused. Appears **satisfied**. | Design-level only |
| FR-5 (note/author/timestamp mandatory) | Design lists these fields on `ConfigSetVersion` but does not state whether `note` is *enforced non-empty* at the API level. **Partially addressed** — field exists, enforcement unconfirmed. | Needs code verification |
| FR-6 (rollback repoints, doesn't duplicate; full history stays re-activatable) | Design explicitly matches Rojet's "flip active back, no content cloned" semantics. Appears **satisfied**. Retention/pruning behavior is unspecified (ties to OQ-4) — **not addressed** either way. | Design-level only |
| FR-7 (diff between any two versions) | Design mentions diff rendering (`java-diff-utils`) only in the **UI** section (§3, deferred milestone), not as a pipeline/API-level capability. As stated, the MVP (no-UI phase) likely **does not yet address** a callable diff capability independent of the UI. | Design-level only — likely gap |
| FR-8/FR-9 (env = RFC 7396 patch over common, null-removes semantics) | Design explicitly describes exactly this, citing RFC 7396's `null` = remove behavior. Appears **satisfied**. | Design-level only |
| FR-10 (effective config computed on demand, not persisted separately) | Design states "computed on read, never persisted as a third copy," used by both pipeline steps. Appears **satisfied**. | Design-level only |
| FR-11 (independent rollback of each layer) | Design explicitly states "you can roll back just the env override without touching common, or vice versa." Appears **satisfied**. | Design-level only |
| FR-12/13 (no real secrets persisted; resolved only at substitution) | Design states secret leaves hold literal `"__SECRET__"` in both layers at all versions, real values pulled via `withCredentials` only at substitution time. Appears **satisfied** at the design level — but this is exactly the kind of rule that needs an actual code/test check (is there a test asserting a save attempt with a non-placeholder value at a secret path is rejected, per FR-14?). | Design-level only — **needs code verification, esp. FR-14** |
| FR-14 (reject non-placeholder value at a secret-declared path) | Not mentioned anywhere in the plan doc's description of the MVP. Appears **not addressed** — the described model relies on convention (author always writes the marker) rather than enforcement. This is a real gap if confirmed. | Likely gap — **needs code verification** |
| FR-15/16 (template generation, same computation path as validate/substitute) | Plan doc frames "Generate template" as a **UI** button (§3, deferred milestone). No mention of a pipeline-step or API-level "generate template" capability existing in the no-UI MVP phase. **Not yet addressed** in the current pushed increment as far as this document's inputs show. | Design-level only — likely gap for the *current* increment (may simply be correctly deferred, not a defect — see note below) |
| FR-17–19 (validate: missing=fail, orphan=warn) | Design explicitly matches this exact behavior for `configTemplateValidate`, including the verification scenarios described (fail naming exact token; non-fatal `[WARN] Orphan store keys:` line). Appears **satisfied**. | Design-level only |
| FR-20 (validate must run before substitute — ordering) | Not something the plugin itself can enforce (correctly identified as a pipeline-authoring convention in this document's own FR-20 caveat); MVP doesn't claim to enforce it either. **Correctly out of the plugin's own control** — not a gap, provided OQ-7's defensive-recheck question is resolved. | N/A |
| FR-21/22 (substitute: real values in, fail if tokens remain) | Design explicitly restates the existing safety guard ("throws if any `#{...}#` remains unsubstituted"). Appears **satisfied**. | Design-level only |
| FR-23/24 (binding created/updated on real substitution; report versions used) | Design explicitly describes this for `ConfigDeploymentBinding`. Appears **satisfied**, though "report which versions were used" (an explicit build-log line) is not explicitly confirmed as implemented output vs. just implied by the mechanism. | Design-level only |
| FR-25/26/27 (pinning: use binding if present, fallback+report if absent, no-op if no buildVersion given) | Design explicitly describes the pinned-lookup and unchanged-default-when-no-`buildVersion`-given behavior. The **"must surface that it fell back" (FR-26)** half is not explicitly confirmed — the design doc describes the fallback behavior but not a log/output line announcing it. **Partially addressed** — likely gap on the visibility half specifically. | Design-level only — **needs code verification** on FR-26 |
| FR-28/29 (multi-project isolation, no hardcoded project names in the model/code) | Design explicitly states the domain model is generic (no motivating-client-project references in the Java type model itself) and keyed per project/env string. Appears **satisfied** at the design level — but NFR-4 (no client name anywhere in the *repo*, including docs/tests/sample data) needs an actual grep of the pushed repo, which this session could not do. **Flag as unverified**, not confirmed-clean. | **Needs code verification — cannot be waived** |
| NFR-1 (no external DB) | Design explicitly uses XStream under `$JENKINS_HOME`. Appears **satisfied**. | Design-level only |
| NFR-2 (not git-backed) | Design confirms no git dependency. Appears **satisfied**. | Design-level only |
| NFR-3 (no real secrets at rest) | Same as FR-12/13 — appears satisfied by convention, not by enforcement (FR-14 gap applies here too). | Design-level only |
| NFR-4 (no client name anywhere in repo) | **Cannot be assessed without reading the actual repo** — the task brief itself flags this as a requirement precisely because the MVP was pushed without this kind of review. Treat as **unverified, do not assume clean**, until an actual grep of the repo (README, package names, test fixtures, sample `ConfigSet` keys) is done. | **Needs code verification — highest-priority check** |
| NFR-5 (installable multi-instance) | Design targets a standard `.hpi`; distribution mechanics (private update center) are explicitly deferred (§6), consistent with the current increment. Not a gap for this stage. | Design-level only |
| NFR-6 (auditability: author+timestamp) | Present on `ConfigSetVersion` per design; not explicitly stated for `ConfigDeploymentBinding` updates (does a binding update record who/when it was last touched, or only what version numbers?). **Partially addressed** — needs confirmation. | Design-level only — needs confirmation |
| NFR-7 (fail loud / never silent) | Validate/substitute failure messaging described explicitly and specifically; the FR-26 fallback-announcement gap above is the one concrete place this principle may not be fully honored yet. | Design-level only |

**Overall read:** the described MVP design covers the *versioning + merge + validate/substitute + pinning*
mechanics solidly and appears to satisfy the majority of core functional requirements at the design level.
The clearest likely gaps, if confirmed by an actual code read, are: **FR-14** (no described enforcement
against a real secret being pasted into a secret-declared field — currently convention-only), **FR-7 and
FR-15/16** (diff and template-generation are framed as UI-milestone features, so a no-UI MVP may have no
callable equivalent yet — this may be a correct, intentional deferral rather than a defect, but needs
confirming it wasn't silently dropped rather than deliberately sequenced), **FR-26** (fallback-to-active
visibility when no pin exists), and — most importantly for this specific project's constraints —
**NFR-4** (the "never name the originating client" rule), which is exactly the kind of rule an
implementer-agent-only pass with no BSA/tech-lead review is most likely to have missed, and which this
session had no way to independently verify. Recommend `tech-lead` run one grep-and-read pass over the
actual repository (README, package/class names, test fixtures/sample data, commit messages) specifically
for NFR-4 before this MVP is treated as a safe public-facing baseline.

</mvp-assessment>
