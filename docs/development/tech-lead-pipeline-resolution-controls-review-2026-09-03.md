# config-template-sync — Tech-lead contract review of pipeline-level resolution overrides, standalone env Config Sets, and `setupConfigTemplate` (FR-79–FR-95, OQ-11)

> Scope: finalizes the exact class/method contract for FR-79–FR-95 before `jenkins-plugin-developer`
> implements it, per the mandatory SDLC chain (`business-analyst` → `tech-lead` → implementer). No
> wireframe/UI dependency for FR-79–FR-95 (pipeline-step-only); FR-89's UI surface is explicitly deferred to
> `ux-ui-designer` by the requirements doc itself and is out of scope here.
>
> Grounded by reading the actual pushed source — `steps/{ConfigTemplateValidateStep,
> ConfigTemplateSubstituteStep,StepSupport}.java`, `model/{ConfigSetVersion,ConfigSet,ConfigSetRole,
> BaseConfigReference,PinMode}.java` — and `pom.xml`. Author: `tech-lead`, 2026-09-03. No code changed by
> this document — contract/design only.
>
> **Correction to a requirements-doc premise, found during grounding:** FR-95's implementation-guidance note
> and OQ-11 both assert `workflow-cps` is "already a dependency of this plugin per `pom.xml`." True, but
> **only at `<scope>test</scope>`** (see `pom.xml` lines 155–159) — it is not on `config-template-sync`'s own
> main/compile classpath. This matters directly for OQ-11's resolution (§7 below): the actual classes needed
> (`FlowNode`, `ThreadNameAction`) do not even live in `workflow-cps` — they live in the separate
> `workflow-api` plugin, which today is **not a dependency of this project at any scope**, not even
> transitively (confirmed: `workflow-step-api`'s own `pom.xml` does not depend on `workflow-api`, and none of
> this project's other main-scope dependencies pull it in either). A new main-scope `pom.xml` dependency is
> required — see §7.
>
> **Amendment (2026-09-03, later same day) — re-resolving §3a against the requirements doc's "automatic
> build-identity pinning" pass (new FR-96–FR-103, `buildVersion` amended in place, `FR-54` amended in place).**
> The requirements doc removed the author-supplied `buildVersion` string parameter entirely (FR-96), replaced
> it with an automatic, Run-derived key (`run.getExternalizableId()`, FR-96), made Deployment Binding
> recording unconditional/default-on (FR-97, no opt-in), and added a narrow `redeployFromRun` override
> (FR-100–FR-103) for the cross-Run rollback case the plain `version` parameter never solved (FR-99's own
> finding). §3a below, which was written against the OLD opt-in model (a `hasBuildVersion` flag gating
> whether a binding lookup/save happened at all), is **rewritten in place** to match. §1's field list, §2's
> resolution paths, §2a/§2b's gap fix, §4/§5 (`explicitlyStandalone`), §6 (`setupConfigTemplate`), and §7
> (OQ-11) are **all unaffected by this amendment** — see the explicit confirmation note closing §2b, and the
> flag closing this note. Nothing else in this document is rewritten.
>
> **Residual inconsistency flagged back to `business-analyst`, not resolved here (out of scope for this
> pass):** FR-90 (the `setupConfigTemplate` convenience step, §6 below) still lists `buildVersion` as one of
> its six stored parameters, and §6's `ConfigTemplateSetupAction`/`mergeWithSetupState` (§3b) both still read
> and forward a `buildVersion` value accordingly. FR-96 removes `buildVersion` as a real parameter of
> `configTemplateSubstitute` itself, but the requirements doc's v10 addition does not amend FR-90–FR-95 to
> match — it is silent on what `setupConfigTemplate`'s own `buildVersion` slot means now that there is nothing
> left on the receiving end to consume it. This document does not resolve that gap (§3b/§6 are explicitly
> unaffected per the note above and are left as originally written) — flag to `business-analyst` for a
> follow-up amendment before `setupConfigTemplate` (FR-90–FR-95) is implemented. It does **not** block FR-96–
> FR-103 (this amendment's own scope), since `configTemplateSubstitute`'s own field set and `Execution.run()`
> logic (§3a below) do not depend on `setupConfigTemplate` at all.

<decision-1-additive-fields>

## 1. `useBase`/`version` fields on `ConfigTemplateValidateStep`/`ConfigTemplateSubstituteStep` — additive, confirmed

Exactly as FR-79 specifies, added the same way `configTemplateSubstitute`'s existing `buildVersion` already
is — `@DataBoundSetter`, not constructor arguments. **Confirmed: purely additive, zero breaking change** to
either step's existing 3-arg `@DataBoundConstructor(projectKey, environment, file)` for FR-79–FR-85 alone.

```java
// Both ConfigTemplateValidateStep and ConfigTemplateSubstituteStep gain these two fields/accessors,
// identically. ConfigTemplateValidateStep does not have ANY @DataBoundSetter today — this is its first.

private Boolean useBase;   // boxed, NOT primitive boolean — see the call-out below
private Integer version;   // already boxed per FR-79's own wording; unchanged

public boolean isUseBase() {
    return Boolean.TRUE.equals(useBase);
}

@DataBoundSetter
public void setUseBase(boolean useBase) {
    this.useBase = useBase;
}

public Integer getVersion() {
    return version;
}

@DataBoundSetter
public void setVersion(int version) {
    this.version = version;
}
```

**One deliberate deviation from FR-79's literal Java-type wording, required by FR-93 (§3b below) — flagged
so the implementer does not "correct" it back to a primitive:** FR-79 describes `useBase` as "boolean,
default `false`" — that is the correct *observable Groovy-DSL contract* for a caller who never touches
`setupConfigTemplate`. But internally the field must be the boxed `Boolean`, starting `null`, because Stapler
only invokes a `@DataBoundSetter` when that named argument was actually present in the call's argument map —
so `this.useBase == null` after construction unambiguously means "this call site did not pass `useBase` at
all" (exactly the same idiom this codebase already uses for `version`, and the same reasoning `buildVersion`
already relies on being nullable). This distinction — "not supplied here" vs. "explicitly supplied `false`
here" — is load-bearing for FR-93's per-parameter precedence merge (§3b): without it, a call site that
relies on `setupConfigTemplate`'s stored `useBase=true` while explicitly setting nothing itself is
indistinguishable, at the Java level, from a call site that explicitly passed `useBase: false` to override
the stored value back off. `isUseBase()` is the only public boolean-returning accessor and is what
`Execution.run()` uses when it is NOT merging against setup state (the FR-79–FR-85-only call path, §2/§3a);
the raw boxed getter is not needed publicly and is omitted.

</decision-1-additive-fields>

<decision-2-usebase-resolution>

## 2. `StepSupport`'s new `useBase=true` resolution path — reuses `getVersion(int)`/`getActiveVersion()` directly, no new lookup method

Confirmed per the ask: no new `ConfigSet`/`ConfigSetRepository` lookup method is needed. New method,
parallel in shape to the existing `resolveEffective`, but bypassing `effectiveBaseChain`/`BaseChainResolver`
entirely (FR-81):

```java
/**
 * FR-81: resolves directly against {@code projectKey}'s own COMMON Config Set only, completely bypassing
 * the env Config Set and its base-chain machinery. A supplied {@code version} pins this SAME COMMON Config
 * Set to that exact version (FR-81's own explicit "never any other project's Config Set" rule — there is
 * only ever one project's Config Set in scope here, so there is no ambiguity to guard against beyond what
 * {@link #requireCommon} already resolves).
 */
static ResolvedEffective resolveUseBaseOnly(ConfigSetRepository repository, String projectKey, Integer version)
        throws AbortException {
    ConfigSet common = requireCommon(repository, projectKey);
    ConfigSetVersion resolved = version != null ? common.getVersion(version) : common.getActiveVersion();
    if (resolved == null) {
        String what = version != null ? "version " + version : "an active version";
        throw new AbortException("Common Config Set for projectKey '" + projectKey + "' has no " + what
                + " to resolve" + (version != null ? " (explicit 'version' parameter, useBase=true)" : ""));
    }
    TreeNode content = TreeFormats.forType(common.getContentType()).parse(resolved.getContentJson());
    List<ResolvedBaseVersion> chain = Collections.singletonList(
            new ResolvedBaseVersion(projectKey, resolved.getVersionNumber()));
    List<ConfigSet> configSets = Collections.singletonList(common);
    return new ResolvedEffective(content, common.getContentType(), chain, configSets);
}
```

Returning the existing `ResolvedEffective` type (not a new one) lets both steps' post-resolution code
(drift check, secrets-manifest fold, logging, binding save) stay branch-agnostic — they already only consume
`ResolvedEffective`'s fields, never care how it was produced. `resolvedBaseChain`/`resolvedBaseConfigSets`
each get exactly one entry (the COMMON Config Set itself standing in for "the chain," which is also exactly
what FR-83 needs — see §2a) rather than an empty list, so any log line that walks `resolvedBaseChain` (e.g.
the substitute step's `joinChain(...)` summary) renders something meaningful ("`projectKey@vN`") instead of
`[]` — this is a deliberate, minor divergence from "bypass the chain entirely" being read too literally: the
chain-shaped *return value* still has one entry, only the chain-*resolution machinery*
(`effectiveBaseChain`/`BaseChainResolver`) is bypassed, exactly as FR-81 actually requires ("MUST NOT call
`requireEnv`/`effectiveBaseChain`/`resolveEffective`" — it does not say the result must look chain-less).

**FR-80's env-pin path needs no new method at all** — it is a one-line change to each step's existing
`Execution.run()`: replace `env.getActiveVersion()` with a version-aware resolution that still calls
`env.getVersion(int)`/`getActiveVersion()` directly:

```java
ConfigSetVersion envVersion;
if (version != null) {
    envVersion = env.getVersion(version);
    if (envVersion == null) {
        throw new AbortException("Env Config Set for projectKey '" + projectKey + "', environment '"
                + environment + "' has no version " + version + " to resolve (explicit 'version' parameter)");
    }
} else {
    envVersion = env.getActiveVersion();
}
```

Everything downstream (`effectiveBaseChain(projectKey, envVersion)`, `resolveEffective(...)`) is **completely
unchanged** — FR-80's own reasoning is exactly right: pinning the env version pins its entire frozen
`baseChain` (or FR-52/FR-87 default) for free, since that's already part of the immutable
`ConfigSetVersion` record.

<decision-2a-secrets-manifest>

### 2a. FR-83 — secrets manifest for `useBase=true`, no new method

`StepSupport.mergedSecretsManifest(List<ConfigSet> resolvedBaseConfigSets, ConfigSet env)` already exists
and already does exactly what FR-83 asks when called with `env = null`:

```java
Map<String, String> secretsManifest = StepSupport.mergedSecretsManifest(resolved.resolvedBaseConfigSets, null);
```

Since `resolveUseBaseOnly`'s `resolvedBaseConfigSets` is the single-element `[common]` list (§2), this is
already "solely from the resolved COMMON Config Set's own manifest" per FR-83's exact wording — the existing
method needs zero changes; only the call site changes what it passes for `env`.

</decision-2a-secrets-manifest>

<decision-2b-contenttype-gap>

### 2b. Real gap found: `resolveEffective`'s content-type fallback breaks once a chain can legitimately be empty (FR-87)

**Not asked for directly, but load-bearing for FR-87 (§4) — flagging now rather than letting the implementer
discover it as a test failure.** Today, `resolveEffective`'s content-type line is:

```java
ContentType resolvedType = distinctTypes.isEmpty() ? ContentType.JSON : distinctTypes.iterator().next();
```

This is silently correct *today* only because `effectiveBaseChain` never actually returns an empty list —
FR-52's synthesized default guarantees at least one entry always. The moment FR-87 makes a genuinely empty
chain a real, reachable case (`explicitlyStandalone == true`), this line starts silently mis-typing every
standalone env Config Set's effective content as `ContentType.JSON` regardless of that env Config Set's own
actual, already-stored `contentType` (every `ConfigSet` — env or common — already carries its own
`contentType` field per `ConfigSet.getContentType()`; FR-60's "inherits from its base chain" is a
*consistency-enforcement* rule, not the sole source of truthfor a standalone version that has no chain to
inherit from). **Fix: `resolveEffective` must take the env `ConfigSet` itself as a new parameter, purely to
supply this one fallback** — both call sites already have `env` in scope trivially:

```java
static ResolvedEffective resolveEffective(ConfigSetRepository repository, List<BaseConfigReference> baseChain,
                                           ConfigSetVersion envVersion, ConfigSet env) throws AbortException {
    // ... unchanged body until the content-type line ...
    ContentType resolvedType = distinctTypes.isEmpty()
            ? (env != null ? env.getContentType() : ContentType.JSON)
            : distinctTypes.iterator().next();
    // ... unchanged rest ...
}
```

Both call sites (`ConfigTemplateValidateStep.Execution.run()`, `ConfigTemplateSubstituteStep.Execution.run()`
branches 2/3) already resolve `env` before calling `resolveEffective`, so this is a pure additive-parameter
change, no new repository fetch. `env` staying `null` only in the theoretical case no caller in this codebase
actually exercises (kept `null`-tolerant only for symmetry with `mergedSecretsManifest`'s existing
`null`-tolerant `env` parameter, §2a).

**Confirmed unaffected by the 2026-09-03 automatic build-identity pinning amendment (see the note at the top
of this document):** this fix is orthogonal to the `buildVersion` → automatic-Run-identity change. It concerns
what `resolveEffective` falls back to when a resolved `baseChain` is legitimately empty (FR-87,
`explicitlyStandalone`) — a question about *how many bases were folded*, not *which Run-identity key a binding
is filed under*. Nothing in FR-96–FR-103 touches `effectiveBaseChain`, `resolveEffective`, or the
`explicitlyStandalone`/empty-chain case at all. The fix stands exactly as specified above, unchanged.

</decision-2b-contenttype-gap>

</decision-2-usebase-resolution>

<decision-3-precedence>

## 3. Two DISTINCT precedence rules — do not conflate them

The requirements doc itself flags these as two different axes; here is the exact code shape for each, kept
deliberately far apart so an implementer cannot merge them into one "explicit wins" helper by accident.

<decision-3a-fr82>

### 3a. FR-96–FR-103 — three-way resolution in `ConfigTemplateSubstituteStep.Execution.run()` (rewritten 2026-09-03, replaces the old opt-in `hasBuildVersion` model)

**Superseded premise, stated for the record:** the previous version of this section was written against the
OLD model — `buildVersion` an optional, author-supplied string; binding lookup/save gated on `hasBuildVersion`
being true. That flag no longer exists and no longer makes sense: binding recording is now **always on**
(FR-97), keyed automatically off the current `Run`'s own identity (FR-96) — there is no "was a build version
supplied" branch left at all. The only remaining conditionals are (a) whether an explicit `version` param
suppresses the binding write for one call (FR-98, unchanged in substance from old FR-82), and (b) whether
`redeployFromRun` (FR-100–FR-103, new) redirects the lookup to a DIFFERENT Run's binding instead of the
current Run's own. Scope, unchanged: `ConfigTemplateSubstituteStep.Execution.run()` only —
`configTemplateValidate` has no binding concept at all and is untouched by any of FR-96–FR-103.

#### Field-set change on `ConfigTemplateSubstituteStep` (final shape)

- **Remove entirely:** the `buildVersion` field, its `getBuildVersion()`/`@DataBoundSetter setBuildVersion`
  accessors, and every reference to it in `Execution` (constructor param, field, javadoc). This is the FR-96
  hard-removal the requirements doc's "Migration / breaking change" note mandates (recommended: hard removal,
  not a deprecation shim) — any existing Jenkinsfile call site passing `buildVersion: '...'` fails loudly
  (Pipeline DSL "unrecognized named parameter") the next time it runs. That is the intended, accepted
  behavior, not a regression to guard against.
- **Add:** `redeployFromRun` (`String`, `@DataBoundSetter`, absent/`null` by default) — **type decision: plain
  `String`, not `Integer` and not a two-field split.** FR-100's own wording mandates the value can be either a
  bare integer (build number of the CURRENT job) or a full `<jobFullName>#<buildNumber>` string (a different
  job's build) — the exact shape `Run#getExternalizableId()`/`Run.fromExternalizableId(String)` already use.
  A plain `Integer` cannot represent the cross-job case at all, so `String` is the only type that satisfies
  FR-100 as written; FR-100 explicitly leaves "a single string vs. two separate `redeployFromJob`/
  `redeployFromBuildNumber` fields" open as a `tech-lead` call — **decision: single `String` field**, because
  it mirrors the exact wire format Jenkins core itself already emits/consumes for this identity
  (`Run#getExternalizableId()`), needs no new parsing convention of this plugin's own invention, and keeps the
  common case (same-job redeploy, a bare number an operator copies straight out of Jenkins' own build-history
  UI) a single scalar argument rather than a two-argument tuple where one argument is usually omitted.
  **Yes, it supports cross-job lookups by design** — that is the entire reason FR-100 specifies the
  `job#buildNumber` shape rather than a bare-integer-only parameter; same-job-only would not need it.

```java
public class ConfigTemplateSubstituteStep extends Step {
    private final String projectKey;
    private final String environment;
    private final String file;
    private String redeployFromRun;   // replaces "private String buildVersion;" — same nullable-String idiom

    @DataBoundConstructor
    public ConfigTemplateSubstituteStep(String projectKey, String environment, String file) {
        this.projectKey = projectKey;
        this.environment = environment;
        this.file = file;
    }

    public String getRedeployFromRun() {
        return redeployFromRun;
    }

    @DataBoundSetter
    public void setRedeployFromRun(String redeployFromRun) {
        this.redeployFromRun = redeployFromRun;
    }
    // getProjectKey/getEnvironment/getFile, useBase/version fields (§1) — all unchanged.
}
```

(`useBase`/`version` — §1 — are completely unaffected by this amendment; they still exist, unchanged, and
still compose with this new logic exactly as FR-101 spells out below.)

#### Resolving `redeployFromRun` to a target Run-identity string

```java
/** FR-100: bare integer -> current job's own externalizableId shape; already-"#"-shaped -> used as-is. */
private static String resolveTargetIdentity(Run<?, ?> run, String redeployFromRun) {
    if (redeployFromRun.indexOf('#') >= 0) {
        return redeployFromRun; // already "<jobFullName>#<buildNumber>"
    }
    return run.getParent().getFullName() + "#" + redeployFromRun.trim();
}
```

#### The three-way resolution, in FR-101's own precedence order

```java
boolean hasVersion = version != null;                       // merged value, post-FR-93 (§3b) if applicable
boolean hasRedeployFromRun = redeployFromRun != null && !redeployFromRun.trim().isEmpty();
String ownIdentity = run.getExternalizableId();              // FR-96

if (hasVersion) {
    // ── Branch A (FR-79–82, FR-98, FR-101's top precedence) ──────────────────────────────────────
    // version wins outright, unconditionally — no binding lookup, no binding save, redeployFromRun is
    // NOT consulted at all even if also supplied (FR-101: "regardless of redeployFromRun"). This is
    // NOT an unaddressed edge case needing a tech-lead judgment call — FR-101 explicitly specifies this
    // outcome for the both-supplied-together combination; see the call-out below the code.
    if (useBase) {
        StepSupport.ResolvedEffective resolved =
                StepSupport.resolveUseBaseOnly(configSetRepository, projectKey, version);
        effective = resolved.mergedConfig;
        effectiveType = resolved.contentType;
        resolvedBaseChain = resolved.resolvedBaseChain;
        resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
        envVersion = null;
    } else {
        envVersion = env.getVersion(version);
        if (envVersion == null) {
            throw new AbortException("Env Config Set for projectKey '" + projectKey + "', environment '"
                    + environment + "' has no version " + version + " to resolve (explicit 'version' parameter)");
        }
        List<BaseConfigReference> chain = StepSupport.effectiveBaseChain(projectKey, envVersion);
        StepSupport.ResolvedEffective resolved =
                StepSupport.resolveEffective(configSetRepository, chain, envVersion, env);
        effective = resolved.mergedConfig;
        effectiveType = resolved.contentType;
        resolvedBaseChain = resolved.resolvedBaseChain;
        resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
    }
    skipBindingWrite = true;   // FR-98
    if (hasRedeployFromRun) {
        listener.getLogger().println("[configTemplateSync] 'redeployFromRun' ('" + redeployFromRun
                + "') was supplied together with an explicit 'version' — per FR-101, 'version' takes "
                + "full precedence and 'redeployFromRun' is ignored for this call.");
    }
} else if (hasRedeployFromRun) {
    // ── Branch B (FR-100–103, FR-101's middle precedence) ────────────────────────────────────────
    String targetIdentity = resolveTargetIdentity(run, redeployFromRun);
    ConfigDeploymentBinding targetBinding = bindingRepository.find(projectKey, environment, targetIdentity);
    if (targetBinding != null) {
        // Frozen-chain replay, identical mechanics to the old Branch 1 (concrete (projectKey,vN) lookups).
        resolvedBaseChain = resolveFrozenChain(configSetRepository, targetBinding, targetIdentity);
        resolvedBaseConfigSets = /* one ConfigSet per resolvedBaseChain entry, as before */;
        effectiveType = resolvedBaseConfigSets.isEmpty() ? ContentType.JSON : resolvedBaseConfigSets.get(0).getContentType();
        envVersion = env.getVersion(targetBinding.getEnvVersionNumber());
        effective = EffectiveConfigResolver.resolveChain(effectiveType, baseContentsOf(resolvedBaseChain, resolvedBaseConfigSets),
                envVersion == null ? null : envVersion.getContentJson());
        pinned = true;
        listener.getLogger().println("[configTemplateSync] redeployFromRun '" + redeployFromRun
                + "' (resolved target '" + targetIdentity + "') is pinned to base chain ["
                + joinChain(resolvedBaseChain) + "] / env v" + targetBinding.getEnvVersionNumber());
    } else {
        // FR-103: fail-loud-but-non-blocking — warn by name, then fall back to live resolution. Does
        // NOT abort the build and does NOT silently substitute the target Run's live-active config
        // (there is no "target Run's live config" concept at all here) — it resolves THIS env version's
        // OWN current base chain live, exactly like the no-binding fallback below.
        listener.getLogger().println("[configTemplateSync][WARN] redeployFromRun '" + redeployFromRun
                + "' (resolved target '" + targetIdentity + "') has no deployment binding; falling back "
                + "to the currently active/pinned base chain for projectKey '" + projectKey
                + "', environment '" + environment + "'.");
        envVersion = env.getActiveVersion();
        List<BaseConfigReference> chain = StepSupport.effectiveBaseChain(projectKey, envVersion);
        StepSupport.ResolvedEffective resolved =
                StepSupport.resolveEffective(configSetRepository, chain, envVersion, env);
        effective = resolved.mergedConfig;
        effectiveType = resolved.contentType;
        resolvedBaseChain = resolved.resolvedBaseChain;
        resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
    }
    skipBindingWrite = false;   // FR-97/FR-102: redeployFromRun never suppresses the OWN-run binding write
} else {
    // ── Branch C (FR-25/26/27, FR-96/97's ordinary case, FR-101's default) ───────────────────────
    // Self-lookup by the current Run's own identity — this is what lets a Pipeline restart-from-stage
    // naturally re-find its own prior binding (FR-25's "(a) the current Run's own ... identity" case).
    ConfigDeploymentBinding ownBinding = bindingRepository.find(projectKey, environment, ownIdentity);
    if (ownBinding != null) {
        resolvedBaseChain = resolveFrozenChain(configSetRepository, ownBinding, ownIdentity);
        // ... identical shape to Branch B's found-binding path ...
        pinned = true;
    } else {
        // FR-27: this Run's very first real substitution under its own identity — expected, NOT a
        // warned-about fallback (no FR-26 warning fires here).
        envVersion = env.getActiveVersion();
        List<BaseConfigReference> chain = StepSupport.effectiveBaseChain(projectKey, envVersion);
        StepSupport.ResolvedEffective resolved =
                StepSupport.resolveEffective(configSetRepository, chain, envVersion, env);
        effective = resolved.mergedConfig;
        effectiveType = resolved.contentType;
        resolvedBaseChain = resolved.resolvedBaseChain;
        resolvedBaseConfigSets = resolved.resolvedBaseConfigSets;
    }
    skipBindingWrite = false;   // FR-97: always writes/updates the own-Run binding, default-on
}

// ... drift re-check, secrets resolution, token substitution — all unchanged from today's code ...

if (!skipBindingWrite) {
    int envVersionNumber = envVersion == null ? 0 : envVersion.getVersionNumber();
    // FR-97/FR-102: ALWAYS keyed by the CURRENT run's own identity, never by redeployFromRun's target —
    // this is what "forward-chains" a fresh, individually-replayable binding for THIS run even when its
    // content was pinned from a redeploy target (Branch B's found-binding case).
    bindingRepository.save(projectKey, environment, ownIdentity, resolvedBaseChain, envVersionNumber,
            System.currentTimeMillis());
}
```

**Restated cleanly (the three branches):**
1. **No `version`, no `redeployFromRun`:** resolve live via `effectiveBaseChain`/`resolveEffective` as
   normal (with a self-lookup first against the current Run's own binding, for the restart-from-stage case),
   then **unconditionally** create/update a binding keyed by `run.getExternalizableId()` (FR-96/97).
2. **`version` supplied (regardless of `redeployFromRun`):** resolve per the FR-79–82 pinning logic
   (env-version pin when `useBase=false`, common-version pin when `useBase=true`), and — per FR-98 — do
   **not** perform any binding lookup or write for this call at all. `redeployFromRun`, if also supplied, is
   silently not consulted (per FR-101's explicit wording), though logging it once is a harmless, recommended
   quality-of-life addition, not a requirement.
3. **`redeployFromRun` supplied (no `version`):** resolve the TARGET Run's identity string, look up its
   binding. Found → use its frozen chain (byte-identical replay, FR-54's amended acceptance criterion), AND
   still write a fresh binding keyed by the CURRENT run's own identity (FR-102 — this is what keeps a chain of
   redeploys each individually re-targetable, not just repeatedly pointing back at the original source build).
   Not found → FR-103's "fail-loud-but-non-blocking": warn by name (naming the unresolved `redeployFromRun`
   value explicitly, not a generic message), then fall back to resolving the CURRENT env version's own current
   base chain live — the build is **not** aborted and nothing is silently substituted from a "target Run's
   live config" (no such concept exists); the own-Run binding write at the bottom still runs afterward exactly
   as in branch 1, since only `version` (FR-98), never `redeployFromRun`, suppresses it.

**Both-supplied-together (`version` AND `redeployFromRun` on the same call) — already addressed by the
requirements doc, not a genuine gap requiring a tech-lead judgment call.** Initial read suggested FR-79–103
might be silent on this combination; on a close re-read, **FR-101 explicitly covers it**: *"an explicit
`version` ... MUST take precedence over everything below it and suppresses all binding lookup/save for that
call, regardless of `redeployFromRun`."* That sentence is unambiguous about the outcome (version wins outright,
`redeployFromRun` is not consulted at all) — implementing a config-time `AbortException` for this combination,
as an initial instinct might suggest, would **contradict** an already-specified MUST, not fill a gap. The one
discretionary addition this document makes (not mandated, but recommended, and shown in the code above): log a
single informational line when both are present, naming that `redeployFromRun` was ignored — this improves
build-log auditability (in the spirit of NFR-7) without deviating from FR-101's mandated behavior in any way,
and can be dropped by the implementer if considered unnecessary noise.

#### `ConfigDeploymentBindingRepository`'s lookup key — signature UNCHANGED, semantics changed

**Confirmed: the Java signature does NOT change shape.** The requirements doc is explicit on this point (§3
entity note, "Deployment Binding," 2026-09-03 amendment): *"the underlying persisted field/shape
(`ConfigDeploymentBinding.buildVersion`, a `String`) is UNCHANGED — only what populates it changes."* This
document's earlier instinct (before reading that note) would have been to rename the parameter/field to
`runExternalizableId` for clarity — **that is explicitly NOT what the requirements doc asks for, and doing it
anyway would be a gratuitous, unrequested rename**, not a contract requirement. Decision: **keep
`ConfigDeploymentBinding.buildVersion` and `ConfigDeploymentBindingRepository.find(String projectKey, String
environment, String buildVersion)` / `.save(String projectKey, String environment, String buildVersion, ...)`
exactly as they are today, field/parameter names included.** Only the *values* passed into that unchanged
`String buildVersion` slot change going forward: from a hand-typed literal, to either `run.getExternalizableId()`
(FR-96, the ordinary case) or a `redeployFromRun`-resolved target identity string looked up but never written
under that name (writes are always the current run's own identity — see the resolution logic above). The key
*shape* — `(projectKey, environment, buildVersion)` as three positional `String` arguments — is unchanged;
only the *semantic content* of the third argument is (a manually-typed version literal, before, vs. a
Jenkins-native Run-identity string, after). `jenkins-plugin-developer` should not rename this field/parameter
as part of implementing FR-96–FR-103 — that would be scope creep beyond what either the requirements doc or
this contract calls for.

**Orphaned pre-existing bindings — accepted, intentional, stated plainly, not a silent gap.** Any
`ConfigDeploymentBinding` persisted before this change, whose `buildVersion` field holds an old hand-typed
literal (e.g. `"1.2.3"`), remains on disk untouched and is **never rewritten or migrated** — the requirements
doc's own words: *"No data migration is needed for existing binding records; they keep matching by their
existing string value exactly as before."* In practice, though, nothing in the codebase will ever again
*query* for that old literal string once `buildVersion` is removed as a real pipeline parameter (FR-96's hard
removal) — every future lookup path passes either `run.getExternalizableId()` or a `redeployFromRun`-resolved
target identity, both of which are `<jobFullName>#<buildNumber>`-shaped strings that will never coincidentally
equal an old hand-typed literal like `"1.2.3"`. So while the *lookup mechanism* is unchanged, these
pre-existing records become **permanently unreachable in practice** — a fact this document states plainly as
an accepted, intentional consequence of the hard-removal decision (matching the requirements doc's own
explicit "hard removal, no deprecation period" recommendation for `buildVersion` itself), not a
silently-discovered gap. No cleanup/purge of these orphaned records is proposed or required — NFR-1's "no
external database" persistence model already has no retention policy (OQ-4, resolved: no pruning in v1), and
a handful of permanently-unreachable XML list entries carries no meaningful cost.

</decision-3a-fr82>

<decision-3b-fr93>

### 3b. FR-93 — explicit call-site parameter beats stored `setupConfigTemplate` state, per parameter

Axis: **before any of §2/§3a's resolution logic runs at all, where do the six input values
(`projectKey`/`environment`/`file`/`buildVersion`/`useBase`/`version`) come from** — this call's own
explicit arguments, or a prior `setupConfigTemplate` call's stored state for this `Run`. This runs strictly
**upstream** of FR-82 — FR-82 then operates on whatever `version`/`buildVersion` values this merge produced,
regardless of which source they came from.

One shared method, called at the very top of `ConfigTemplateValidateStep.Execution.run()` and
`ConfigTemplateSubstituteStep.Execution.run()`, replacing the raw field reads both currently do:

```java
/** Also implements FR-94 (fail-loud on insufficient parameters) as this method's single exit-check. */
static final class EffectiveParams {
    final String projectKey;
    final String environment;
    final String file;
    final String buildVersion;   // still nullable — "no buildVersion at all" remains a valid, common case
    final boolean useBase;
    final Integer version;

    EffectiveParams(String projectKey, String environment, String file, String buildVersion,
                     boolean useBase, Integer version) {
        this.projectKey = projectKey;
        this.environment = environment;
        this.file = file;
        this.buildVersion = buildVersion;
        this.useBase = useBase;
        this.version = version;
    }
}

static EffectiveParams mergeWithSetupState(Run<?, ?> run, String callProjectKey, String callEnvironment,
        String callFile, String callBuildVersion, Boolean callUseBase, Integer callVersion)
        throws AbortException {
    ConfigTemplateSetupAction setup = run == null ? null : run.getAction(ConfigTemplateSetupAction.class);

    String projectKey = callProjectKey != null ? callProjectKey : (setup == null ? null : setup.getProjectKey());
    String environment = callEnvironment != null ? callEnvironment : (setup == null ? null : setup.getEnvironment());
    String file = callFile != null ? callFile : (setup == null ? null : setup.getFile());
    String buildVersion = callBuildVersion != null ? callBuildVersion : (setup == null ? null : setup.getBuildVersion());
    boolean useBase = callUseBase != null ? callUseBase : (setup != null && setup.isUseBase());
    Integer version = callVersion != null ? callVersion : (setup == null ? null : setup.getVersion());

    List<String> missing = new ArrayList<>();
    if (isBlank(projectKey)) missing.add("projectKey");
    if (isBlank(environment)) missing.add("environment");
    if (isBlank(file)) missing.add("file");
    if (!missing.isEmpty()) {
        // FR-94's exact message shape — see §8.
        throw new AbortException("[configTemplateSync] Missing required parameter(s) " + missing
                + " — not supplied explicitly on this call, and no prior setupConfigTemplate() call in this "
                + "build provided them.");
    }
    return new EffectiveParams(projectKey, environment, file, buildVersion, useBase, version);
}

private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
}
```

**Per-parameter, not all-or-nothing (FR-93's own explicit example):** this is why the merge happens field by
field, never "if any explicit arg is present, ignore the setup state entirely" — a call supplying only
`file` explicitly still picks up stored `projectKey`/`environment` unchanged. Both steps' `Execution.run()`
call this once, then use `EffectiveParams` exclusively for the rest of the method (`§2`'s `version`/`useBase`
reads above are reads of `params.version`/`params.useBase`, not the step's own raw fields).

**Structural consequence for FR-91 — this is the load-bearing signature change, not an optional nicety:**
for `configTemplateValidate()`/`configTemplateSubstitute()` to be callable with **zero** arguments at all
(FR-91's literal wording), `projectKey`/`environment`/`file` can no longer be `@DataBoundConstructor`
parameters — Jenkins Pipeline's `DescribableModel`/`DataBoundConstructor` binding requires every constructor
parameter to be resolvable from the call's named-argument map (a step with required constructor parameters
cannot be invoked as `stepName()` with none of them supplied at all; only `@DataBoundSetter`-based parameters
can be omitted from a call). **Both steps' constructors must become no-arg, with `projectKey`/`environment`/
`file` demoted to `@DataBoundSetter` fields** — mirroring exactly how `buildVersion` is already optional
today:

```java
public class ConfigTemplateValidateStep extends Step {
    private String projectKey;
    private String environment;
    private String file;
    private Boolean useBase;
    private Integer version;

    @DataBoundConstructor
    public ConfigTemplateValidateStep() {
    }

    @DataBoundSetter public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    @DataBoundSetter public void setEnvironment(String environment) { this.environment = environment; }
    @DataBoundSetter public void setFile(String file) { this.file = file; }
    @DataBoundSetter public void setUseBase(boolean useBase) { this.useBase = useBase; }
    @DataBoundSetter public void setVersion(int version) { this.version = version; }
    // existing getters unchanged in shape (getProjectKey/getEnvironment/getFile), just no longer final fields
}
```

This is explicitly **in scope** for FR-90–FR-95 despite the FR-79–FR-85 section's own header note ("amend
neither... existing required parameters... — every existing call site... keeps compiling and behaving
identically") — that promise is scoped to the FR-79–FR-85 change alone (adding `useBase`/`version` without
touching the constructor), and is not violated by *this* section's necessary, separate relaxation: an
existing call site that always supplied all three positionally/named (`configTemplateValidate(projectKey:
'x', environment: 'y', file: 'z')`) keeps compiling and behaving identically either way, since named-argument
Pipeline step calls do not care whether a parameter is bound via constructor or setter. Flagging this
explicitly so the implementer does not read the earlier "no breaking change" language as forbidding this
necessary, still-fully-backward-compatible constructor change.

`ConfigTemplateSubstituteStep` gets the identical treatment (`projectKey`/`environment`/`file` demoted
alongside the pre-existing `buildVersion` setter).

**`ConfigTemplateValidateStep.DescriptorImpl.getRequiredContext()` must add `Run.class`** (it currently
declares only `TaskListener.class`/`FilePath.class`) — needed for `mergeWithSetupState`'s
`run.getAction(ConfigTemplateSetupAction.class)` lookup. `ConfigTemplateSubstituteStep` already declares
`Run.class`, no change needed there.

</decision-3b-fr93>

</decision-3-precedence>

<decision-4-explicitlystandalone>

## 4. `explicitlyStandalone` on `ConfigSetVersion` — additive, but NOT a literal mirror of `baseChain`'s migration mechanism

**Correction to the requirements doc's "mirroring... exactly" framing:** `baseChain` is a `List` (reference
type) — XStream2's `RobustReflectionConverter` leaves it `null` when deserializing XML written before the
field existed, which is why `ConfigSetVersion.readResolve()` needs an explicit null-check-and-reconstruct.
`explicitlyStandalone` (FR-86) is a **primitive `boolean`** per its own spec wording — a primitive field
missing from old XML deserializes to its JVM default, `false`, **automatically, with no `readResolve()`
logic needed for this field specifically.** That default (`false`) is already exactly FR-88's required
backward-compatibility behavior. This is a simplification versus the ask's phrasing, not an extra step.

```java
public class ConfigSetVersion implements Serializable {
    // ... existing fields unchanged ...
    private final boolean explicitlyStandalone;

    /** Pre-existing 5-arg constructor — unchanged, still defaults baseChain empty + explicitlyStandalone false. */
    public ConfigSetVersion(int versionNumber, String contentJson, String note, String author,
                             long timestampEpochMillis) {
        this(versionNumber, contentJson, note, author, timestampEpochMillis, Collections.emptyList(), false);
    }

    /** Pre-existing 6-arg constructor (FR-51) — unchanged call sites keep compiling; defaults explicitlyStandalone false. */
    public ConfigSetVersion(int versionNumber, String contentJson, String note, String author,
                             long timestampEpochMillis, List<BaseConfigReference> baseChain) {
        this(versionNumber, contentJson, note, author, timestampEpochMillis, baseChain, false);
    }

    /** New overload (FR-86): persists explicitlyStandalone as part of this version's own immutable record. */
    public ConfigSetVersion(int versionNumber, String contentJson, String note, String author,
                             long timestampEpochMillis, List<BaseConfigReference> baseChain,
                             boolean explicitlyStandalone) {
        // ... existing body (note/content/author null-and-blank checks) unchanged ...
        this.baseChain = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(baseChain, "baseChain")));
        this.explicitlyStandalone = explicitlyStandalone;
    }

    public boolean isExplicitlyStandalone() {
        return explicitlyStandalone;
    }

    /** Only baseChain (a reference type) needs this null-coalescing branch — see the class-level note above. */
    private Object readResolve() {
        if (baseChain == null) {
            return new ConfigSetVersion(versionNumber, contentJson, note, author, timestampEpochMillis,
                    Collections.emptyList(), explicitlyStandalone);
        }
        return this;
    }
}
```

Passing `this.explicitlyStandalone` through in the reconstruction branch (rather than hardcoding `false`)
is deliberate defensive precision even though, in practice, any document old enough to have `baseChain ==
null` necessarily predates FR-86 too (both fields are only ever written together by this codebase's own
save path going forward), so `explicitlyStandalone` is already guaranteed `false` there — passing it through
explicitly avoids relying on that reasoning being remembered correctly by a future reader.

<decision-4a-effectivebasechain-guard>

### 4a. FR-87's early-return guard — exact placement in `effectiveBaseChain`

```java
static List<BaseConfigReference> effectiveBaseChain(String projectKey, ConfigSetVersion envVersion) {
    if (envVersion != null) {
        if (envVersion.isExplicitlyStandalone()) {
            // FR-87: a deliberate zero-base declaration — never FR-52's synthesized single-entry default.
            return Collections.emptyList();
        }
        List<BaseConfigReference> declared = envVersion.getBaseChain();
        if (declared != null && !declared.isEmpty()) {
            return declared;
        }
    }
    return Collections.singletonList(BaseConfigReference.active(projectKey));
}
```

Placed as the FIRST check inside the `envVersion != null` branch, strictly before the existing
`declared`/`isEmpty()` check — order matters: an `explicitlyStandalone` version's own `baseChain` is
required to be empty anyway (a version cannot simultaneously be explicitly-standalone AND carry a non-empty
chain, enforced at write time, §5), so in practice the two checks never actually compete on the same real
version — but placing the `explicitlyStandalone` check first documents the actual precedence intent
unambiguously for a future reader, rather than relying on that invariant being remembered.

No change needed to `EffectiveConfigResolver.resolveChain`/`resolveEffective`'s fold loop itself — an empty
`baseChain` already correctly produces an empty `baseContents` list, which `resolveChain` already folds to
`{}` before applying the env patch on top (this is the same "zero bases" case the fold algorithm has always
handled for `EffectiveConfigResolver.resolve`'s trivial single-base entry point). The only other change
needed for FR-87 is the content-type fallback fix in §2b, which this exact scenario is what actually makes
reachable.

</decision-4a-effectivebasechain-guard>

</decision-4-explicitlystandalone>

<decision-5-addversion-validation>

## 5. `ConfigSet.addVersion` — one new overload, both checks validated together

**Decision: new parameter is required** (cannot be inferred/derived — `explicitlyStandalone` is an
independent operator choice from `baseChain`'s contents, per §3 of the entity note) — **and both the FR-53
(non-empty `baseChain` on COMMON) and the new FR-86 (`explicitlyStandalone=true` on COMMON) checks, plus the
new FR-86/87 contradiction check (`explicitlyStandalone=true` with a non-empty `baseChain`), are validated
together in this one method**, not split across call sites — consistent with FR-53's own existing check
already living here rather than in the pipeline steps or UI layer.

```java
/** Appends a new version carrying an explicit base chain and standalone flag (FR-51, FR-86). */
public int addVersion(String contentJson, String note, String author, long timestampEpochMillis,
                       List<BaseConfigReference> baseChain, boolean explicitlyStandalone) {
    if (role == ConfigSetRole.COMMON) {
        if (baseChain != null && !baseChain.isEmpty()) {
            // FR-53, unchanged.
            throw new IllegalArgumentException(
                    "A COMMON-role Config Set must not declare a base chain of its own");
        }
        if (explicitlyStandalone) {
            // FR-86: mirrors FR-53's own restriction for the new flag.
            throw new IllegalArgumentException(
                    "A COMMON-role Config Set must not be marked explicitlyStandalone");
        }
    }
    if (explicitlyStandalone && baseChain != null && !baseChain.isEmpty()) {
        // New contradiction check (FR-86/87 area) — the exact message the ask asks to decide.
        throw new IllegalArgumentException(
                "explicitlyStandalone=true is contradictory with a non-empty baseChain — an "
                        + "explicitly-standalone env Config Set Version must declare zero base configs");
    }
    enforceSecretPlaceholders(contentJson);
    int nextVersionNumber = nextVersionNumber();
    versions.add(new ConfigSetVersion(nextVersionNumber, contentJson, note, author, timestampEpochMillis,
            baseChain, explicitlyStandalone));
    return nextVersionNumber;
}
```

**Decision: hard validation error, not silent normalization** (e.g. silently forcing `baseChain` to empty
when `explicitlyStandalone=true` is supplied alongside a non-empty one) — consistent with this codebase's
existing convention of never silently truncating/correcting a contradictory save (FR-53's own comment says
exactly this: "MUST be rejected (fail the save, not silently truncated to empty)"; the same principle
applies here). The two existing 4-arg and 5-arg `addVersion` overloads are kept, unchanged, each delegating
forward with `explicitlyStandalone = false` — so every existing call site (which never mentions this new
flag) keeps compiling and behaving identically.

</decision-5-addversion-validation>

<decision-6-setupconfigtemplate>

## 6. `SetupConfigTemplateStep` — synchronous, no block, `Run`-scoped `InvisibleAction`

**Shape:** a `Step`/`StepExecution` pair, matching the other two steps' family, but note one deliberate base
class difference (see below). No body/block argument (`takesImplicitBlockArgument() == false`, same as the
other two).

**Stored-state class — `ConfigTemplateSetupAction`, package-private, `InvisibleAction`:**

```java
package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.model.InvisibleAction;

import java.io.Serializable;

/**
 * Build-scoped storage for {@code setupConfigTemplate}'s parameters (FR-90). Attached to the {@link
 * hudson.model.Run} via {@code addOrReplaceAction} (never plain {@code addAction} — see the class-level
 * note on {@link SetupConfigTemplateStep.Execution#run()}), so a same-build, zero/partial-argument
 * {@code configTemplateValidate()}/{@code configTemplateSubstitute()} call can read back whichever
 * parameters it did not itself explicitly supply (FR-91/FR-93). {@code InvisibleAction} — this state is a
 * pipeline-internal implementation detail, never rendered on the build's own UI page.
 */
final class ConfigTemplateSetupAction extends InvisibleAction implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String projectKey;
    private final String environment;
    private final String file;
    private final String buildVersion;
    private final boolean useBase;
    private final Integer version;

    ConfigTemplateSetupAction(String projectKey, String environment, String file, String buildVersion,
                               boolean useBase, Integer version) {
        this.projectKey = projectKey;
        this.environment = environment;
        this.file = file;
        this.buildVersion = buildVersion;
        this.useBase = useBase;
        this.version = version;
    }

    String getProjectKey() { return projectKey; }
    String getEnvironment() { return environment; }
    String getFile() { return file; }
    String getBuildVersion() { return buildVersion; }
    boolean isUseBase() { return useBase; }
    Integer getVersion() { return version; }
}
```

**`SetupConfigTemplateStep` itself:**

```java
package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class SetupConfigTemplateStep extends Step {

    private String projectKey;
    private String environment;
    private String file;
    private String buildVersion;
    private Boolean useBase;
    private Integer version;

    @DataBoundConstructor
    public SetupConfigTemplateStep() {
    }

    @DataBoundSetter public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    @DataBoundSetter public void setEnvironment(String environment) { this.environment = environment; }
    @DataBoundSetter public void setFile(String file) { this.file = file; }
    @DataBoundSetter public void setBuildVersion(String buildVersion) { this.buildVersion = buildVersion; }
    @DataBoundSetter public void setUseBase(boolean useBase) { this.useBase = useBase; }
    @DataBoundSetter public void setVersion(int version) { this.version = version; }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, projectKey, environment, file, buildVersion,
                useBase != null && useBase, version);
    }

    static class Execution extends SynchronousStepExecution<Void> {
        // Deliberately SynchronousStepExecution, NOT SynchronousNonBlockingStepExecution like the other two
        // steps: this step does no file/credential/network I/O (it only appends an in-memory Action to the
        // running Run), so there is nothing worth handing off to the async-step thread pool for — running
        // it on the CPS execution thread directly is both simpler and correct. Flagged so the implementer
        // does not "match" the other two steps' base class out of habit.

        private static final long serialVersionUID = 1L;

        private final String projectKey;
        private final String environment;
        private final String file;
        private final String buildVersion;
        private final boolean useBase;
        private final Integer version;

        Execution(StepContext context, String projectKey, String environment, String file,
                  String buildVersion, boolean useBase, Integer version) {
            super(context);
            this.projectKey = projectKey;
            this.environment = environment;
            this.file = file;
            this.buildVersion = buildVersion;
            this.useBase = useBase;
            this.version = version;
        }

        @Override
        protected Void run() throws Exception {
            FlowNode flowNode = getContext().get(FlowNode.class);
            String branch = ParallelBranchGuard.enclosingParallelBranchName(flowNode);
            if (branch != null) {
                // FR-95 — see §7 for ParallelBranchGuard's grounded implementation.
                throw new AbortException("[configTemplateSync] setupConfigTemplate() is not supported "
                        + "inside a parallel {} branch ('" + branch + "') — its build-scoped state would be "
                        + "ambiguous across concurrently-running branches. Call configTemplateValidate/"
                        + "configTemplateSubstitute with their own full explicit parameters inside "
                        + "parallel {} instead.");
            }
            Run<?, ?> run = getContext().get(Run.class);
            // addOrReplaceAction, not addAction: a build MAY legitimately call setupConfigTemplate() more
            // than once (e.g. re-pointing to a different environment partway through a Jenkinsfile) — a
            // second call must REPLACE the stored state, not leave two ConfigTemplateSetupAction instances
            // on the Run where run.getAction(Class) resolution order would be an unspecified surprise.
            run.addOrReplaceAction(new ConfigTemplateSetupAction(projectKey, environment, file, buildVersion,
                    useBase, version));
            return null;
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "setupConfigTemplate";
        }

        @Override
        public String getDisplayName() {
            return "Store config-template parameters for the current build";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            Set<Class<?>> context = new LinkedHashSet<>();
            context.add(Run.class);
            context.add(FlowNode.class);
            return Collections.unmodifiableSet(context);
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }
    }
}
```

**Lookup side, in `ConfigTemplateValidateStep`/`ConfigTemplateSubstituteStep`'s `Execution.run()`:** already
covered in full in §3b's `mergeWithSetupState` — the single line `run.getAction(ConfigTemplateSetupAction.class)`
is the entire lookup; no new accessor needed on `Run` itself (`Actionable.getAction(Class)` is inherited).

</decision-6-setupconfigtemplate>

<decision-7-oq11>

## 7. OQ-11 — resolved: `FlowNode`/`ThreadNameAction`, via a NEW main-scope `workflow-api` dependency; reject any `workflow-cps`/`CpsThread`-based alternative

**(a) Does `FlowNode.getEnclosingBlocks()` alone suffice, or is manual parent-walking needed?**
Neither, exactly — **use `FlowNode.iterateEnclosingBlocks()`**, not `getEnclosingBlocks()`.
`workflow-api`'s own `docs/flowgraph.md` explicitly documents `getEnclosingBlocks()`/`getAllEnclosingIds()`
as eager, "walk the entire flow graph" calls to use "with caution," and recommends the lazy,
internally-cached `iterateEnclosingBlocks()`/`getEnclosingId()` pair instead for exactly this
"is one specific ancestor block present" style of check, where returning early on the first match matters.
No manual parent-node-walking is needed either way — both methods already do the ancestry walk internally;
the choice is only eager-list-then-scan vs. lazy-iterate-then-break.

```java
package io.github.retrokharkov1.configtemplatesync.steps;

import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

/**
 * Detects whether a {@link FlowNode} is enclosed inside a {@code parallel {}} branch body (FR-95),
 * by walking its enclosing blocks looking for the marker a `parallel` branch's own start node carries.
 * Grounded against `jenkinsci/workflow-api-plugin`'s own flowgraph.md and
 * `jenkinsci/pipeline-plugin` PR #80 (JENKINS-26122): each parallel branch's start node is decorated
 * with a {@link ThreadNameAction} (via workflow-cps's internal, non-public `ParallelLabelAction`, which
 * this code deliberately never references directly — see the class-level rationale below).
 */
final class ParallelBranchGuard {

    private ParallelBranchGuard() {
    }

    /**
     * @return the branch's thread name if {@code flowNode} is enclosed in a parallel branch, else
     *         {@code null}. A {@code null} input (context not yet available, or step invoked in a
     *         context with no flow graph at all) is treated as "not inside a parallel branch" — fail
     *         open on this specific check only because the underlying condition (running inside a real
     *         Pipeline `parallel {}`) structurally cannot exist without a FlowNode also existing; a null
     *         FlowNode here means something else is unusual enough that this guard is not the right
     *         place to surface it.
     */
    static String enclosingParallelBranchName(FlowNode flowNode) {
        if (flowNode == null) {
            return null;
        }
        for (BlockStartNode enclosing : flowNode.iterateEnclosingBlocks()) {
            ThreadNameAction threadName = enclosing.getAction(ThreadNameAction.class);
            if (threadName != null) {
                return threadName.getThreadName();
            }
        }
        return null;
    }
}
```

**Version-pinning caveat the implementer must verify at build time (not resolvable by reading `pom.xml`
alone, since the version comes from the imported BOM):** `iterateEnclosingBlocks()` was added to
`workflow-api` later than that plugin's very first releases. Before relying on it, confirm the exact
`workflow-api` version the `bom-2.426.x` import resolves to (`mvn dependency:tree
-Dincludes=org.jenkins-ci.plugins.workflow:workflow-api`) actually has this method on its classpath; if the
resolved version predates it, fall back to `getEnclosingBlocks()` (functionally equivalent for this narrow,
low-frequency check — `setupConfigTemplate` runs at most a handful of times per build, never in a hot loop,
so the "walk the whole graph" cost this method is cautioned against is not a real concern here either way).

**(b) Does adding `FlowNode.class` to `getRequiredContext()` have side effects?** No. Per
`workflow-step-api`'s own `StepContext.get(Class)` javadoc, `FlowNode`/`FlowExecution` are listed as
ordinary, first-class "known context types" retrievable exactly the same way as `TaskListener`/`Run`/
`FilePath` already are in this codebase's existing two steps — declared in `getRequiredContext()`, then read
via `getContext().get(FlowNode.class)`. Nothing in that contract singles out `FlowNode` as behaving
differently from any context type this codebase already requests.

**(c) Is there a simpler, more recent `workflow-cps`-provided helper (e.g. `CpsThread`/`CpsThreadGroup`)
that supersedes the `FlowNode`-walking approach? — Explicitly rejected, on a stability-boundary
principle, not just "not simpler":** `CpsThread`/`CpsThreadGroup` live in `workflow-cps`, which is the
Groovy-CPS *execution engine* itself, not the stable step-authoring surface (`workflow-step-api` +
`workflow-api`) this plugin's steps are built against today — confirmed by this repo's own `pom.xml`
already keeping `workflow-cps` at `<scope>test</scope>` only, deliberately, everywhere in the main
codebase. Depending on `CpsThread` from main-scope code would (i) require promoting `workflow-cps` to a
main-scope compile dependency for the first time in this plugin's history, coupling every one of its steps
to one specific pipeline execution engine's internals instead of the engine-agnostic `Step`/`StepExecution`
contract they're written against today, and (ii) tie this detection to CPS specifically, when the actual,
documented, Jenkins-core-precedented mechanism (`ThreadNameAction` on a `FlowNode`) already lives at the
correct, more-public `workflow-api` layer and works regardless of which engine produced the flow graph.
**Recommendation: do not use `CpsThread`/`CpsThreadGroup` for this.**

**Concrete `pom.xml` consequence (the actual, previously-unstated blocker):** `FlowNode` and
`ThreadNameAction` are declared in package `org.jenkinsci.plugins.workflow.{graph,actions}`, both shipped by
the **`workflow-api`** plugin — which today is **absent from `pom.xml` at every scope**, not merely at test
scope like `workflow-cps`. This project's existing `workflow-step-api` main-scope dependency does **not**
pull `workflow-api` in transitively (confirmed by reading `workflow-step-api-plugin`'s own `pom.xml` and its
`StepContext.java` source — the javadoc mentions `FlowNode`/`FlowExecution` by name but the file imports
neither, i.e. zero compile-time coupling). **Required `pom.xml` change:**

```xml
<dependency>
  <groupId>org.jenkins-ci.plugins.workflow</groupId>
  <artifactId>workflow-api</artifactId>
  <!-- no <version> needed: already managed by the imported bom-2.426.x -->
</dependency>
```

This must be a normal (main/compile-scope) dependency, not test-scope — `SetupConfigTemplateStep`/
`ParallelBranchGuard` are main-source-tree classes that reference `FlowNode`/`ThreadNameAction` directly.

</decision-7-oq11>

<decision-8-fail-loud-messages>

## 8. Fail-loud message shapes — FR-84 and FR-94, matched to the *right* existing sibling convention

This codebase actually has two coexisting fail-loud message families today, not one — picking the wrong one
for a new message would be its own small inconsistency:

- **Family A — no `[configTemplateSync]` prefix**, used for "a specific referenced project/version could not
  be resolved" (`requireCommon`/`requireEnv`/`resolveEffective`'s `AbortException`s, and the substitute
  step's frozen-binding lookup). Shape: `"<Role> Config Set '<projectKey>'[, environment '<environment>']
  has no <what>[ to resolve][ (<context>)]"`.
- **Family B — `[configTemplateSync]` prefix**, used for step-level, whole-invocation fail conditions not
  about resolving one specific reference (`validateOrThrow`'s missing-keys message, `resolveEffective`'s
  mismatched-content-types message, the substitute step's "Substitution incomplete" message).

**FR-84 (missing explicit version) belongs to Family A** — it is exactly "a specific referenced
project/version could not be resolved," the same shape as `resolveEffective`'s existing missing-version
message, just for a differently-sourced reference (the `version` parameter, not a base-chain entry):

```
Env Config Set for projectKey 'apilealtad-env-qa', environment 'qa' has no version 12 to resolve (explicit 'version' parameter)
```
```
Common Config Set for projectKey 'apilealtad' has no version 12 to resolve (explicit 'version' parameter, useBase=true)
```

(Exact strings already given in §2's code.)

**FR-94/FR-95 belong to Family B** — both are step-level, whole-invocation fail conditions with no single
"referenced project/version" to name, matching `validateOrThrow`/`resolveEffective`'s mismatched-content-type
message's own shape and prefix convention:

```
[configTemplateSync] Missing required parameter(s) [projectKey, environment] — not supplied explicitly on this call, and no prior setupConfigTemplate() call in this build provided them.
```
```
[configTemplateSync] setupConfigTemplate() is not supported inside a parallel {} branch ('Branch: qa') — its build-scoped state would be ambiguous across concurrently-running branches. Call configTemplateValidate/configTemplateSubstitute with their own full explicit parameters inside parallel {} instead.
```

(Exact strings already given in §3b and §6's code — restated here together so the implementer can see both
families' exact boundary in one place, rather than piecing it together from separately-scattered decisions.)

</decision-8-fail-loud-messages>

<summary>

## Summary for handoff

| # | Decision |
|---|---|
| 1 | `useBase`/`version` are additive `@DataBoundSetter` fields on both steps, exactly as FR-79 specifies — but `useBase`'s *internal* field type must be boxed `Boolean` (not primitive), starting `null`, so FR-93's per-parameter precedence merge can tell "not supplied here" from "explicitly `false` here." Public contract stays "boolean, default false" via `isUseBase()`. |
| 2 | New `StepSupport.resolveUseBaseOnly(repository, projectKey, version)` reuses `ConfigSet.getVersion(int)`/`getActiveVersion()` directly — no new lookup method. FR-80's env-pin path needs no new method, just an `env.getVersion(version)` branch before the existing `effectiveBaseChain`/`resolveEffective` call. FR-83 needs zero code changes — `mergedSecretsManifest(resolved.resolvedBaseConfigSets, null)` already does what's asked. |
| 2b | **Real gap found:** `resolveEffective`'s content-type fallback hardcodes `ContentType.JSON` when the chain is empty — silently wrong once FR-87 makes an empty chain reachable. Fix: add an `env` (`ConfigSet`) parameter purely to supply the correct fallback (`env.getContentType()`). |
| 3a | **(Rewritten 2026-09-03, FR-96–FR-103.)** `buildVersion` removed entirely from `ConfigTemplateSubstituteStep`; new `redeployFromRun` (`String`, `@DataBoundSetter`) added — bare integer (same job) or `job#buildNumber` (cross-job), mirroring `Run#getExternalizableId()`'s own shape, single field not a two-field split. Three-way resolution in `Execution.run()`: (A) `version` supplied → FR-79–82 pinning, no binding lookup/save at all, `redeployFromRun` not consulted even if present (FR-101 already specifies this outcome — not an unaddressed gap); (B) `redeployFromRun` supplied (no `version`) → look up the TARGET Run's binding, replay its frozen chain if found (and still write a fresh binding under the CURRENT run's own identity, FR-102), or warn-and-live-fallback if not found (FR-103, non-blocking); (C) neither supplied → self-lookup by the current Run's own identity (restart-from-stage case), else live-resolve with no warning (FR-27), always writing/updating the own-Run binding (FR-97, default-on). `ConfigDeploymentBindingRepository.find`/`.save`'s `(projectKey, environment, buildVersion)` signature is **unchanged in shape** (confirmed — no rename to `runExternalizableId`, per the requirements doc's own "field/shape UNCHANGED" note); only the semantic content of the third argument changes. Pre-existing bindings keyed by an old hand-typed literal become permanently unreachable in practice — stated as an accepted, intentional consequence of FR-96's hard removal, not a silent gap. |
| 3b | FR-93: one shared `mergeWithSetupState(...)` method, per-parameter (never all-or-nothing) precedence, also the single fail-loud gate for FR-94. **Structural consequence:** both steps' constructors must become no-arg, with `projectKey`/`environment`/`file` demoted to `@DataBoundSetter` — required for FR-91's true zero-argument call to even be constructible; does not conflict with FR-79–FR-85's own "no breaking change" note (that note is scoped to the `useBase`/`version` addition alone). `ConfigTemplateValidateStep` also needs `Run.class` added to `getRequiredContext()`. |
| 4 | `explicitlyStandalone` is an additive primitive-`boolean` field/constructor overload on `ConfigSetVersion` — **simpler than `baseChain`'s migration mechanism, not a literal mirror of it**: a missing primitive field in old XML already deserializes to `false` with zero new `readResolve()` logic needed for this field. `effectiveBaseChain` gets one new early-return guard, checked before the existing empty/absent check. |
| 5 | `ConfigSet.addVersion` gets one new 6-arg overload taking `explicitlyStandalone`; both the FR-53 (non-empty `baseChain` on COMMON) and new FR-86 (`explicitlyStandalone` on COMMON) checks, plus a new FR-86/87 contradiction check (`explicitlyStandalone=true` + non-empty `baseChain`), live together in this one method as hard `IllegalArgumentException`s — never silent normalization. |
| 6 | New `SetupConfigTemplateStep`/`ConfigTemplateSetupAction` (package-private `InvisibleAction`). Deliberately `SynchronousStepExecution`, not `SynchronousNonBlockingStepExecution` (no I/O to hand off). Stores state via `run.addOrReplaceAction(...)`, not `addAction`, so a second same-build call replaces rather than duplicates. |
| 7 | **OQ-11 resolved:** `FlowNode.iterateEnclosingBlocks()` (lazy/cached, not `getEnclosingBlocks()`) checking each enclosing `BlockStartNode` for `getAction(ThreadNameAction.class) != null`. No side effects from adding `FlowNode.class` to `getRequiredContext()`. `CpsThread`/`workflow-cps`-based alternatives explicitly rejected (wrong stability boundary — engine-internal, not step-authoring API). **Concrete, previously-missed consequence: `pom.xml` needs a NEW main-scope `workflow-api` dependency** (`FlowNode`/`ThreadNameAction` live there, not in `workflow-cps`, and nothing currently pulls it in even transitively at main scope). |
| 8 | FR-84 follows the existing no-prefix "Family A" resolution-failure message shape (mirrors `resolveEffective`'s own missing-version wording); FR-94/FR-95 follow the existing `[configTemplateSync]`-prefixed "Family B" step-level-failure shape (mirrors `validateOrThrow`/mismatched-content-type messages). Exact strings given in §2/§3b/§6/§8. |

**Ready for implementation**, with items the implementer must additionally verify/decide at build time (not
blocking, all flagged with concrete fallbacks above): (i) confirm the BOM-resolved `workflow-api` version
actually has `iterateEnclosingBlocks()`, else fall back to `getEnclosingBlocks()` (§7); (ii) *(superseded —
FR-98's binding-save suppression on explicit `version` is now directly mandated by the requirements doc
itself, no longer a tech-lead interpretation pending confirmation; see §3a's rewrite)*; (iii)
`ConfigDeploymentBindingRepositoryTest`/`ConfigSetTest`/`ConfigSetVersionTest` need new fixtures covering: an
`explicitlyStandalone=true` version's effective-config computation, the FR-86/87 contradiction rejection,
`useBase=true` with and without a pinned `version`, `setupConfigTemplate` state round-tripping across a
same-build zero-argument call, and a `parallel {}`-branch JenkinsRule integration test asserting
`setupConfigTemplate` fails loud inside a branch but a fully-explicit `configTemplateValidate`/
`configTemplateSubstitute` call still succeeds there; **(iv, new)** §3a's rewritten resolution needs its own
fixtures: `redeployFromRun` bare-integer vs. cross-job `job#buildNumber` resolution, found-binding replay with
the FR-102 forward-chained own-Run write, not-found fallback with FR-103's warning text, `version` +
`redeployFromRun` supplied together asserting `redeployFromRun` is fully ignored, and a same-Run
restart-from-stage scenario asserting the FR-25 self-lookup finds its own prior binding. Suggested build
order, each layer green before the next: domain model (§4/§5) → `pom.xml` dependency + `ParallelBranchGuard`
(§7, isolated unit-testable with a hand-built `FlowNode` fixture, no full JenkinsRule needed for the pure
logic) → `StepSupport` additions (§2/§2b) → constructor/setter restructuring + `mergeWithSetupState` (§3b) on both
existing steps → `SetupConfigTemplateStep` (§6) → precedence wiring in `ConfigTemplateSubstituteStep` (§3a)
→ full JenkinsRule integration tests (including the `parallel {}` scenario) → real end-to-end Jenkinsfile
smoke covering every combination in FR-79–FR-95's own acceptance language.

</summary>
