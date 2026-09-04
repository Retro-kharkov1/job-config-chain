package io.github.retrokharkov1.configtemplatesync.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.retrokharkov1.configtemplatesync.merge.BaseChainResolver;
import io.github.retrokharkov1.configtemplatesync.merge.EffectiveConfigResolver;
import io.github.retrokharkov1.configtemplatesync.merge.TemplateGenerator;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeFormat;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeFormats;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeNode;
import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The env-level Config Set admin page (FR-34–FR-39, FR-51–FR-58) at
 * {@code /configTemplates/project/<projectKey>/env/<environment>}.
 *
 * <p>The picker binding this env layer to its paired common Config Set is the {@code projectKey}
 * itself (already structural per OQ-2/FR-1) — locked/immutable once the env Config Set has been
 * created for the first time (tech-lead Finding 4: the picker is set once at creation, never a
 * second, independent re-pairing mechanism).</p>
 *
 * <p>The old single-locked-picker "Overrides Common Config Set" widget (backed by
 * {@code getCommonConfigSet}/{@code getCommonActiveVersion}/
 * {@code getCommonActiveVersionContentJsonForScript}/{@code isCommonPickerLocked}) is replaced
 * wholesale by the multi-base chain editor (FR-55, tech-lead §10: hard-removed, page-internal UI
 * glue with no external contract — not deprecated).</p>
 */
public class EnvConfigSetPage extends ConfigSetPage {

    private final String environment;

    EnvConfigSetPage(String projectKey, String environment, ConfigSetRepository repository) {
        super(projectKey, repository);
        this.environment = environment;
    }

    @Override
    ConfigSetRole getRole() {
        return ConfigSetRole.ENV;
    }

    @Override
    String getEnvironment() {
        return environment;
    }

    @Override
    String getDisplayNameSeed() {
        return projectKey + " (" + environment + ")";
    }

    public String getDisplayName() {
        return projectKey + "-" + environment;
    }

    /**
     * Project picker source for the base-chain editor's per-row project select (wireframe Page 2):
     * every project that currently has a COMMON-role Config Set, across all projects — not just this
     * env's own {@link #projectKey} — since a chain entry may reference any project's common set.
     */
    public List<String> getAvailableCommonProjectKeys() {
        List<String> keys = new ArrayList<>();
        for (ConfigSet common : repository.listAllCommon()) {
            keys.add(common.getProjectKey());
        }
        return keys;
    }

    /**
     * {@link #getAvailableCommonProjectKeys()}, pre-encoded into a ready-to-embed JS string literal
     * for the base-chain editor's per-row project {@code &lt;select&gt;} (same
     * {@code toJsScriptStringLiteral} + client-side {@code JSON.parse} seed-data convention as
     * {@link ConfigSetPage#getEditorSeedJsonForScript()}).
     */
    public String getAvailableCommonProjectKeysJsonForScript() {
        JsonArray array = new JsonArray();
        for (String key : getAvailableCommonProjectKeys()) {
            array.add(key);
        }
        return toJsScriptStringLiteral(array.toString());
    }

    /**
     * The base-chain editor's initial draft rows (FR-51/FR-55/FR-56), pre-encoded as a JS string
     * literal of a JSON array (same {@code toJsScriptStringLiteral} + client-side {@code JSON.parse}
     * pattern as {@link ConfigSetPage#getEditorSeedJsonForScript()}). Seeded from the current ACTIVE
     * version's declared chain when non-empty, otherwise a single default row — this env's own
     * {@link #projectKey}, ACTIVE (FR-56: a brand-new env Config Set's chain editor defaults to
     * exactly one row).
     */
    public String getBaseChainSeedJsonForScript() {
        List<BaseConfigReference> chain = effectiveBaseChainForTemplate();
        JsonArray array = new JsonArray();
        for (BaseConfigReference ref : chain) {
            JsonObject row = new JsonObject();
            row.addProperty("projectKey", ref.getProjectKey());
            row.addProperty("pinMode", ref.getPinMode().name());
            row.addProperty("pinnedVersionNumber", ref.getPinnedVersionNumber());
            array.add(row);
        }
        return toJsScriptStringLiteral(array.toString());
    }

    /**
     * Every COMMON-role Config Set's own version list (number/note/timestamp), keyed by projectKey,
     * pre-rendered as a JS string literal of one JSON blob at page-render time — matches this page's
     * existing "seed data via inline script, no extra AJAX for static-per-render data" convention
     * (tech-lead §9), so the base-chain editor's per-row "Pin to version" picker can populate
     * instantly for whichever project a row currently has selected, without a round trip.
     */
    public String getCommonVersionCatalogJsonForScript() {
        JsonObject catalog = new JsonObject();
        JsonObject typesByProject = new JsonObject(); // FR-72: sibling map, same key set as catalog
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
            typesByProject.addProperty(common.getProjectKey(), common.getContentType().name());
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("versionsByProject", catalog);
        wrapper.add("typeByProject", typesByProject);
        return toJsScriptStringLiteral(wrapper.toString());
    }

