# config-template-sync — Tech-lead contract review of the base-chain visualization redesign (FR-71–FR-74) and `explicitlyStandalone` UI surfacing (FR-89)

> Scope: finalizes the exact Jelly/JS/Java contract for FR-71–FR-74 (nested-accordion base-chain rows,
> type-filtered project picker, Merged-bases/Env-override/Merged-result layout, "Discard all changes") and
> FR-89 (surfacing `explicitlyStandalone` in the base-chain editor and version-history display) before
> `jenkins-plugin-developer` implements them, per the mandatory SDLC chain (`business-analyst` →
> `ux-ui-designer` → `tech-lead` → implementer). Depth/format matches
> `docs/development/tech-lead-pipeline-resolution-controls-review-2026-09-03.md`.
>
> Grounded by reading the actual pushed source — `ui/{EnvConfigSetPage,ConfigSetPage,CommonConfigSetPage}.java`,
> `ui/EnvConfigSetPage/index.jelly`, `model/{ConfigSet,ConfigSetVersion,BaseConfigReference,ResolvedBaseVersion}.java`,
> `steps/StepSupport.java` — and the wireframe at `D:\Repos\.copilot-knowledge\spaces\config-template-sync\
> docs\design\wireframe-layout.md` (v6 pass, 2026-09-03). Author: `tech-lead`, 2026-09-03. No code changed by
> this document — contract/design only.
>
> **Repo-location flag (process, not scope):** the wireframe this contract is built against currently lives
> only at `D:\Repos\.copilot-knowledge\spaces\config-template-sync\docs\design\wireframe-layout.md` — it does
> NOT exist anywhere under this repo's own `docs/`. Per this owner's standing rule ("personal-repo projects
> keep their documentation in the repo, not in a separate Copilot Space folder" — already applied to
> `docs/business/requirements.md` itself, see that file's own consolidation note), this file should be copied
> into `docs/design/wireframe-layout.md` in this repo as part of landing this feature, so the wireframe travels
> with the code the same way the requirements doc now does. Flagged here rather than silently fixed, since it
> is a docs-location housekeeping item, not part of FR-71–FR-74/FR-89's own contract.
>
> **FR-89 has no wireframe placement decided anywhere (confirmed by direct inspection, not assumed):** the
> requirements doc says so explicitly ("Exact wireframe treatment ... is explicitly NOT decided here — route
> to `ux-ui-designer`"), and the wireframe file itself was checked end-to-end — its v6 pass (2026-09-03,
> the *same day* as FR-86–FR-89's `business-analyst` pass) covers FR-71–FR-74 in full diagram detail but
> contains zero mentions of `explicitlyStandalone`, "standalone," or FR-86/87/88/89 anywhere. So unlike
> FR-71–FR-74 (wireframed, ready for a straight contract pass), FR-89 genuinely has no design to contract
> against. Per the owner's standing autonomy rule (make the reversible call myself rather than block), §5
> below makes a concrete placement decision with reasoning — but this is explicitly flagged as a recommended
> `ux-ui-designer` follow-up to confirm/refine before or shortly after implementation, not a substitute for
> one. This does not block FR-71–FR-74, which have no such dependency.
>
> **Real gap found, not from the requirements doc — a confirmed, load-bearing bug in the current code, found
> by reading `ConfigSetPage.saveImpl`/`ConfigSet.addVersion` side by side (§1 below):** the UI's Save path
> cannot persist `explicitlyStandalone=true` at all today. `ConfigSet` already has the correct 6-arg
> `addVersion(..., baseChain, explicitlyStandalone)` overload (implemented, per its own javadoc, for FR-86/87)
> — but `ConfigSetPage.saveImpl` (the ONLY method the UI's Save/Save & Activate buttons reach) still calls the
> OLDER 5-arg `addVersion(content, note, author, timestamp, baseChain)` overload, which hardcodes
> `explicitlyStandalone = false` via its own delegating constructor. So even after this document's Jelly/JS
> changes add an `explicitlyStandalone` checkbox to the page, checking it and clicking Save would silently
> save `explicitlyStandalone = false` regardless — a structurally invisible failure (no exception, no error
> banner) that only a targeted `JenkinsRule` test (save with the checkbox checked, then read back
> `getVersions().get(N).isExplicitlyStandalone()`) would catch. This is this document's single most
> load-bearing finding — flagged the same way the pipeline-resolution-controls review flagged the
> `workflow-api` dependency gap: not asked for directly, but blocks the feature from working at all if missed.
> Fixed in §1.

<decision-1-savepath-gap>

## 1. `ConfigSetPage.saveImpl`/`doSubmitSave`/`jsSave` — real gap: never plumbs `explicitlyStandalone` through to `ConfigSet.addVersion`

**Confirmed by direct code read, not inferred:** `ConfigSetPage.saveImpl` (`ui/ConfigSetPage.java` lines
~210–265) has this exact call today:

```java
newVersion = configSet.addVersion(content, note, author, System.currentTimeMillis(), baseChain);
```

— the 5-arg overload, which per `ConfigSet.java` line 192 delegates as
`addVersion(contentJson, note, author, timestampEpochMillis, baseChain, false)`. There is no code path
anywhere in `ConfigSetPage`, `EnvConfigSetPage`, or `CommonConfigSetPage` that ever calls the 6-arg overload.
**Fix — additive parameter threaded end to end:**

```java
// saveImpl's signature gains one parameter, threaded from both callers below.
private SaveOutcome saveImpl(String content, String note, boolean activate, String baseChainJson,
                              String contentTypeParam, boolean explicitlyStandalone) {
    // ... unchanged body until the addVersion call ...
    try {
        newVersion = configSet.addVersion(content, note, author, System.currentTimeMillis(), baseChain,
                explicitlyStandalone);
    } catch (IllegalArgumentException e) {
        // FR-14/OQ-1/FR-53/FR-86/FR-87: every ConfigSet.addVersion validation failure — including the
        // NEW "explicitlyStandalone on COMMON" and "explicitlyStandalone=true with non-empty baseChain"
        // checks — surfaces through this SAME catch, no new branch needed (ConfigSet.addVersion already
        // throws the same exception type for all of them).
        throw new Failure(Messages.ConfigSetPage_SaveBlocked(e.getMessage()));
    }
    // ... unchanged rest ...
}
```

**`doSubmitSave` (classic path)** gains one more `@QueryParameter(fixEmpty = true) String explicitlyStandalone`
(Stapler's own "checkbox → `"on"`/absent" convention, matching how `activate` is already read on this exact
method), converted the same way `activate` already is (`explicitlyStandalone != null`):

```java
public void doSubmitSave(StaplerResponse rsp,
                          @QueryParameter String content,
                          @QueryParameter String note,
                          @QueryParameter(fixEmpty = true) String activate,
                          @QueryParameter(fixEmpty = true) String baseChainJson,
                          @QueryParameter(fixEmpty = true) String contentType,
                          @QueryParameter(fixEmpty = true) String explicitlyStandalone) throws IOException {
    Jenkins.get().checkPermission(Jenkins.ADMINISTER);
    SaveOutcome outcome = saveImpl(content, note, activate != null, baseChainJson, contentType,
            explicitlyStandalone != null);
    // ... unchanged ...
}
```

**`jsSave` (the actual, live path both pages' Save/Save & Activate buttons call)** gains one more boolean
field on the same JSON payload object, read the identical way `activate` already is:

```java
boolean explicitlyStandalone = payload.has("explicitlyStandalone")
        && !payload.get("explicitlyStandalone").isJsonNull()
        && payload.get("explicitlyStandalone").getAsBoolean();
SaveOutcome outcome = saveImpl(content, note, activate, baseChainJson, contentType, explicitlyStandalone);
```

**`CommonConfigSetPage` never sends this field** (mirrors how it never sends `baseChainJson` today —
`explicitlyStandalone` is meaningful only on an ENV-role Config Set per FR-86, and `ConfigSet.addVersion`
already rejects `true` on a COMMON-role save) — its Jelly/JS is unaffected by this section; the payload
object simply omits the key, and `jsSave`'s `payload.has(...)` check above already treats an absent key as
`false`, matching `ConfigSet.addVersion`'s own COMMON-role default expectation exactly.

**Why this is a required parameter addition, not inferable from `baseChainJson` alone:** an operator could
in principle leave the base-chain editor's draft rows completely empty (0 rows) WITHOUT intending
`explicitlyStandalone` — that combination is exactly what `ConfigSet.addVersion`'s existing FR-52-vs-FR-87
distinction exists to make meaningful (an empty `baseChain` on its own still means "apply FR-52's synthesized
default," never "standalone," per FR-88's own explicit non-reinterpretation rule). So the checkbox's boolean
state must travel as its own independent field, never derived from "is the chain editor's row count zero."

</decision-1-savepath-gap>

<decision-2-accordion-rows>

## 2. Nested-accordion base-chain rows (FR-71) — exact Jelly/JS structure

**Current state (confirmed by reading `EnvConfigSetPage/index.jelly` lines 299–329, 592–728):** the
base-chain editor already renders each row as a flat `<tr>` in `#baseChainRowsTable` via
`buildBaseChainRowElement` — there is no per-row expand/collapse today. The "resolved content" view that
exists today lives in a SEPARATE, page-level `#inspectChainDrawer` (toggled by `#inspectChainToggle`,
rendered by `renderInspectDrawer()`), which shows ALL rows' resolved content in one drawer, not each row's
own accordion. This entire drawer, plus the dead `#globalEditor` static-placeholder box (both already
confirmed dead/superseded per the wireframe's own audit note), are REMOVED — not kept alongside the new
per-row accordion.

**New per-row structure — `buildBaseChainRowElement` gains an accordion body, `renderBaseChainRows` tracks
expand state across re-renders:**

```java
/* index.jelly's baseChainRowsTable header row gains a leading toggle column: */
<thead><tr><th/><th>#</th><th>Project</th><th>Mode</th><th>Pinned version</th><th/></tr></thead>
```

```js
// Base-chain editor draft state gains one more parallel array, indexed identically to baseChainRows —
// tracks which rows are currently expanded, independent of add/remove/reorder (FR-71: "regardless of
// any row's expanded/collapsed state" for the controls; expand state itself is purely a UI concern and
// is NOT part of the saved/serialized baseChainRows payload).
var baseChainRowExpanded = []; // boolean[], same length/index as baseChainRows

function toggleBaseChainRowExpanded(index) {
  baseChainRowExpanded[index] = !baseChainRowExpanded[index];
  renderBaseChainRows();
}

function buildBaseChainRowElement(row, index) {
  // ... existing idxTd/projectTd/modeTd/versionTd/actionsTd construction, UNCHANGED ...

  // NEW: leading toggle cell, prepended before idxTd.
  var toggleTd = document.createElement('td');
  var toggleLink = document.createElement('a');
  toggleLink.href = '#';
  toggleLink.className = 'ctsync-basechain-row-toggle';
  toggleLink.innerHTML = baseChainRowExpanded[index] ? '&#9662;' : '&#9656;'; // ▾ / ▸
  toggleLink.onclick = (function (idx) {
    return function (e) { e.preventDefault(); toggleBaseChainRowExpanded(idx); };
  })(index);
  toggleTd.appendChild(toggleLink);

  var tr = document.createElement('tr');
  tr.className = 'ctsync-basechain-row';
  tr.appendChild(toggleTd);
  tr.appendChild(idxTd);
  // ... projectTd/modeTd/versionTd/actionsTd appended exactly as today ...

  if (!baseChainRowExpanded[index]) {
    return tr; // collapsed: header row only, matches wireframe's "no resolved-content view shown"
  }

  // Expanded: a second <tr> immediately below, one wide <td colspan> holding the read-only resolved
  // view, wrapped in a DocumentFragment so renderBaseChainRows can append both rows for this index in
  // order (a plain array-of-two-elements return, unlike the collapsed case's single <tr>).
  var detailTr = document.createElement('tr');
  detailTr.className = 'ctsync-basechain-row-detail';
  var detailTd = document.createElement('td');
  detailTd.colSpan = 5;
  var perRef = findPerReferenceFor(row, index); // see below — reused from the SAME doComputeMerge response
  if (perRef) {
    var pre = document.createElement('pre');
    pre.className = 'ctsync-basechain-resolved-pre';
    pre.textContent = perRef.contentJson; // already serialized text in the resolved ContentType (FR-71)
    var caption = document.createElement('div');
    caption.className = 'ctsync-basechain-resolved-caption';
    caption.textContent = 'Resolved content (read-only) — ' + perRef.projectKey + ', ' + perRef.pinMode
        + ' (v' + perRef.resolvedVersionNumber + ')';
    detailTd.appendChild(caption);
    detailTd.appendChild(pre);
  } else {
    detailTd.textContent = 'Not yet resolvable — check the project/version picker above.';
  }
  detailTr.appendChild(detailTd);
  return [tr, detailTr]; // renderBaseChainRows appends every element of this array in order
}

function renderBaseChainRows() {
  var body = document.getElementById('baseChainRowsBody');
  body.innerHTML = '';
  for (var i = 0; i < baseChainRows.length; i++) {
    var built = buildBaseChainRowElement(baseChainRows[i], i);
    if (Array.isArray(built)) {
      built.forEach(function (el) { body.appendChild(el); });
    } else {
      body.appendChild(built);
    }
  }
  document.getElementById('baseChainField').value = JSON.stringify(baseChainRows);
}

// FR-71: "the SAME EffectiveConfigResolver/doComputeMerge response already driving the Merged bases and
// Merged result panels — there MUST NOT be a second, divergent computation." lastPerReference (already
// populated by recomputeMerge from r.perReference, unchanged) is matched back to a given draft row by
// (projectKey, pinMode, pinnedVersionNumber-if-pinned) — array position alone is not reliable once
// add/remove/reorder is mixed with an in-flight debounced response, so match on content, not index.
function findPerReferenceFor(row, index) {
  for (var i = 0; i < lastPerReference.length; i++) {
    var ref = lastPerReference[i];
    if (ref.projectKey === row.projectKey && ref.pinMode === row.pinMode
        && (row.pinMode !== 'PINNED' || ref.resolvedVersionNumber === row.pinnedVersionNumber)) {
      return ref;
    }
  }
  return null; // e.g. the chain has an unresolvable/duplicate entry — recomputeMerge's ok:false path
                // already leaves lastPerReference at its last-good value, so this only returns null for
                // a row that itself was never resolvable even at that last-good state.
}
```

**`renderInspectDrawer`/`toggleInspectDrawer`/`#inspectChainToggle`/`#inspectChainDrawer`/`#globalEditor`
and the entire "Effective base" `<td>` in `#threePanelTable` are DELETED** (superseded wholesale by §3's new
layout, not kept as a second, now-redundant read-only view — the wireframe's v6 note is explicit that both
are removed, not retained alongside the new design).

**`recomputeMerge` needs one addition:** after `lastPerReference = r.perReference || [];`, also call
`renderBaseChainRows()` so any currently-expanded row's resolved-content view refreshes from the freshly
landed response (today `recomputeMerge` only refreshes the drawer via `renderInspectDrawer()`; the accordion
replaces that call site).

**Add/remove/reorder controls remain fully functional regardless of expand state (FR-71's own explicit
requirement):** confirmed automatically satisfied by this design — `upBtn`/`downBtn`/`removeBtn` are built
identically regardless of `baseChainRowExpanded[index]`'s value, and `moveBaseChainRow`/`removeBaseChainRow`
already operate on `baseChainRows` by index with no dependency on expand state. **One required fix,
call it out explicitly so the implementer doesn't skip it:** `removeBaseChainRow`/`moveBaseChainRow` must
also splice/move the corresponding entry in the NEW `baseChainRowExpanded` array in lockstep with
`baseChainRows`, or expand state will silently attach to the wrong row after a reorder/removal:

```js
function removeBaseChainRow(index) {
  baseChainRows.splice(index, 1);
  baseChainRowExpanded.splice(index, 1); // NEW — keep both arrays in lockstep
  renderBaseChainRows();
  recomputeMerge();
}

function moveBaseChainRow(index, delta) {
  var target = index + delta;
  if (target < 0 || target >= baseChainRows.length) { return; }
  var row = baseChainRows.splice(index, 1)[0];
  baseChainRows.splice(target, 0, row);
  var expanded = baseChainRowExpanded.splice(index, 1)[0]; // NEW
  baseChainRowExpanded.splice(target, 0, expanded);         // NEW
  renderBaseChainRows();
  recomputeMerge();
}

function addBaseChainRow() {
  var defaultProject = availableProjectKeys.length > 0 ? availableProjectKeys[0] : '';
  baseChainRows.push({ projectKey: defaultProject, pinMode: 'ACTIVE', pinnedVersionNumber: 0 });
  baseChainRowExpanded.push(false); // NEW — new rows start collapsed
  renderBaseChainRows();
  recomputeMerge();
}
```

</decision-2-accordion-rows>

<decision-3-type-filter-picker>

## 3. Type-filtered project picker (FR-72) — client-side filtering, `doSave`'s FR-61 check is the unchanged backstop

**No new server endpoint needed.** `getCommonVersionCatalogJsonForScript()` (`EnvConfigSetPage.java`) already
serializes every COMMON Config Set's version list keyed by `projectKey`; this document adds ONE more field to
that same payload — the resolved `ContentType` per project — so the client can filter without a round trip:

```java
public String getCommonVersionCatalogJsonForScript() {
    JsonObject catalog = new JsonObject();
    JsonObject typesByProject = new JsonObject(); // NEW — sibling map, same key set as catalog
    for (ConfigSet common : repository.listAllCommon()) {
        JsonArray versions = new JsonArray();
        for (ConfigSetVersion v : common.getVersions()) {
            JsonObject versionEntry = new JsonObject();
            versionEntry.addProperty("version", v.getVersionNumber());
            versionEntry.addProperty("note", v.getNote());
            versionEntry.addProperty("timestampEpochMillis", v.getTimestampEpochMillis());
            versions.add(versionEntry);
        }
        catalog.add(common.getProjectKey(), versions);
        typesByProject.addProperty(common.getProjectKey(), common.getContentType().name()); // NEW
    }
    JsonObject wrapper = new JsonObject(); // NEW top-level shape — was a bare `catalog` object before
    wrapper.add("versionsByProject", catalog);
    wrapper.add("typeByProject", typesByProject);
    return toJsScriptStringLiteral(wrapper.toString());
}
```

**Breaking shape change to `__commonVersionCatalog`, flagged explicitly:** this wraps the previously bare
`{projectKey: [...]}` object inside `{versionsByProject: {...}, typeByProject: {...}}`. Every existing read
site (`commonVersionCatalog[baseChainRows[index].projectKey]` in `onBaseChainModeChange`, and the version
`<select>` population in `buildBaseChainRowElement`) must be updated to
`commonVersionCatalog.versionsByProject[...]` — a mechanical rename, called out here so it is not missed as
an "orphaned" read of the old shape.

**Client-side filter logic — row #1 unfiltered, every later row filtered to row #1's resolved type:**

```js
// FR-72: row #1 (index 0) always lists every available project (NFR-8: unrestricted cross-project
// references) — it is what ESTABLISHES the chain's content-type context, so it cannot itself be
// filtered by a context that doesn't exist yet. Every row after it is filtered.
function projectOptionsForRow(index) {
  if (index === 0 || baseChainRows.length === 0) {
    return availableProjectKeys;
  }
  var contextType = commonVersionCatalog.typeByProject[baseChainRows[0].projectKey];
  if (!contextType) {
    return availableProjectKeys; // row #1's project not yet resolvable — don't over-filter on nothing
  }
  return availableProjectKeys.filter(function (key) {
    return commonVersionCatalog.typeByProject[key] === contextType;
  });
}
```

`buildBaseChainRowElement`'s existing `for (var p = 0; p < availableProjectKeys.length; p++)` loop building
`projectSelect`'s `<option>`s is changed to iterate `projectOptionsForRow(index)` instead of the raw
`availableProjectKeys` array — the only change needed at that call site.

**Changing row #1's project re-filters every later row (FR-72's explicit requirement):**
`onBaseChainProjectChange` already calls `renderBaseChainRows()` unconditionally on every project change
(including row #1's), and since `projectOptionsForRow` is now evaluated fresh on every render call (not
cached), later rows' `<select>` option lists are automatically re-filtered for free — no new invalidation
logic needed. **One edge case the implementer must handle explicitly:** if row #1's project changes to a
NEW content type and an already-selected row #2+ project no longer matches that type, that row's OWN
`projectKey` value is left as-is in `baseChainRows` (never silently reset) — `doSave`'s existing FR-61
`checkChainTypeConsistencyOrFail` server-side check remains the authoritative backstop for exactly this
case (a stale selection that predates a row-#1 change), matching FR-72's own explicit wording ("FR-61 is
unchanged and remains the authoritative backstop — this FR does not weaken or replace it"). The rendered
`<select>` for that now-stale row simply won't show the current value among its filtered `<option>`s (falls
back to the browser's own "no matching option selected" rendering) — a deliberate, visible signal to the
operator that this row now needs attention, rather than a silent auto-fix that could surprise them.

</decision-3-type-filter-picker>

<decision-4-three-pane-layout>

## 4. Merged-bases / Env-override / Merged-result layout (FR-73) — CSS grid replaces the `<table>`, no new AJAX endpoint

**No new server contract at all — this is purely a layout/DOM change.** `doComputeMerge`/`previewMergeImpl`
already return exactly the three things this layout needs (`r.merged` for Merged result, `r.perReference`
for both the per-row accordion AND the new Merged-bases panel, `r.contentType` for the shared language mode)
— the ONLY change needed is what the client does with the existing response, not the response shape itself.

**One NEW field on the existing response, needed because "Merged bases" (the fold of the chain BEFORE the
env override is applied) is not the same value as `r.merged` (the fold AFTER the override is applied) —
today's endpoint only returns the latter:**

```java
// previewMergeImpl, EnvConfigSetPage.java — after building baseContents (the existing loop), fold them
// alone (no envPatch) for the NEW mergedBases field, reusing the identical EffectiveConfigResolver call
// with a null overlay — no second resolver implementation, per FR-16/FR-71's "no divergent computation"
// principle stated for the per-row accordion and restated here for the same reason.
TreeNode mergedBasesOnly = EffectiveConfigResolver.resolveChain(type, baseContents, null);
result.put("mergedBases", format.serialize(mergedBasesOnly)); // NEW field
// ... existing "merged" (= mergedBasesOnly ⊕ overlayRaw) computation and result.put("merged", ...) unchanged ...
```

**DOM/CSS restructure — replaces `#threePanelTable` entirely (removed, not left dormant):**

```html
<div id="mergeLayout" class="ctsync-merge-grid">
  <div class="ctsync-merge-top">
    <div class="ctsync-merge-pane" id="mergedBasesPane">
      <h3>Merged bases (read-only, live)</h3>
      <div id="mergedBasesEditor" style="height:340px;border:1px solid #ccc"/>
    </div>
    <div class="ctsync-merge-pane" id="envOverridePane">
      <!-- existing ctsync-editor-header-row / modeEditBtn / checkingIndicator / saveBanner /
           compareBanner / #overrideEditor block, UNCHANGED content, just moved into this pane -->
    </div>
  </div>
  <div class="ctsync-merge-pane ctsync-merge-full" id="mergedResultPane">
    <h3>Merged result (read-only, live)</h3>
    <div id="mergedEditor" style="height:380px;border:1px solid #ccc"/>
    <div id="mergedStaleIndicator" style="display:none;color:#a33">
      showing last valid merge — override JSON is currently invalid
    </div>
  </div>
</div>
```

```css
/* Desktop: two columns on top, full-width row below (FR-73). Tablet/mobile: all three stack, same
   order, per the wireframe's own "2-then-1 collapses directly to 3-stacked, no intermediate state"
   reasoning — reuses this page's EXISTING 1100px breakpoint rather than inventing a new one. */
.ctsync-merge-top { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.ctsync-merge-full { margin-top: 12px; }
@media (max-width: 1100px) {
  .ctsync-merge-top { grid-template-columns: 1fr; }
}
```

`mergedEditor` (Merged result) is unchanged in every respect except which container div wraps it. A NEW
Monaco instance, `mergedBasesEditor`, is created identically to `mergedEditor` (`readOnly: true`,
`language: languageForType(currentContentType)`) at the same point in the `require(['vs/editor/editor.main'])`
callback, and `recomputeMerge` gains one line setting its value:

```js
mergedBasesEditor.setValue(r.mergedBases); // NEW — alongside the existing mergedEditor.setValue(r.merged)
```

`monaco.editor.setModelLanguage` is called on `mergedBasesEditor.getModel()` in lockstep with the two
existing calls (`overrideEditor`, `mergedEditor`) inside the same `if (r.contentType !== currentContentType)`
branch — three editors now share the language-sync branch instead of two.

**`editorDetails`'s `toggle` listener (accordion re-measure fix) gains `mergedBasesEditor.layout()`** alongside
its existing `overrideEditor`/`mergedEditor`/`diffEditor`/`generatedTemplateEditor` calls — the same Monaco
zero-size-on-closed-`<details>` issue this page already defends against for every other live editor instance
in this section applies identically to the new one.

</decision-4-three-pane-layout>

<decision-5-explicitlystandalone-surfacing>

## 5. `explicitlyStandalone` surfacing (FR-89) — placement decision (no wireframe exists, see the header flag)

**Decision: a checkbox directly above the base-chain editor's row table, mutually exclusive with having any
rows in the chain — checking it disables (not hides) the "+ Add base" button and every existing row's
controls, greys the rows out, and clears `baseChainRows`/`baseChainRowExpanded` to empty on check.**

```html
<!-- Sits between the existing <p> description paragraph and the <table id="baseChainRowsTable">,
     inside the existing #baseChainEditor div — no new <details> section, since it's inseparable from
     the editor it configures, same placement principle the wireframe already uses elsewhere on this
     page (e.g. the content-type picker on the Global page sits directly above the box it configures). -->
<div class="ctsync-standalone-row">
  <label>
    <input type="checkbox" id="explicitlyStandaloneCheckbox" onchange="onExplicitlyStandaloneChange(this.checked);"/>
    This env Config Set is explicitly standalone — zero base configs, its own content is the entire
    effective configuration.
  </label>
</div>
```

```js
function onExplicitlyStandaloneChange(checked) {
  explicitlyStandalone = checked;
  if (checked) {
    baseChainRows = [];
    baseChainRowExpanded = [];
  } else if (baseChainRows.length === 0) {
    // Unchecking with zero rows would otherwise leave an ambiguous "empty chain, not standalone" draft
    // that FR-52's synthesized default already treats as "this env's own project, ACTIVE" anyway — seed
    // the same FR-56 default a brand-new Config Set gets, so the UI never shows a visibly-empty table
    // with the checkbox off (which would look identical to the standalone state it's distinguishing
    // itself from — the exact ambiguity FR-89 exists to resolve).
    baseChainRows = [{ projectKey: availableProjectKeys.length > 0 ? availableProjectKeys[0] : '',
                       pinMode: 'ACTIVE', pinnedVersionNumber: 0 }];
    baseChainRowExpanded = [false];
  }
  document.getElementById('addBaseChainRowBtn').disabled = checked;
  renderBaseChainRows();
  recomputeMerge();
}
```

**`addBaseChainRow`/`renderBaseChainRows` gain one guard each** so the table visually communicates the
mutual-exclusion even if some other code path is reached with the checkbox checked (defense in depth, mirrors
this codebase's existing convention of never trusting a single UI gate alone — e.g. `doSave`'s server-side
FR-61 recheck):

```js
function addBaseChainRow() {
  if (explicitlyStandalone) { return; } // defensive no-op — button is disabled, but guard anyway
  // ... unchanged ...
}
```

**Reasoning for "disable, don't hide" the row table while checked:** hiding the table entirely would remove
the operator's ability to SEE that switching the checkbox off restores exactly the rows that were there
before (there aren't any — it always reseeds the FR-56 default per the code above) — greying it out with the
table structure still visible communicates "this control is currently irrelevant" more clearly than making it
vanish, consistent with the wireframe's own established pattern of disabling (never hiding) the "Generate
Template" button when there's nothing to template (FR-40).

**`previewMergeImpl`/`doComputeMerge` gains one parameter — the load-bearing fix for the live-preview gap
found while grounding this section (§ below), not something the requirements doc asked for directly:**

**Real gap found: today's live-merge preview cannot represent "explicitly standalone, zero bases" — it always
falls back to FR-52's synthesized single-entry default for an empty chain.** Confirmed by reading
`previewMergeImpl`:

```java
if (chain.isEmpty()) {
    chain = Collections.singletonList(BaseConfigReference.active(projectKey));
}
```

This unconditionally applies FR-52's default the moment the draft chain is empty — there is no way, today,
for the live "Merged bases"/"Merged result" preview to show what an `explicitlyStandalone=true` draft would
actually resolve to (its own override content alone, FR-87) while the operator is still mid-edit, even though
`StepSupport.effectiveBaseChain` (the pipeline-side equivalent) already got this exact FR-87 guard in the
prior tech-lead pass. **Fix — thread the checkbox's live (unsaved) state through to the preview call:**

```java
public JSONObject doComputeMerge(@QueryParameter String overlayJson, @QueryParameter String baseChainJson,
                                  @QueryParameter boolean explicitlyStandalone) {
    Jenkins.get().checkPermission(Jenkins.ADMINISTER);
    return previewMergeImpl(overlayJson, baseChainJson, explicitlyStandalone);
}

@JavaScriptMethod(name = "previewMerge")
public JSONObject jsPreviewMerge(String overlayJson, String baseChainJson, boolean explicitlyStandalone) {
    Jenkins.get().checkPermission(Jenkins.ADMINISTER);
    return previewMergeImpl(overlayJson, baseChainJson, explicitlyStandalone);
}

private JSONObject previewMergeImpl(String overlayRaw, String baseChainJson, boolean explicitlyStandalone) {
    // ... unchanged chain parse ...
    if (chain.isEmpty()) {
        if (!explicitlyStandalone) {
            // FR-52's default — UNCHANGED behavior for the ordinary "empty chain, not standalone" case.
            chain = Collections.singletonList(BaseConfigReference.active(projectKey));
        }
        // else: FR-87 — leave chain empty; BaseChainResolver.resolve(repository, emptyList()) already
        // returns an empty resolved list with no special-casing needed (mirrors StepSupport's own
        // effectiveBaseChain guard, restated here for the UI's decoupled-from-steps code path per this
        // class's own existing "ui stays decoupled from steps" convention).
    }
    // ... unchanged resolution/fold logic — an empty `resolved` list already correctly produces an
    // empty baseContents list and folds to {} before the overlay is applied, per §4a of the pipeline
    // review's identical reasoning for EffectiveConfigResolver.resolveChain's fold loop ...
}
```

`recomputeMerge`'s call site gains the third argument: `proxy.previewMerge(overrideEditor.getValue(),
JSON.stringify(baseChainRows), explicitlyStandalone, function (t) { ... });` (the top-level `explicitlyStandalone`
JS variable this section introduces, kept in sync by `onExplicitlyStandaloneChange` above).

**Seeding the checkbox's initial state on page load — `EnvConfigSetPage` gains one accessor, mirroring
`getBaseChainSeedJsonForScript`'s existing pattern exactly:**

```java
/** FR-89: seeds the explicitlyStandalone checkbox from the current ACTIVE version's own flag — false
 * for a brand-new Config Set (no active version) or any version that never set it (FR-88). */
public boolean isExplicitlyStandaloneSeed() {
    ConfigSetVersion active = getActiveVersion();
    return active != null && active.isExplicitlyStandalone();
}
```

```js
var explicitlyStandalone = ${it.explicitlyStandaloneSeed}; // Jelly boolean interpolates as a bare
                                                             // true/false token — no JSON.parse needed,
                                                             // unlike the other seed values on this page.
document.getElementById('explicitlyStandaloneCheckbox').checked = explicitlyStandalone; // set once, on load
```

**`getBaseChainSeedJsonForScript`/`effectiveBaseChainForTemplate` — second real gap found in the same area,
confirmed by direct code read:** `EnvConfigSetPage.effectiveBaseChainForTemplate()` (used by `computeTemplate`,
`getTemplateContentType`, `getActiveVersionForTemplate`, AND `getBaseChainSeedJsonForScript`) does **not**
check `isExplicitlyStandalone()` at all — it unconditionally applies the FR-52 synthesized-default fallback
the moment `active.getBaseChain()` is empty, exactly the bug `StepSupport.effectiveBaseChain` was already
fixed for on the pipeline side. Two concrete, currently-live consequences of this gap: (1) **Generate
Template** (FR-15b) on an `explicitlyStandalone=true` env Config Set would silently template the WRONG
effective configuration (env content alone ⊕ a spuriously-synthesized self-reference, instead of env content
alone per FR-87); (2) **re-opening an already-saved standalone version's edit page** would incorrectly re-seed
the base-chain editor with one default row instead of zero, silently losing the "standalone" signal on next
save unless the operator notices and re-checks the box. **Fix — mirror `StepSupport.effectiveBaseChain`'s
exact guard, placed first:**

```java
private List<BaseConfigReference> effectiveBaseChainForTemplate() {
    ConfigSetVersion active = getActiveVersion();
    if (active != null) {
        if (active.isExplicitlyStandalone()) {
            // FR-87, mirrored from StepSupport.effectiveBaseChain — checked first, same precedence
            // reasoning as that method's own javadoc (a version cannot simultaneously be
            // explicitly-standalone and carry a non-empty baseChain, enforced at write time).
            return Collections.emptyList();
        }
        List<BaseConfigReference> declared = active.getBaseChain();
        if (declared != null && !declared.isEmpty()) {
            return declared;
        }
    }
    return Collections.singletonList(BaseConfigReference.active(projectKey));
}
```

This one-line reorder-and-guard fixes `getBaseChainSeedJsonForScript()`, `computeTemplate()`,
`getTemplateContentType()`, and `getActiveVersionForTemplate()` all at once, since all four already funnel
through this single private method — no other call site needs its own separate fix.

</decision-5-explicitlystandalone-surfacing>

<decision-6-discard-all-changes>

## 6. "Discard all changes" (FR-74) — pure client-side revert, no new server endpoint

**Confirmed: no server round trip is needed at all.** Every value FR-74 must revert is already present,
client-side, at page-load time or from the last successful save — `__overrideSeed`/`lastEditContent` (the
saved version's content) and `__baseChainSeed`/the seed computed in §5 above (the saved version's
`baseChain`/`explicitlyStandalone`). "Discard" is therefore just "re-run the page's own initial-seed logic
against the CURRENT in-memory seed variables, without a page reload":

```html
<!-- Sits alongside Save/Save & Activate, in the existing .ctsync-btn-row block at the bottom of the
     Editor details section (FR-74: "presented alongside Save/Save & Activate"). -->
<button type="button" id="discardAllBtn" class="jenkins-button jenkins-button--destructive"
        title="${%discardAll.title}" onclick="discardAllChangesClicked();">${%discardAll.label}</button>
```

```js
function discardAllChangesClicked() {
  // FR-74: destructive to unsaved work — confirm first. Plain window.confirm is consistent with this
  // codebase's existing convention for other destructive-but-infrequent actions (e.g. removeSecret's
  // button already uses jenkins-button--destructive styling with no separate confirm dialog today, but
  // that action is NOT undoable server-side the way this one just resets in-memory drafts — an
  // in-browser confirm is the right-weight guard here specifically because there is real typed content
  // at stake, matching FR-74's own explicit "after a confirmation step" wording).
  if (!window.confirm('Discard all unsaved changes to the base chain and env override? This cannot be undone.')) {
    return;
  }

  // 1. Revert the base-chain editor's draft rows to the last-SAVED state (re-parse the seed the page
  //    was originally rendered with — NOT __baseChainSeed's ORIGINAL page-load value if a save has
  //    happened since without a reload; see the note below this block for why a fresh source is needed).
  baseChainRows = JSON.parse(lastSavedBaseChainJson);
  baseChainRowExpanded = baseChainRows.map(function () { return false; });
  explicitlyStandalone = lastSavedExplicitlyStandalone;
  document.getElementById('explicitlyStandaloneCheckbox').checked = explicitlyStandalone;
  document.getElementById('addBaseChainRowBtn').disabled = explicitlyStandalone;

  // 2. Revert the Env-override editor's draft content to the last-saved content, in place — same
  //    editor-recreation path switchToEditMode() already uses for the Compare-mode-exit case, since
  //    Monaco's setValue() alone does not reset undo history / does not exit an active diff-editor mode.
  lastEditContent = lastSavedOverrideContent;
  switchToEditMode(); // already handles: dispose diffEditor if present, recreate overrideEditor from
                       // lastEditContent, re-wire diagnostics/change-listener, recomputeMerge(),
                       // clear history-row selection, reset the mode-toggle button/compare banner —
                       // EVERY one of those side effects is exactly what "discard" needs too, so this
                       // is a genuine reuse, not a coincidental shortcut.
}
```

**Why three new "last-saved" variables (`lastSavedBaseChainJson`, `lastSavedExplicitlyStandalone`,
`lastSavedOverrideContent`) rather than reusing the page-load `__overrideSeed`/`__baseChainSeed` constants
directly — this is the one genuinely new piece of client state this feature needs:** the existing seed
variables are set ONCE, from the page's initial server render, and never updated afterward. But `jsSave`'s
AJAX response already changes the version-history table in place WITHOUT a page reload (the
2026-09-02 in-place-update pass) — so after a successful in-page save, the page's "currently-loaded saved
version" has moved on, but `__overrideSeed`/`__baseChainSeed` still hold the ORIGINAL page-load values.
"Discard all changes" reverting to those stale values instead of the just-saved version would be a visible
regression (discard would undo a successful save, not just in-progress unsaved edits) — violating FR-74's own
"revert... to the currently-loaded saved version's content" wording. **Fix: `saveClicked`'s success branch
updates all three tracking variables**, so "last saved" always means the most recent successful save, not the
original page load:

```js
function saveClicked(activate) {
  var content = (editMode && overrideEditor) ? overrideEditor.getValue() : lastEditContent;
  // ... unchanged payload construction, including baseChainJson/explicitlyStandalone from §1/§5 ...
  proxy.save(JSON.stringify(payload), function (t) {
    // ... unchanged busy-state re-enable / r.ok error branch ...
    // NEW — refresh the discard-target baseline on every successful save:
    lastSavedOverrideContent = content;
    lastSavedBaseChainJson = JSON.stringify(baseChainRows);
    lastSavedExplicitlyStandalone = explicitlyStandalone;
    // ... unchanged renderVersionHistoryRows/showSaveBanner/noteField-clear ...
  });
}
```

`lastSavedOverrideContent`/`lastSavedBaseChainJson`/`lastSavedExplicitlyStandalone` are initialized once,
at script load, from the existing page-render seeds (`__overrideSeed`, `__baseChainSeed`,
`${it.explicitlyStandaloneSeed}` from §5) — identical values to today's `lastEditContent`/`baseChainRows`
initial assignment, just captured under a separate name so `discardAllChangesClicked` has an
independently-tracked "last known-good" baseline distinct from the live, currently-being-edited state.

**Does NOT alter any already-saved version, version-history table, or active pointer (FR-74's explicit
"MUST NOT" clause) — confirmed by construction:** nothing in `discardAllChangesClicked` calls `proxy.save`,
`proxy.activate`, or any other server endpoint; it is purely a client-side reassignment of in-memory
JavaScript state followed by a UI re-render, so there is structurally no path by which it could touch
persisted data.

</decision-6-discard-all-changes>

<decision-7-versionhistory-explicitlystandalone-display>

## 7. Version-history display of `explicitlyStandalone` (FR-89, second half) — `versionsAsJsonArray` + row template

**`ConfigSetPage.versionsAsJsonArray()` gains one field per row** (the same method §1's `saveImpl` change
flows through — `jsSave`'s response and `activateImpl`'s response both already reuse this one method, so
one change covers both):

```java
row.put("explicitlyStandalone", v.isExplicitlyStandalone()); // NEW
```

The server-rendered initial table (`EnvConfigSetPage/index.jelly`'s `<j:forEach var="v" items="${it.versions}">`
loop) and the client-side `buildHistoryRowElement`/`historyRowTemplate` both need the same distinguishing
label added to the existing base-chain `<td>` cell — **placement decision: append a badge/note to the
EXISTING `[▸ N bases]` cell, rather than adding a new column**, since this is exactly the same cell FR-58
already uses to show the frozen chain, and the distinction FR-89 asks for ("zero bases, on purpose" vs.
"chain resolves to one/zero entries via FR-52's default") is a refinement of that SAME piece of information,
not a separate fact about the version:

```xml
<!-- Server-rendered initial table -->
<td data-th="${%column.baseChain}">
  <j:choose>
    <j:when test="${v.explicitlyStandalone}">
      <span class="ctsync-standalone-badge" title="Declared zero base configs on purpose (FR-86/87)">
        &#9873; Standalone
      </span>
    </j:when>
    <j:otherwise>
      <!-- existing &#9656; N bases toggle link + detail div, UNCHANGED -->
    </j:otherwise>
  </j:choose>
</td>
```

```js
// buildHistoryRowElement, after the existing chain/detail block:
var standaloneBadge = tr.querySelector('.js-standalone-badge'); // NEW element in historyRowTemplate
if (v.explicitlyStandalone) {
  standaloneBadge.style.display = 'inline';
  tr.querySelector('.js-basechain-toggle').style.display = 'none'; // hide the now-inapplicable "N bases" link
} else {
  standaloneBadge.style.display = 'none';
  tr.querySelector('.js-basechain-toggle').style.display = 'inline';
}
```

`historyRowTemplate` gains one new `<span class="js-standalone-badge" style="display:none">&#9873; Standalone</span>`
element inside the same `<td data-th="${%column.baseChain}">` cell, alongside the existing
`.js-basechain-toggle`/`.js-basechain-detail` elements — hidden by default, toggled per-row by the code above.

**Distinguishing "declared zero bases, on purpose" from "chain happens to resolve to one self-referencing
entry" (FR-89's own literal wording) — confirmed unambiguous with this design:** a version with
`explicitlyStandalone=true` always has an empty `baseChain` (enforced at write time by `ConfigSet.addVersion`,
§1), so it would otherwise render as `▸ 0 bases` — visually indistinguishable from a version whose declared
chain is genuinely empty-but-not-standalone (which, per FR-52, is not actually a real, reachable state for
any version created through this UI going forward, since §5's `onExplicitlyStandaloneChange` always reseeds
the FR-56 default the moment the checkbox is unchecked with zero rows — but IS reachable for data written
via Script Console or a future non-UI caller). Showing the "⚑ Standalone" badge INSTEAD OF the
`▸ 0 bases`/`▸ 1 bases` link (not alongside it) makes the two states visually distinct at a glance, satisfying
FR-89's requirement without adding a second, separate UI element an operator would have to learn to
cross-reference.

</decision-7-versionhistory-explicitlystandalone-display>

<decision-8-risks-and-gaps>

## 8. Risks / gaps found during this pass, restated together for visibility

1. **§1 (blocking, must-fix-first):** the UI Save path cannot persist `explicitlyStandalone=true` today —
   `saveImpl` calls the wrong `addVersion` overload. Without this fix, every other piece of this contract
   (the checkbox, the badge, the live-preview guard) would appear to work in the browser right up until the
   operator clicks Save, at which point the flag silently reverts to `false` with no error surfaced anywhere.
   This MUST be implemented and covered by a `JenkinsRule` test asserting `isExplicitlyStandalone()` round-
   trips through a real save before any of the other sections are considered done.
2. **§5 (blocking for FR-87's UI-side correctness):** `EnvConfigSetPage.effectiveBaseChainForTemplate()`
   ignores `isExplicitlyStandalone()` — affects Generate Template output AND the base-chain editor's seed on
   page reopen for an already-standalone version. Confirmed live in the currently-pushed code, not
   hypothetical.
3. **§5 (blocking for FR-87's live-preview correctness):** `previewMergeImpl` unconditionally applies FR-52's
   default to an empty draft chain, with no way to represent "explicitly standalone" while mid-edit. Without
   this fix, the new checkbox from §5 would visibly disagree with what the Merged-bases/Merged-result panels
   show while it's checked (panels would still show the FR-52 self-reference fold, not "no bases at all").
4. **FR-89 has no wireframe (process gap, not a code bug)** — flagged in this document's header; §5/§7's
   placement decisions are this document's own best-guess design, not a `ux-ui-designer`-approved one.
   Recommend a follow-up `ux-ui-designer` pass to confirm/refine before or shortly after implementation,
   same SDLC convention this repo already uses for other FRs whose wireframe lagged their requirements text.
5. **Docs-location housekeeping (process, not scope):** the wireframe this contract is grounded against
   lives outside this repo (`D:\Repos\.copilot-knowledge\spaces\config-template-sync\docs\design\
   wireframe-layout.md`); recommend copying it into `docs/design/wireframe-layout.md` in this repo as part of
   landing this feature, per this owner's existing "personal-repo docs live in the repo" rule already applied
   to `docs/business/requirements.md`.
6. **Not a gap, confirmed correct — stated for completeness:** FR-79–FR-103's pipeline-side `explicitlyStandalone`
   handling (`StepSupport.effectiveBaseChain`, `resolveEffective`'s content-type fallback) is ALREADY correctly
   implemented, matching the prior tech-lead contract exactly — this document's §5 fixes are the UI-side
   counterpart of a fix that already shipped correctly on the pipeline-step side, not a duplicate discovery of
   the same bug in two places.

</decision-8-risks-and-gaps>
