# config-template-sync — Tech-lead design + task breakdown for job-scoped "Config Templates" (FR-75–FR-78a)

> Scope: designs the concrete code/contract shape for the 2026-09-05-rewritten job-association block
> (FR-75, FR-76, FR-77, FR-77a, FR-78, FR-78a) before implementation, per the mandatory SDLC chain
> (`business-analyst` → `tech-lead` → `jenkins-plugin-developer`). Depth/format matches
> `docs/development/tech-lead-base-chain-ui-redesign-review-2026-09-03.md` and
> `docs/development/tech-lead-pipeline-resolution-controls-review-2026-09-03.md`.
>
> Grounded by reading the actual pushed source — `ui/{ConfigTemplatesJobAction,
> ConfigTemplatesJobActionFactory, ConfigTemplatesJobProperty, ConfigTemplatesRootAction, ProjectConfigPage,
> ConfigSetPage, EnvConfigSetPage}.java`, `ui/ConfigTemplatesJobProperty/config.jelly`,
> `ui/EnvConfigSetPage/index.jelly`, `ui/ProjectConfigPage/index.jelly`, the three existing job-association
> tests, and `docs/development/job-property-form-persistence-root-cause.md` — plus the requirements' own
> verified Jenkins-core facts (`hudson/model/Job/configure.jelly`, `hudson/model/AbstractProject/sidepanel.jelly`)
> and one additional core fact confirmed directly against `jenkins-core-2.568.3`'s own source for this
> document (`JobPropertyDescriptor.isApplicable`/`getPropertyDescriptors`, §3). Author: `tech-lead`,
> 2026-09-05. No code changed by this document — design/breakdown only.

---

## 1. Feature-level classification

Test applied throughout: *does this surface's behavior/content depend on a specific business object's
state (a specific `Job`, a specific `ConfigSet`/environment)?* If yes → CORE. If it is a fixed,
state-independent app-shell entry point → GLOBAL. (No OPTIONAL surfaces in this feature — nothing here is
behind a toggle or an edition boundary.)