    /**
     * FR-56's default chain: this env's own {@code projectKey}, ACTIVE — used both to seed a
     * brand-new env Config Set's chain editor with exactly one row, and (duplicated here rather than
     * reaching into {@code steps.StepSupport}'s package-private {@code effectiveBaseChain}, per the
     * tech-lead review's explicit preference for keeping {@code ui} decoupled from {@code steps})
     * as the resolution default for {@link #computeTemplate()} below.
     */
    private List<BaseConfigReference> effectiveBaseChainForTemplate() {
        ConfigSetVersion active = getActiveVersion();
        if (active != null) {
            if (active.isExplicitlyStandalone()) {
                // FR-87, mirrored from StepSupport#effectiveBaseChain — checked first, same
                // precedence reasoning as that method's own javadoc (a version cannot simultaneously
                // be explicitly-standalone and carry a non-empty baseChain, enforced at write time by
                // ConfigSet#addVersion).
                return Collections.emptyList();
            }
            List<BaseConfigReference> declared = active.getBaseChain();
            if (declared != null && !declared.isEmpty()) {
                return declared;
            }
        }
        return Collections.singletonList(BaseConfigReference.active(projectKey));
    }

    /**
     * FR-89: seeds the explicitlyStandalone checkbox from the current ACTIVE version's own flag —
     * {@code false} for a brand-new Config Set (no active version) or any version that never set it
     * (FR-88).
     */
    public boolean isExplicitlyStandaloneSeed() {
        ConfigSetVersion active = getActiveVersion();
        return active != null && active.isExplicitlyStandalone();
    }

    /**
     * AJAX (OQ-9, resolved server-side): recomputes the live "Merged result" panel (FR-38) by
     * calling the exact same {@link EffectiveConfigResolver} the pipeline steps use — never a
     * second, divergent merge implementation (FR-16). Never throws on invalid input JSON or an
     * unresolvable base-chain reference; instead returns a structured {@code ok:false} result so the
     * caller can retain its last-known-good render (Finding 1 of the tech-lead review) instead of
     * blanking the panel.
     *
     * <p>Named {@code doComputeMerge} (URL segment {@code computeMerge}), deliberately NOT
     * {@code doPreviewMerge}, to avoid colliding with the
     * {@code @JavaScriptMethod(name = "previewMerge")} sibling below — see
     * {@link ConfigSetPage#doActivateVersion(int)}'s javadoc for the full root-cause chain: this
     * exact collision was the one that silently made {@code previewMerge} look like it "worked" in
     * earlier manual checks, when in fact the classic method (this one) was intercepting every
     * JS-proxy call and always merging against a {@code null}/default overlay instead of the real
     * live content the browser sent.</p>
     *
     * <p><b>{@code standaloneContentType} (FR-104, added 2026-09-03):</b> the env-override editor's
     * own content-type picker selection — meaningful ONLY as a fallback when the resolved chain is
     * genuinely empty (i.e. {@code explicitlyStandalone} with zero base-chain rows), which is the one
     * case FR-60's "always resolve type from the chain" rule has nothing to resolve from. Ignored
     * whenever the chain resolves to ≥1 entry — that case's type always comes from the chain, exactly
     * as before this parameter existed.</p>
     */
    public JSONObject doComputeMerge(@QueryParameter String overlayJson, @QueryParameter String baseChainJson,
                                      @QueryParameter boolean explicitlyStandalone,
                                      @QueryParameter(fixEmpty = true) String standaloneContentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return previewMergeImpl(overlayJson, baseChainJson, explicitlyStandalone, standaloneContentType);
    }

