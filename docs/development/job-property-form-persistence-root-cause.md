# Root cause: `ConfigTemplatesJobProperty` not persisted via the real `/configure` form (FR-74)

**Status: diagnosed, NOT fixed.** This note is input for the next implementation pass
(`jenkins-plugin-developer`) — no production code was changed while producing it.

## Symptom (confirmed live, docker-compose test instance)

1. Open `/job/config-template-sync-e2e/configure`.
2. Fill in the "Config Templates Association" section: `projectKey=test-app`, `environment=dev`.
3. Save.
4. `GET /job/config-template-sync-e2e/config.xml` shows:
   ```xml
   <properties/>
   ```
   — completely empty. No `ConfigTemplatesJobProperty` element at all.
5. Consequence: the job's "Config Templates" sidebar link still redirects to the generic
   `/configTemplates` root list, never `/configTemplates/test-app/dev` (FR-72 can never activate its
   project/env cases for a job configured this way, because FR-74 never actually persists the property
   that FR-72's resolution logic reads).

## Mechanism traced end-to-end (Jenkins core source, current `master`)

1. **`hudson.model.Job#doConfigSubmit`** (`core/src/main/java/hudson/model/Job.java`) reads the whole
   submitted form as `JSONObject json = req.getSubmittedForm()`, then does:
   ```java
   DescribableList<JobProperty<?>, JobPropertyDescriptor> t = new DescribableList<>(NOOP, getAllProperties());
   JSONObject jsonProperties = json.optJSONObject("properties");
   if (jsonProperties != null) {
       t.rebuild(req, jsonProperties, JobPropertyDescriptor.getPropertyDescriptors(Job.this.getClass()));
   } else {
       t.clear();
   }
   ```
   So every `JobProperty` on a job is rebuilt from whatever sub-object the submitted form JSON has under
   the top-level key `"properties"`.

2. **`hudson.util.DescribableList#rebuild(StaplerRequest2, JSONObject, List<? extends Descriptor<T>>)`**
   (`core/src/main/java/hudson/util/DescribableList.java`) then, for each applicable
   `JobPropertyDescriptor d`:
   ```java
   String name = d.getJsonSafeClassName();
   JSONObject o = json.optJSONObject(name);   // json here is the "properties" sub-object
   if (o != null) {
       instance = d.newInstance(req, o);
       // (or reconfigure(req, o) if the previous instance is a ReconfigurableDescribable)
   }
   ```
   **A property is only ever constructed if `o != null`** — i.e. only if the submitted `"properties"`
   JSON object has a nested key exactly equal to the descriptor's JSON-safe class name
   (`io-github-retrokharkov1-configtemplatesync-ui-ConfigTemplatesJobProperty-DescriptorImpl`, or
   similar). If that key is missing, the property is silently skipped — this exactly explains a totally
   empty `<properties/>` (not a property with blank fields — the property is never constructed at all).

3. **Where that key normally comes from:** `core/src/main/resources/hudson/model/Job/configure.jelly`
   renders every applicable `JobPropertyDescriptor`'s own `config.jelly` via:
   ```xml
   <f:descriptorList field="properties" descriptors="${h.getJobPropertyDescriptors(it)}" forceRowSet="true"/>
   ```
   `lib/form/descriptorList.jelly` then wraps **each individual descriptor's config.jelly body** in:
   ```xml
   <f:optionalBlock name="${d.jsonSafeClassName}"
                     title="${attrs.forceRowSet!=null?null:d.displayName}"
                     checked="${instances.get(d)!=null}">
   ```
   Because the job's `configure.jelly` call passes `forceRowSet="true"`, `title` is forced to `null` for
   every property block. `lib/form/optionalBlock.jelly`'s own logic branches on that:
   ```xml
   <j:when test="${attrs.title!=null}"> <!-- renders an actual checkbox gating inclusion --> </j:when>
   <j:otherwise>
     <f:rowSet name="${attrs.name}"> <d:invokeBody /> </f:rowSet>
   </j:otherwise>
   ```
   With `title == null` (the case here), there is **no checkbox at all** — the block unconditionally
   becomes `<f:rowSet name="${d.jsonSafeClassName}">`, and its content (the descriptor's own
   `config.jelly` body) is always submitted, grouped under that name. **This is not an
   unchecked-checkbox bug** — Jenkins core's own job-property list deliberately has no such checkbox
   when `forceRowSet=true`.

4. **The actual defect — this plugin's own `config.jelly`:**
   `src/main/resources/io/github/retrokharkov1/configtemplatesync/ui/ConfigTemplatesJobProperty/config.jelly`:
   ```xml
   <?jelly escape-by-default='true'?>
   <j:jelly xmlns:j="jelly:core" xmlns:f="/lib/form">
     <f:section title="Config Templates Association">      <!-- line 3 -->
       <f:entry title="Project key" field="projectKey">
         <f:textbox />
       </f:entry>
       <f:entry title="Environment (optional)" field="environment">
         <f:textbox />
       </f:entry>
     </f:section>
   </j:jelly>
   ```
   `<f:section>` (`core/src/main/resources/lib/form/section.jelly`) itself renders:
   ```xml
   <f:rowSet name="${attrs.name}">
     <section class="${attrs.title!=null ? 'jenkins-section' : ''}"> ... <d:invokeBody/> ... </section>
   </f:rowSet>
   ```
   — i.e. `f:section` **always introduces its own nested `f:rowSet`**, and this plugin's call
   (`<f:section title="Config Templates Association">`, config.jelly line 3) supplies **no `name`
   attribute at all**, so that inner `f:rowSet`'s `name` is empty/undefined.

   The net structure actually rendered on the page is:
   ```
   <f:rowSet name="io-github-...-ConfigTemplatesJobProperty-DescriptorImpl">   (from descriptorList.jelly)
     <f:rowSet name="">                                                        (from f:section, line 3)
       <f:entry field="projectKey"> ... </f:entry>
       <f:entry field="environment"> ... </f:entry>
     </f:rowSet>
   </f:rowSet>
   ```
   Jenkins' structured-form-submission client-side JS reconstructs nested JSON purely from sibling
   `<tr>`/row-group marker elements in the flat HTML table the config page renders as (real DOM nesting
   isn't available inside an HTML `<table>`), matching each row-group's start/end markers by `name`. An
   inner row-group with no `name` breaks that reconstruction for its own subtree: the `projectKey`/
   `environment` field rows never get correctly attributed one level up as direct children of the outer,
   correctly-named `io-github-...-ConfigTemplatesJobProperty-DescriptorImpl` scope. The practical,
   observed effect is that the outer scope ends up with no usable content, so
   `json.optJSONObject("properties").optJSONObject(descriptorJsonSafeClassName)` is `null` — the exact
   condition `DescribableList.rebuild()` (step 2) treats as "this descriptor was not submitted," so the
   property is skipped and `<properties/>` ends up empty. This matches the observed bug precisely
   (a totally absent property, not a property with blank fields).

5. **`ConfigTemplatesJobProperty.DescriptorImpl` and `@DataBoundConstructor` are NOT the problem:**
   `JobPropertyDescriptor#newInstance(StaplerRequest, JSONObject)`
   (`core/src/main/java/hudson/model/JobPropertyDescriptor.java`) has no special override behavior here
   that would explain the empty result — it delegates to the default `Descriptor#newInstance`
   (`req.bindJSON(clazz, formData)`, which binds via `@DataBoundConstructor` exactly as documented) once
   it is actually invoked. `DescriptorImpl` in `ConfigTemplatesJobProperty.java` does not override
   `newInstance` at all, and does not need to — **the defect is upstream of `newInstance` ever being
   called**, at the JSON-key-not-found gate in `DescribableList.rebuild()` (step 2), because of the
   config.jelly structural error (step 4). No `@Symbol` annotation issue either — that only affects
   Pipeline/JCasC DSL discoverability, unrelated to a `/configure` HTML form submission.

## Why the existing tests didn't catch this

`ConfigTemplatesJobActionAssociationTest` and `ConfigTemplatesJobActionTest`
(`src/test/java/.../ui/`) only ever construct `ConfigTemplatesJobProperty` directly in Java
(`new ConfigTemplatesJobProperty(...)` + `project.addProperty(...)`) or read a property injected that
way. None of them submit the real HTML `/configure` form, so none of them exercise the
`f:descriptorList`/`f:section` double-`rowSet` nesting at all — they pass regardless of whether
`config.jelly` can actually be saved by a real user.

## Reproduction test (committed, currently failing — do not fix production code to make it pass here)

`src/test/java/io/github/retrokharkov1/configtemplatesync/ui/ConfigTemplatesJobPropertyFormPersistenceTest.java`
— uses `JenkinsRule.WebClient` to open `/job/<name>/configure`, fill in the real HTML `_.projectKey`/
`_.environment` inputs via `HtmlForm`/`HtmlTextInput`, submit via `jenkins.submit(form)`, reload the job,
and assert `job.getProperty(ConfigTemplatesJobProperty.class)` is non-null with the submitted values.
This test is expected to fail against the current code (property comes back `null`) — it exists as the
red half of the red/green gate for the next fix.

**Note on execution:** this diagnosis session's tool environment had no shell/Maven execution access, so
`mvn -B test -Dtest=ConfigTemplatesJobPropertyFormPersistenceTest` could not actually be run and observed
failing here. The trace above is grounded directly in Jenkins core source (`Job.java`,
`DescribableList.java`, `descriptorList.jelly`, `optionalBlock.jelly`, `section.jelly`, all fetched from
`jenkinsci/jenkins@master`) plus a line-by-line read of this plugin's own `config.jelly`, and the failure
mode it predicts (`getProperty(...)` returns `null`) is the same failure the owner already observed live
(`config.xml` → `<properties/>`). **The next agent must run this test first**, before attempting any
fix, to confirm it is red for the reason described here (and not some other reason) before changing
`config.jelly`/`ConfigTemplatesJobProperty`.

## Suggested direction (for the next agent — NOT implemented here)

Real, working `JobProperty` `config.jelly` examples (Jenkins core docs' own port/host example, and the
`forceRowSet=true` mechanics traced above) never nest an `f:section` inside the descriptor's own
`f:descriptorList`-contributed row-group — they emit `<f:entry>` fields directly. The fix likely is to
drop the `<f:section title="...">` wrapper from
`ConfigTemplatesJobProperty/config.jelly` entirely (keep only the two `<f:entry>` blocks, letting the
outer `f:descriptorList`/`f:rowSet` machinery provide the grouping), or, if a titled sub-grouping is
genuinely wanted, give the `f:section` an explicit non-empty `name` attribute of its own and verify the
resulting JSON nesting matches what `ConfigTemplatesJobProperty`'s `@DataBoundConstructor` parameter
names expect. Either way, the fix must be proven by turning
`ConfigTemplatesJobPropertyFormPersistenceTest` green without weakening its assertions, not by writing a
new, narrower test around whatever the fix happens to produce.