- **Job sidebar "Config Templates" action (`ConfigTemplatesJobAction`/`…Factory`) — CORE.** Its label/icon
  are fixed, but its *content* (which of FR-76's three page states renders) depends entirely on the
  specific job's own `ConfigTemplatesJobProperty` state. Confirms the owner's reading.
- **Job Config Templates page, `/job/<name>/configTemplates` (FR-76) — CORE.** Renders one of three states
  keyed on this specific job's association (no property / `projectKey`-only / both set) — textbook
  business-object-state dependency. Confirms the owner's reading.
- **Job-scoped per-environment editor, `/job/<name>/configTemplates/<environment>` (FR-77) — CORE.**
  Resolves against *this job's* current `projectKey` at request time and edits *that project's* specific
  environment Config Set — doubly state-dependent (job's association + the Config Set itself). Confirms the
  owner's reading.
- **`ConfigTemplatesJobProperty` persistence + `doCheckProjectKey` (FR-75) — CORE.** It *is* the per-job
  business-object state the three CORE surfaces above all key off of.
- **Manage Jenkins → Tools "Config Templates" entry (`ConfigTemplatesRootAction` as `ManagementLink`) —
  GLOBAL.** Fixed app-shell navigation tile, identical for every admin regardless of any job's state.
  Confirms the owner's reading.
- **Global `/configTemplates` root list + `ProjectConfigPage`/`CommonConfigSetPage` — GLOBAL.** The root
  list itself enumerates all projects (a catalog view, not keyed to one business object); `ProjectConfigPage`
  is arguably "per-project" but it is reached by an operator picking a project key, not derived from a job's
  state — it stays the canonical, project-scoped entry point independent of any job. Confirms the owner's
  reading; flagged as GLOBAL rather than CORE specifically because FR-77a requires it to remain reachable
  and equally valid with **no** job in the picture at all.
- **`EnvConfigSetPage`/`ConfigSetPage` (the shared editor engine) — not classified as a surface in its own
  right.** It is REUSED, unmodified in its public contract, by both the GLOBAL global env URL and the CORE
  job-scoped env URL (§2 below) — its level is inherited from whichever URL is asking, which is exactly
  the point of FR-77a's "one shared implementation" requirement.

---

## 2. Component/contract design

### 2.1 `ConfigTemplatesJobAction` becomes a page-rendering + nested-dispatch object, not a redirector

Today it holds one `ConfigTemplatesJobProperty` reference and only computes a redirect URL. It changes to:

- **Keep** the constructor shape `ConfigTemplatesJobAction(ConfigTemplatesJobProperty property)` — but now
  also needs the owning `Job` (to write the property back on save, and to resolve environments against the
  *live* property at request time per FR-77's "always resolve against the requesting job's own, current
  association" rule — a `Job` reference, not a cached `ConfigTemplatesJobProperty` snapshot, is what
  guarantees "current"). New constructor: `ConfigTemplatesJobAction(Job<?, ?> job)` — it reads
  `job.getProperty(ConfigTemplatesJobProperty.class)` itself, on every call that needs it, instead of being
  handed a stale reference at factory time. `ConfigTemplatesJobActionFactory.createFor(Job)` changes from
  `new ConfigTemplatesJobAction(property)` to `new ConfigTemplatesJobAction(target)`. The no-property "not
  configured" case is simply `job.getProperty(...) == null` — no behavior change, just moved inside.
- **`getIconFileName()`/`getDisplayName()`/`getUrlName()`** — unchanged (FR-78: label/icon/URL segment stay
  exactly as today).
- **`doIndex(StaplerRequest req, StaplerResponse rsp)` is REPLACED.** FR-78 explicitly supersedes the old
  `sendRedirect2` requirement. New signature renders a Jelly view instead of redirecting:
  ```java
  public HttpResponse doIndex() {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      return null; // Stapler default: render this object's own index.jelly (see note below)
  }
  ```
  In practice `doIndex` need not even exist as a method any more — Stapler's default dispatch for a
  request landing exactly on an object's own URL, when no `doIndex` is present, is "render `<ClassName>/
  index.jelly` for this object" (the exact mechanism `ProjectConfigPage`/`EnvConfigSetPage`/
  `ConfigTemplatesRootAction` already all rely on with **no** `doIndex` method at all). Keeping a thin
  `doIndex()` that only does the permission check (returning `HttpResponses.forwardToView(this, "index")`
  or simply performing the check and returning void so Stapler falls through to `index.jelly`) is worth
  keeping explicit here — unlike those three sibling classes, `ConfigTemplatesJobAction` is reached via
  `TransientActionFactory`, whose factory-level permission gate is a *visibility* convenience only (per the
  class's own existing javadoc), so an explicit `Jenkins.ADMINISTER` re-check belongs on this class itself,
  not implicitly assumed from Stapler's default index dispatch. This mirrors the pattern already used by
  `ConfigTemplatesRootAction#getTarget()` (a permission gate that fires on every request to the object)
  rather than inventing a new one.
- **New Jelly view: `ConfigTemplatesJobAction/index.jelly`** — the FR-76 page. Three page states rendered
  from one template via `<j:choose>` on `job.getProperty(ConfigTemplatesJobProperty.class)` being `null`
  / `projectKey` blank / `projectKey`+`environment` both set — see §2.3 for the save-handler shape and §2.4
  for the exact furniture (breadcrumbs/`f:saveApplyBar`/`f:entry`+`f:textbox`) mandated by FR-76.
- **New Stapler nested dispatch for the trailing `<environment>` segment (FR-77).** Per the requirement's
  own wording ("`getDynamic`-style hook, or an equivalent nested-action object... which exact Stapler API
  accomplishes this is an implementation detail left to the developer") — **decision: `getDynamic(String
  environment)`**, not a nested named getter. Justification: this repo already has two working precedents
  for exactly this shape one level up (`ConfigTemplatesRootAction.getDynamic(String projectKey)` →
  `ProjectConfigPage`, and `ProjectConfigPage.getDynamic(String environment)` → `EnvConfigSetPage`), both
  explicitly chosen over a named getter specifically to dodge Jenkins core's post-SECURITY-595 Stapler
  getter-routing whitelist filter (documented at length in `ConfigTemplatesRootAction`'s own javadoc, and
  empirically confirmed in this codebase already). There is no reason the third level down would behave
  differently under that same filter, so reusing the identical mechanism is the lowest-risk choice and
  keeps all three levels of this URL tree structurally consistent:
  ```java
  public Object getDynamic(String environment) {
      Jenkins.get().checkPermission(Jenkins.ADMINISTER);
      ConfigTemplatesJobProperty property = job.getProperty(ConfigTemplatesJobProperty.class);
      String projectKey = property == null ? null : property.getProjectKey();
      return new JobScopedEnvConfigSetPage(job, projectKey, environment, repository);
  }
  ```
  `projectKey` is read from `job.getProperty(...)` *inside* `getDynamic`, at request time — never cached —
  which is exactly what makes it structurally impossible for a stale bookmarked URL to act against a
  project the job is no longer associated with (FR-77's "always resolve... at request time" rule): if the
  job's `projectKey` has since changed or been cleared, the very next request recomputes it fresh.

### 2.2 `JobScopedEnvConfigSetPage` — a thin job-context wrapper, NOT a second editor implementation

FR-77a requires exactly one shared save/validate path and one persisted entity. The concrete mechanism:

- **New class `JobScopedEnvConfigSetPage`**, same package (`ui`), whose entire body is composition/delegation
  around a real `EnvConfigSetPage` instance — **it never re-implements save/validate/activate/merge-preview
  logic; it does not even subclass `ConfigSetPage`.** Two cases per FR-77:
  - **No `projectKey` on this job (blank/no property):** `JobScopedEnvConfigSetPage` renders its OWN small
    `index.jelly` — the "no association" explanatory state with a link back to
    `/job/<name>/configTemplates` — and never touches `ConfigSetRepository` at all (FR-77: "No Config Set
    read or write happens in this state"). No delegate `EnvConfigSetPage` is even constructed in this case.
  - **`projectKey` present:** constructs `new EnvConfigSetPage(projectKey, environment, repository)` — the
    exact same package-private constructor `ProjectConfigPage.getDynamic` already calls — and delegates
    every accessor/action Jelly needs to that single instance:
    ```java
    public class JobScopedEnvConfigSetPage {
        private final Job<?, ?> job;
        private final String projectKey;   // null => no-association state
        private final String environment;
        private final EnvConfigSetPage delegate; // null when projectKey == null

        JobScopedEnvConfigSetPage(Job<?, ?> job, String projectKey, String environment,
                                   ConfigSetRepository repository) {
            this.job = job;
            this.projectKey = projectKey;
            this.environment = environment;
            this.delegate = projectKey == null ? null : new EnvConfigSetPage(projectKey, environment, repository);
        }

        public boolean hasAssociation() { return projectKey != null; }
        public Job<?, ?> getJob() { return job; }
        public String getJobName() { return job.getFullName(); }
        public EnvConfigSetPage getDelegate() { return delegate; } // Jelly reads everything through this
        public String getCanonicalUrl() {
            return Jenkins.get().getRootUrl() + "configTemplates/" + projectKey + "/" + environment + "/";
        }
    }
    ```
  - **`index.jelly` for the "has association" case does not duplicate `EnvConfigSetPage/index.jelly`'s
    1600+ lines.** It **includes** the existing view via Stapler's cross-object Jelly include, the same
    mechanism Jenkins core itself uses for "render another object's fragment in my context":
    `<st:include page="index.jelly" from="${it.delegate}" optional="false"/>` (Jelly's `<st:include
    from="...">` renders the *named object's* view resource, i.e. literally
    `EnvConfigSetPage/index.jelly`, with `it` inside that included fragment still bound to `${it.delegate}`
    so every `${it.xxx}` expression, `<st:bind var="proxy" value="${it}"/>` JS proxy, and every `doXxx`/
    `@JavaScriptMethod` AJAX call the existing view already makes continues to hit the SAME
    `EnvConfigSetPage` object, unmodified.** This is what guarantees FR-77a mechanically rather than by
    convention: the job-scoped page's Save button posts to `proxy.save(...)` on the very same
    `EnvConfigSetPage`/`ConfigSetPage.jsSave`/`saveImpl` code path the global URL posts to, because it is
    the identical Java object instance for the duration of that one request, backed by the identical
    `ConfigSet`/`ConfigSetVersion` records in `ConfigSetRepository` (there is exactly one `ConfigSetRepository`
    per request, and `ConfigSet.equals`/identity is irrelevant here — both URLs call
    `repository.find(projectKey, ENV, environment)` against the same on-disk/XStream-backed store, so "one
    persisted entity" falls out of both pages sharing that repository lookup key, not from any in-memory
    caching).
  - A thin wrapper `index.jelly` (a few lines, NOT included in the "≥1600 lines" duplication concern) adds
    only the FR-77a-mandated banner ("Showing `<projectKey>`/`<environment>` for job `<jobName>` — view
    canonical page") above the `<st:include>`, plus the job-scoped breadcrumb (`<l:layout title="...">`
    under this job-ancestor URL — falls out automatically per §2.4).
  - **Consequence for FR-77's "does not exist yet" behavior:** since `delegate.isExists()` is the exact
    same `EnvConfigSetPage#isExists()` method (inherited from `ConfigSetPage`) the global page already
    calls, the "does not exist yet" banner + full usable editor behavior is inherited for free — no new
    logic needed, satisfying FR-77's explicit callout of this case.

### 2.3 The FR-76 association form's own save handler — separate from, and much smaller than, `ConfigSetPage.saveImpl`

FR-76's association form (`projectKey`/`environment` fields + Save) is NOT a `JobProperty` `config.jelly`
form any more (FR-75 removes that path entirely) — it is an ordinary `f:form` on `ConfigTemplatesJobAction`'s
own `index.jelly`, posting to a new method on `ConfigTemplatesJobAction` itself:

```java
@RequirePOST
public HttpResponse doSaveAssociation(@QueryParameter String projectKey,
                                       @QueryParameter String environment) throws IOException {
    Jenkins.get().checkPermission(Jenkins.ADMINISTER);
    job.checkPermission(Job.CONFIGURE); // secondary, job-specific gate — see Risks §6
    job.addProperty(new ConfigTemplatesJobProperty(projectKey, environment));
    return HttpResponses.redirectToDot(); // FR-76: reload of this same page shows the saved values
}
```

This is a brand-new, single-purpose write path — `job.addProperty(...)` is the exact same call
`ConfigTemplatesJobActionAssociationTest`/`ConfigTemplatesJobActionTest` already use directly in Java, so
this handler is provably equivalent to "what the tests already exercise," just reachable from a real form
now. `f:saveApplyBar` (FR-76's mandated furniture) posts a normal structured form submit; `doSaveAssociation`
reads the two plain `@QueryParameter`s Stapler binds from that submit — no `DescribableList`/
`f:descriptorList`/`JobPropertyDescriptor.newInstance` involvement anywhere in this path, which is precisely
what sidesteps the entire bug class in `job-property-form-persistence-root-cause.md` (that bug lives
entirely inside `Job#doConfigSubmit`'s `"properties"` JSON sub-object handling — a path this new handler
never goes near). The one-click "associate this job with `<env>`" action FR-76 also describes (from the
project-only state's env list) is a second, tiny variant of the same handler:
`doAssociateEnvironment(@QueryParameter String environment)` — reads the job's current `projectKey` off its
existing property and calls the identical `job.addProperty(new ConfigTemplatesJobProperty(projectKey,
environment))`.

**`ConfigTemplatesJobProperty` itself keeps its `@DataBoundConstructor`, `getProjectKey()`/`getEnvironment()`,
and `DescriptorImpl.doCheckProjectKey`** exactly as-is (FR-75's acceptance criteria: it must still round-trip
via `new ConfigTemplatesJobProperty(...)` + `project.addProperty(...)`, and `doCheckProjectKey` still needs to
back the FR-76 form's live `.warning`-only field validation via `f:entry`'s `checkUrl` convention). Only
`config.jelly` and the descriptor's applicability change (§3).

### 2.4 Furniture: breadcrumbs, `f:saveApplyBar`, `f:entry`/`f:textbox` — no new mechanism needed

All three FR-76/77 furniture requirements are, as the requirements doc's own verified-facts section already
states, routine consequences of the URL being job-scoped and using standard core tags — nothing this design
needs to build:
- **Breadcrumbs:** `<l:layout title="...">` walking Stapler's ancestor chain automatically appends
  `... › <job> › Config Templates` (and one level deeper, `› <environment>`) because
  `ConfigTemplatesJobAction`'s own ancestor in the URL *is* the `Job` object — no explicit breadcrumb code,
  confirmed by this codebase's own `EnvConfigSetPage/index.jelly` already using bare `<l:layout
  title="${it.displayName}">` for its own (non-job) breadcrumb today.
- **`f:saveApplyBar`:** used directly on `ConfigTemplatesJobAction/index.jelly`'s association `<f:form>`,
  exactly as FR-76 mandates — this is a plain core tag, not something wired to `Job#doConfigSubmit`
  specifically; it simply renders Save/Apply buttons for whatever `<f:form>` wraps it, so pointing that
  form's `action` at `doSaveAssociation` "just works."
- **`f:entry`/`f:textbox`:** the association form's two fields are plain `<f:entry title="Project key"
  field="projectKey"><f:textbox/></f:entry>`-style rows, identical in shape to the fields
  `ConfigTemplatesJobProperty/config.jelly` already has today (only their *page*, not their markup shape,
  changes) and to `ProjectConfigPage/index.jelly`'s own `.ctsync-inline-form` convention FR-76 cites.

---

## 3. How `ConfigTemplatesJobProperty` stops being returned by `h.getJobPropertyDescriptors(it)`

**Verified mechanism (confirmed by reading `hudson.model.JobPropertyDescriptor` source at the
`jenkins-2.568.3` tag directly, not assumed):**

```java
// hudson.model.JobPropertyDescriptor
public boolean isApplicable(Class<? extends Job> jobType) { ... default: true, inferred from generics ... }

public static List<JobPropertyDescriptor> getPropertyDescriptors(Class<? extends Job> clazz) {
    List<JobPropertyDescriptor> r = new ArrayList<>();
    for (JobPropertyDescriptor p : all())
        if (p.isApplicable(clazz)) r.add(p);
    return r;
}
```

`h.getJobPropertyDescriptors(it)` (the Jelly helper `Job/configure.jelly` calls) is built directly on top of
this `getPropertyDescriptors(Class)` — it is **literally the `isApplicable` filter**, nothing more
sophisticated. **The fix: override `isApplicable` on `ConfigTemplatesJobProperty.DescriptorImpl` to always
return `false`:**

```java
@Extension
public static class DescriptorImpl extends JobPropertyDescriptor {
    @Override
    public boolean isApplicable(Class<? extends Job> jobType) {
        return false; // FR-75: never surfaced by Job/configure.jelly's f:descriptorList again
    }
    // getDisplayName(), doCheckProjectKey(...) unchanged
}
```

Why this precisely satisfies FR-75 and nothing else breaks:
- `getPropertyDescriptors`/`h.getJobPropertyDescriptors` is the ONLY consumer of `isApplicable` that matters
  here — it is exactly what `Job/configure.jelly`'s `<f:descriptorList field="properties"
  descriptors="${h.getJobPropertyDescriptors(it)}"/>` iterates, so returning `false` removes this descriptor
  from that list unconditionally, for every job type, on every render — no more row-group on
  `/job/<name>/configure`, ever.
- The `@Extension` annotation stays (the descriptor remains a real, discoverable
  `JobPropertyDescriptor` extension — `isApplicable` gates *where it is offered for editing*, not whether it
  exists as an extension point).
- **Persistence is completely unaffected**, because it never went through this descriptor in the first
  place for the paths that matter: `project.addProperty(new ConfigTemplatesJobProperty(...))` (used by
  §2.3's new save handlers and by the existing direct-construction tests) calls straight into
  `Job#addProperty`/`DescribableList#add`, with zero descriptor involvement; XStream (de)serialization of
  `config.xml` resolves purely by the property's own Java class name, not through `JobPropertyDescriptor`
  lookup at all. `job.getProperty(ConfigTemplatesJobProperty.class)` (used everywhere this design reads the
  association) is a plain typed lookup into the job's already-deserialized property list — also
  descriptor-independent. This is exactly the "keeping only the plain persistence class" alternative the
  requirements doc names, except it is even less invasive: the descriptor is kept intact (so
  `doCheckProjectKey` still backs the FR-76 form's live validation, §2.4), only its one `isApplicable`
  method changes.
- `config.jelly` next to `ConfigTemplatesJobProperty.DescriptorImpl` is **deleted** (not repurposed) — its
  two fields move verbatim into `ConfigTemplatesJobAction/index.jelly`'s own `<f:form>` (§2.3), which is a
  bespoke Jelly view, not a `Describable`-driven `config.jelly` fragment at all, so there is no remaining
  reason for a `config.jelly` resource to exist under this descriptor's resource path.

---

## 4. Ordered, atomic task breakdown

Each task leaves `mvn -q -DskipTests=false test` (or the relevant subset) green as its own commit. File
sets are called out so independence/parallelism is obvious at a glance.

| # | Task | Files touched | Depends on | Parallelizable with |
|---|------|----------------|------------|----------------------|
| **T1** | `ConfigTemplatesJobProperty.DescriptorImpl.isApplicable` → always `false`; delete `ConfigTemplatesJobProperty/config.jelly`; update class javadoc (no longer "renders on `/configure`"). Update/replace `ConfigTemplatesJobPropertyFormPersistenceTest` per §5 (it targets a form that no longer exists). | `ui/ConfigTemplatesJobProperty.java`, `ui/ConfigTemplatesJobProperty/config.jelly` (delete), `ui/ConfigTemplatesJobPropertyFormPersistenceTest.java` | none | T2 |
| **T2** | `EnvConfigSetPage`'s constructor visibility/shape confirmed sufficient for reuse (it already is — package-private, same package) — **no code change**, but add a short javadoc note on `EnvConfigSetPage` cross-referencing the new job-scoped caller in T5, so a future reader sees both call sites from one place. Trivial/no-op if the reviewer judges the note unnecessary; kept as its own task only so it never blocks T3/T4. | `ui/EnvConfigSetPage.java` (javadoc only) | none | T1, T3, T4 |
| **T3** | `ConfigTemplatesJobAction` rewritten: constructor takes `Job<?, ?>` instead of a cached `ConfigTemplatesJobProperty`; `doIndex`/redirect logic removed; add `doSaveAssociation`/`doAssociateEnvironment`; `ConfigTemplatesJobActionFactory` updated to pass `target` instead of a pre-fetched property. **Does not yet add `getDynamic`** (that is T5, kept separate since it introduces a whole new class). Update `ConfigTemplatesJobActionTest`/`ConfigTemplatesJobActionAssociationTest` per §5 (their redirect-URL assertions no longer apply). | `ui/ConfigTemplatesJobAction.java`, `ui/ConfigTemplatesJobActionFactory.java`, `ui/ConfigTemplatesJobActionTest.java`, `ui/ConfigTemplatesJobActionAssociationTest.java` | none | T1, T2, T4 |
| **T4** | New `ConfigTemplatesJobAction/index.jelly` — the FR-76 three-state page (no-association / project-only / both-set), association `<f:form>` with `f:saveApplyBar`, env list + "New Env Config Set" input mirroring `ProjectConfigPage/index.jelly`. Depends on T3's Java surface existing (the Jelly reads `${it.job}`, calls `doSaveAssociation` etc.) but is a pure-Jelly file so can be drafted in parallel and only needs T3 merged before it can actually render. | `ui/ConfigTemplatesJobAction/index.jelly` (new) | T3 (Java contract) | T1, T2 |
| **T5** | New `JobScopedEnvConfigSetPage` class + `ConfigTemplatesJobAction.getDynamic(String)`; new `JobScopedEnvConfigSetPage/index.jelly` (banner + `<st:include from="${it.delegate}">` + no-association state). This is FR-77/FR-77a's core delivery. | `ui/JobScopedEnvConfigSetPage.java` (new), `ui/JobScopedEnvConfigSetPage/index.jelly` (new), `ui/ConfigTemplatesJobAction.java` (add `getDynamic` only — small, additive diff on top of T3) | T3 | T1, T2, T4 (T4 and T5 touch disjoint Jelly/Java surfaces once T3 has landed) |
| **T6** | New `JenkinsRule` tests: FR-76's three page states (`GET /job/<name>/configTemplates` returns 200 not 3xx, each state's actionable content present, non-admin gets permission failure), association save round-trip via the real form (`doSaveAssociation`) verified through `config.xml`. Replaces the old redirect-shape assertions removed from T3. | new `ui/ConfigTemplatesJobPageTest.java` | T3, T4 | T7 (disjoint test file) |
| **T7** | New `JenkinsRule` tests: FR-77 (`GET /job/<name>/configTemplates/<environment>` — matching association renders full editor, no-projectKey renders explanatory state, never-saved env renders "does not exist yet", non-admin permission failure, URL never redirects into `/configTemplates/...`) and FR-77a (save via job-scoped URL, read back via global URL and vice versa; identical validation error on both URLs for the same bad input). | new `ui/JobScopedEnvConfigSetPageTest.java` | T5 | T6 (disjoint test file) |
| **T8** | Docs: update `ConfigTemplatesJobProperty`/`ConfigTemplatesJobAction` package-level or class javadocs that still describe the old redirect/`config.jelly` behavior (there are several — see class javadocs quoted in §2.1/§2.3); no behavior change, comment-only cleanup pass once T1–T7 have landed, so stale comments don't linger. | javadoc-only touches across `ui/ConfigTemplatesJobAction.java`, `ui/ConfigTemplatesJobActionFactory.java`, `ui/ConfigTemplatesJobProperty.java` | T1–T7 | none (last, low-risk, quick) |

**Sequencing summary:** T1 and T2 are fully independent of everything and of each other — assign first,
in parallel. T3 is the pivotal, must-land-first task for the rest of the job-action rewrite: T4 and T5 both
need T3's new Java surface merged before they can compile/render, but T4 and T5 themselves touch disjoint
files (`ConfigTemplatesJobAction/index.jelly` vs. the new `JobScopedEnvConfigSetPage*` files, plus one
small additive `getDynamic` method on `ConfigTemplatesJobAction.java` that does not conflict with T4's
Jelly-only diff) and can run in parallel once T3 is merged. T6 depends on T3+T4; T7 depends on T5; T6 and
T7 are disjoint test files and can run in parallel once their respective prerequisites land. T8 is last,
depends on everything, and is low-risk/quick (one agent, sequential, no real parallelism benefit).

---

## 5. Test plan

**Retarget (rewrite in place, do not delete outright — same file, changed assertions):**
- `ConfigTemplatesJobActionTest` — `jobAction_isContributedToEveryJobAndMatchesTheManagementLinkEntry` and
  `jobUrlName` assertions stay valid (label/icon/URL segment unchanged, FR-78). The
  `jobPage_rendersTheConfigTemplatesSidebarLink` test also stays valid unchanged (still just checks the
  sidebar text on the job's own page). No redirect-specific assertions exist in this file today, so it
  needs no structural rewrite — only the `ConfigTemplatesJobAction` constructor call sites inside it (if
  any construct the class directly) updated to the new `Job`-based constructor.
- `ConfigTemplatesJobActionAssociationTest` — every `assertRedirectsTo(...)` test
  (`jobWithNoPropertyConfigured_redirectsToTheGenericRootList`,
  `jobWithBlankProjectKey_redirectsToTheGenericRootList`,
  `jobWithProjectKeyOnly_redirectsToTheProjectOverview_notTheCommonPage`,
  `jobWithProjectKeyAndEnvironment_redirectsToTheEnvPage`) is **retargeted, not deleted**: FR-78's own
  acceptance criteria demand the exact opposite of what these currently assert (`GET
  /job/<name>/configTemplates` must never return an HTTP 3xx). Rewrite each to assert the corresponding
  FR-76 PAGE STATE instead of a redirect target: e.g. `jobWithNoPropertyConfigured_rendersTheNoAssociationState`
  asserts HTTP 200 + page text explaining no association + an editable `projectKey` field present;
  `jobWithProjectKeyOnly_rendersTheProjectOnlyState_withEnvListAndNoCommonLink` asserts the project-only
  state's env list renders and does NOT link to the common Config Set page (mirrors the old test's own
  "not the common page" guarantee, now as a content assertion rather than a redirect-target assertion).
  This is the direct, equivalent-guarantee replacement FR-78a's migration note calls for.
- `ConfigTemplatesJobPropertyFormPersistenceTest` — **this is the one the deliverable explicitly calls out:
  it drives the `/configure` form path that FR-75 removes, so it must be replaced by an equivalent
  guarantee, not dropped.** Its replacement is a new test asserting the SAME end state
  (`job.getProperty(ConfigTemplatesJobProperty.class)` non-null with the submitted values, verified via
  `GET /job/<name>/config.xml`) but driven through the NEW real form: open
  `/job/<name>/configTemplates`, fill the association `<f:form>`'s `projectKey`/`environment` inputs, submit
  via `jenkins.submit(form)`, then assert exactly what the old test asserted. Two reasonable homes: (a)
  rename/rewrite this file in place to describe the new form's path (keeps the file's git history attached
  to the same guarantee), or (b) fold it into new `ConfigTemplatesJobPageTest` (T6) as one of that class's
  save-round-trip tests. **Decision: (a), rename in place** — this test's whole reason for existing is "prove
  a REAL HTML form round-trip persists the property" as opposed to the other tests' direct-Java construction;
  that framing is still exactly right for the new form, it is only the URL/form-name that changes
  (`configTemplates` page's own form, not `configure`'s `config` form) — renaming preserves that intent
  most clearly rather than scattering it into a broader page-test class. The file's javadoc must be rewritten
  end-to-end (its current text specifically documents the OLD bug and explicitly says "do NOT fix
  config.jelly... as part of resolving this test" — all now obsolete framing that would mislead a future
  reader if left in place).

**Kept unchanged:**
- `ConfigSetPageValidateSyntaxTest`, `MessagesI18nTest` — untouched, no relationship to this feature.
- `ConfigTemplatesUiTest` — the large existing suite covering the GLOBAL `/configTemplates/...` pages'
  editor behavior stays exactly as-is; per §2.2, the job-scoped page delegates to the identical
  `EnvConfigSetPage` those tests already exercise, so no duplicate editor-behavior coverage is needed there
  — FR-77a's "identical semantics" guarantee is a structural fact (same Java object), not something each
  individual editor behavior needs re-testing per URL.

**New tests needed (beyond T6/T7's outline in §4):**
- FR-75: a direct-construction test that `DescriptorImpl.isApplicable(FreeStyleProject.class)` (or any
  `Job` subtype) returns `false`, AND that `h.getJobPropertyDescriptors(job)`-equivalent
  (`JobPropertyDescriptor.getPropertyDescriptors(job.getClass())`) no longer contains this descriptor — the
  precise mechanism claimed in §3, not just its externally-observable consequence. Also keep/port forward
  the existing `doCheckProjectKey` `.warning`-never-`.error` unit-level assertion (already exists somewhere
  in the current suite per FR-75's acceptance text — confirm it survives whichever file it lives in
  unmodified, since FR-75's contract for that method is unchanged).
- FR-77a's two-URL identity: beyond T7's outline, one test specifically proving "exactly ONE version
  history" — save twice via the job-scoped URL, once via the global URL, and assert the global page's
  version-history table shows all three versions in one continuous sequence (not two divergent histories of
  1-and-2), which is the sharpest possible proof that both URLs write through the same `ConfigSet` record.

---

## 6. Risks / open questions for the owner

1. **`doSaveAssociation`'s permission model — `Jenkins.ADMINISTER` alone, or also `Job.CONFIGURE`?**
   FR-76 states the page is gated on `Jenkins.ADMINISTER` throughout and says nothing about a
   per-job `Job.CONFIGURE` check. §2.3's sketch adds a `job.checkPermission(Job.CONFIGURE)` call as a
   defensive secondary gate (an admin editing a job they can't otherwise configure would be unusual, but
   folder-scoped ADMINISTER grants exist in some setups). This is an implementation-detail addition beyond
   what any FR mandates — flagging it now so the owner can confirm "ADMINISTER alone is sufficient, drop the
   second check" or "keep it" before `jenkins-plugin-developer` builds it, rather than the implementer
   guessing silently.
2. **`getDynamic` vs. an alternative nested-action object for FR-77:** §2.1 picks `getDynamic(String)` on
   the strength of this repo's own two existing precedents at the levels above. The requirements doc
   explicitly leaves this open ("which exact Stapler API accomplishes this is an implementation detail...
   not mandated here"), so this is a design decision, not a requirements gap — flagged only so the owner
   has visibility into which option was chosen and why, not because a decision is still needed from them.
3. **`JobScopedEnvConfigSetPage`'s `<st:include from="...">` cross-object include is the one genuinely novel
   Jelly mechanism in this design** — every other piece in this document reuses a pattern already proven
   elsewhere in this codebase, but no existing view in this plugin currently does a cross-object `<st:include
   from="${it.delegate}">`. It is a standard, well-documented Stapler/Jelly mechanism (not a workaround), but
   `jenkins-plugin-developer` should do one quick live check early in T5 (render the job-scoped env page,
   confirm the included fragment's `${it}`/`st:bind`/AJAX calls all resolve against the delegate object as
   expected, before writing the rest of T5's tests around it) — called out explicitly so that check happens
   deliberately rather than being discovered mid-implementation.
4. **No open requirements questions remain** — FR-75–FR-78a were rewritten and re-verified against live
   Jenkins-core behavior by the owner/business-analyst pass immediately preceding this document; this
   design found no gaps in that content, only the three implementation-level notes above.