    /**
     * JS-proxy-facing sibling of {@link #doComputeMerge}; exposed as {@code proxy.previewMerge(...)}.
     * See {@link ConfigSetPage#doActivateVersion(int)} javadoc.
     */
    @JavaScriptMethod(name = "previewMerge")
    public JSONObject jsPreviewMerge(String overlayJson, String baseChainJson, boolean explicitlyStandalone,
                                      String standaloneContentType) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return previewMergeImpl(overlayJson, baseChainJson, explicitlyStandalone, standaloneContentType);
    }

    private JSONObject previewMergeImpl(String overlayRaw, String baseChainJson, boolean explicitlyStandalone,
                                         String standaloneContentType) {
        JSONObject result = new JSONObject();

        List<BaseConfigReference> chain = tryParseBaseChain(baseChainJson);
        if (chain == null) {
            result.put("ok", false);
            result.put("error", "Malformed base chain");
            return result;
        }
        if (chain.isEmpty()) {
            if (!explicitlyStandalone) {
                // FR-52's default — unchanged behavior for the ordinary "empty chain, not standalone" case.
                chain = Collections.singletonList(BaseConfigReference.active(projectKey));
            }
            // else FR-87: leave chain empty — BaseChainResolver.resolve(repository, emptyList())
            // already returns an empty resolved list with no special-casing needed, mirroring
            // StepSupport#effectiveBaseChain's own guard for the UI's decoupled-from-steps code path.
        }

        List<BaseChainResolver.ResolvedReference> resolved = BaseChainResolver.resolve(repository, chain);
        Set<ContentType> distinctTypes = EnumSet.noneOf(ContentType.class);
        List<String> typeReport = new ArrayList<>();
        for (BaseChainResolver.ResolvedReference r : resolved) {
            if (r.configSet == null || r.version == null) {
                result.put("ok", false);
                result.put("error", "Cannot resolve base chain entry '" + r.reference.getProjectKey()
                        + "' (" + r.reference.getPinMode() + ") — "
                        + (r.configSet == null ? "no such common Config Set" : "no such version"));
                return result;
            }
            distinctTypes.add(r.configSet.getContentType());
            typeReport.add(r.reference.getProjectKey() + " (" + r.configSet.getContentType() + ")");
        }
        if (distinctTypes.size() > 1) {
            // FR-61 surfaced through the SAME live-preview envelope (ok:false), not a page reload —
            // the env override panel's debounced recompute keeps its "last valid + non-blocking
            // indicator" behavior unchanged; this ok:false just means the indicator persists until the
            // operator fixes the chain, exactly like any other unresolvable-chain error already does.
            result.put("ok", false);
            result.put("error", "Mismatched content types in base chain — " + String.join(", ", typeReport)
                    + " must all share one content type.");
            return result;
        }
        // FR-104: a genuinely empty resolved chain only ever occurs when explicitlyStandalone is
        // true (a non-standalone empty draft chain is substituted with the FR-52 default above, so
        // `resolved` is never empty for that case) — there is no chain to resolve a ContentType from,
        // so fall back to the client's own content-type picker selection instead of hardcoding JSON.
        ContentType type = !distinctTypes.isEmpty()
                ? distinctTypes.iterator().next()
                : parseContentTypeOrDefault(standaloneContentType, ContentType.JSON);
        TreeFormat format = TreeFormats.forType(type);

        TreeNode overlay;
        try {
            overlay = format.parse(overlayRaw == null ? format.serialize(format.emptyObject()) : overlayRaw);
            if (!overlay.isObject()) {
                result.put("ok", false);
                result.put("error", "Override content must be a " + type + " object/root element");
                return result;
            }
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }

        List<TreeNode> baseContents = new ArrayList<>();
        JsonArray perReference = new JsonArray();
        for (BaseChainResolver.ResolvedReference r : resolved) {
            TreeNode baseNode = format.parse(r.version.getContentJson());
            baseContents.add(baseNode);
            JsonObject entry = new JsonObject();
            entry.addProperty("projectKey", r.reference.getProjectKey());
            entry.addProperty("pinMode", r.reference.getPinMode().name());
            entry.addProperty("resolvedVersionNumber", r.version.getVersionNumber());
            entry.addProperty("contentJson", format.serialize(baseNode)); // now type-serialized text, not raw JSON
            perReference.add(entry);
        }

        try {
            // FR-73: "Merged bases" is the fold of the chain BEFORE the env override is applied —
            // reuses the SAME EffectiveConfigResolver.resolveChain call with a null overlay, no
            // second/divergent merge implementation.
            TreeNode mergedBasesOnly = EffectiveConfigResolver.resolveChain(type, baseContents, null);
            TreeNode merged = EffectiveConfigResolver.resolveChain(type, baseContents, overlayRaw);
            result.put("ok", true);
            result.put("contentType", type.name()); // NEW field — client needs this to pick Monaco's language mode
            result.put("mergedBases", format.serialize(mergedBasesOnly)); // NEW field (FR-73)
            result.put("merged", format.serialize(merged)); // plain string now, not a nested JSON object
            result.put("perReference", net.sf.json.JSONArray.fromObject(perReference.toString()));
            return result;
        } catch (RuntimeException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * FR-104: parses a client-supplied {@link ContentType} name defensively — used only for the
     * standalone-with-empty-chain fallback in {@link #previewMergeImpl}, where the value comes from
     * the env-override editor's own content-type picker rather than a resolved base chain. Never
     * throws (a malformed/absent value degrades to {@code fallback}) since this is a live-preview
     * convenience, not a save-time contract — {@link ConfigSetPage#saveImpl} independently validates
     * the same picker's submitted value at save time via {@link ContentType#valueOf(String)}, same
     * convention as {@link CommonConfigSetPage}'s own picker.
     */
    private static ContentType parseContentTypeOrDefault(String raw, ContentType fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return ContentType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * FR-15b/FR-8: resolves the effective (merged) configuration from the env's ACTIVE version's
     * FROZEN base chain (never the live 3-panel "Merged result" panel's in-progress draft, per
     * FR-16/FR-41) and tokenizes it. {@link #getVersion} for template generation must use the
     * ACTIVE env version specifically (FR-16), never a draft.
     */
    @Override
    TreeNode computeTemplate() {
        ConfigSetVersion envActive = getActiveVersion();
        List<BaseChainResolver.ResolvedReference> resolved = resolveTemplateChainOrFail();
        ContentType type = resolveTemplateContentType(resolved);
        List<TreeNode> baseContents = new ArrayList<>();
        for (BaseChainResolver.ResolvedReference r : resolved) {
            baseContents.add(TreeFormats.forType(type).parse(r.version.getContentJson()));
        }
        String envRaw = envActive == null ? null : envActive.getContentJson();
        TreeNode effective = EffectiveConfigResolver.resolveChain(type, baseContents, envRaw);
        return TemplateGenerator.fromEffective(effective, type);
    }

    @Override
    ContentType getTemplateContentType() {
        return resolveTemplateContentType(resolveTemplateChainOrFail());
    }

    private List<BaseChainResolver.ResolvedReference> resolveTemplateChainOrFail() {
        List<BaseConfigReference> chain = effectiveBaseChainForTemplate();
        List<BaseChainResolver.ResolvedReference> resolved = BaseChainResolver.resolve(repository, chain);
        for (BaseChainResolver.ResolvedReference r : resolved) {
            if (r.configSet == null || r.version == null) {
                throw new IllegalStateException("Cannot resolve base chain entry '"
                        + r.reference.getProjectKey() + "' for template generation");
            }
        }
        return resolved;
    }

    /**
     * FR-62: mismatched types across the resolved chain are already rejected at save time (§4a/§4b)
     * and at live-preview time (previewMergeImpl above) — template generation reads an
     * already-frozen, already-consistent chain (FR-51), so this simply picks the first resolved
     * base's type rather than re-running the full mismatch check a third time.
     */
    private static ContentType resolveTemplateContentType(List<BaseChainResolver.ResolvedReference> resolved) {
        return resolved.isEmpty() ? ContentType.JSON : resolved.get(0).configSet.getContentType();
    }

    /**
     * Placeholder non-null value returned by {@link #getActiveVersionForTemplate()} when the env
     * layer itself has no active version yet but the effective base chain still fully resolves — an
     * env layer with no active version is a legitimate empty overlay (FR-15b/FR-41, unchanged from
     * the pre-multi-base-chain contract: only the base side gates availability, never the env side).
     * Never fed into {@link #computeTemplate()}'s actual generation — that method independently
     * re-fetches {@link #getActiveVersion()} itself — this exists purely so
     * {@link ConfigSetPage#isTemplateAvailable()}'s {@code != null} check has something non-null to
     * see without inventing a second, divergent "is available" boolean accessor.
     */
    private static final ConfigSetVersion CHAIN_RESOLVED_SENTINEL =
            new ConfigSetVersion(0, "{}", "internal sentinel — chain resolves, no env active version yet", "n/a", 0L);

    /**
     * Gating condition for FR-40/41's "disabled when nothing to template" contract, reworked for the
     * multi-base chain: previously keyed on the (now-removed) single common picker's active version;
     * only the BASE side gates availability, matching the pre-existing contract that an env layer
     * with no active version of its own is a legitimate empty overlay. {@link
     * ConfigSetPage#isTemplateAvailable()} (final on the base class) simply checks this for
     * {@code null}, so the full "does the effective base chain resolve to at least one real
     * active/pinned version" check must live here rather than in a same-named override.
     */
    @Override
    ConfigSetVersion getActiveVersionForTemplate() {
        List<BaseConfigReference> chain = effectiveBaseChainForTemplate();
        for (BaseChainResolver.ResolvedReference r : BaseChainResolver.resolve(repository, chain)) {
            if (r.configSet == null || r.version == null) {
                return null;
            }
        }
        ConfigSetVersion envActive = getActiveVersion();
        return envActive != null ? envActive : CHAIN_RESOLVED_SENTINEL;
    }
}
