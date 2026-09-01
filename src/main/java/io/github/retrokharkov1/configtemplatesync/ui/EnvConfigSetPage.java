package io.github.retrokharkov1.configtemplatesync.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.github.retrokharkov1.configtemplatesync.merge.EffectiveConfigResolver;
import io.github.retrokharkov1.configtemplatesync.merge.TemplateGenerator;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.bind.JavaScriptMethod;

/**
 * The env-level three-panel merge admin page (FR-34–FR-39) at
 * {@code /configTemplates/project/<projectKey>/env/<environment>}.
 *
 * <p>The picker binding this env layer to its paired common Config Set is the {@code projectKey}
 * itself (already structural per OQ-2/FR-1) — locked/immutable once the env Config Set has been
 * created for the first time (tech-lead Finding 4: the picker is set once at creation, never a
 * second, independent re-pairing mechanism).</p>
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

    /** Read-only left panel content (FR-36): the paired common Config Set's active version. */
    public ConfigSet getCommonConfigSet() {
        return repository.findCommon(projectKey);
    }

    public ConfigSetVersion getCommonActiveVersion() {
        ConfigSet common = getCommonConfigSet();
        return common == null ? null : common.getActiveVersion();
    }

    /**
     * {@link #getCommonActiveVersion()}'s {@code contentJson} (or {@code "{}"} if none), pre-encoded
     * into a ready-to-embed JS string literal for the read-only "Global" Monaco editor seed — see
     * {@link ConfigSetPage#toJsScriptStringLiteral(String)} for why this must not be built via
     * {@code h.jsStringEscape} + hand-written surrounding quotes.
     */
    public String getCommonActiveVersionContentJsonForScript() {
        ConfigSetVersion commonActive = getCommonActiveVersion();
        return toJsScriptStringLiteral(commonActive == null ? "{}" : commonActive.getContentJson());
    }

    /** Whether the common-Config-Set picker must render locked (FR-35: immutable after creation). */
    public boolean isCommonPickerLocked() {
        return isExists();
    }

    /**
     * AJAX (OQ-9, resolved server-side): recomputes the live "Merged result" panel (FR-38) by
     * calling the exact same {@link EffectiveConfigResolver} the pipeline steps use — never a
     * second, divergent merge implementation (FR-16). Never throws on invalid input JSON; instead
     * returns a structured {@code ok:false} result so the caller can retain its last-known-good
     * render (Finding 1 of the tech-lead review) instead of blanking the panel.
     *
     * <p>Named {@code doComputeMerge} (URL segment {@code computeMerge}), deliberately NOT
     * {@code doPreviewMerge}, to avoid colliding with the
     * {@code @JavaScriptMethod(name = "previewMerge")} sibling below — see
     * {@link ConfigSetPage#doActivateVersion(int)}'s javadoc for the full root-cause chain: this
     * exact collision was the one that silently made {@code previewMerge} look like it "worked" in
     * earlier manual checks, when in fact the classic method (this one) was intercepting every
     * JS-proxy call and always merging against a {@code null}/default overlay instead of the real
     * live content the browser sent.</p>
     */
    public JSONObject doComputeMerge(@QueryParameter String overlayJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return previewMergeImpl(overlayJson);
    }

    /**
     * JS-proxy-facing sibling of {@link #doComputeMerge}; exposed as {@code proxy.previewMerge(...)}.
     * See {@link ConfigSetPage#doActivateVersion(int)} javadoc.
     */
    @JavaScriptMethod(name = "previewMerge")
    public JSONObject jsPreviewMerge(String overlayJson) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return previewMergeImpl(overlayJson);
    }

    private JSONObject previewMergeImpl(String overlayJson) {
        JSONObject result = new JSONObject();
        ConfigSetVersion commonActive = getCommonActiveVersion();
        String commonJson = commonActive == null ? "{}" : commonActive.getContentJson();
        try {
            JsonElement parsedOverlay = JsonParser.parseString(overlayJson == null ? "{}" : overlayJson);
            if (!parsedOverlay.isJsonObject()) {
                result.put("ok", false);
                result.put("error", "Override content must be a JSON object");
                return result;
            }
            JsonObject merged = EffectiveConfigResolver.resolve(commonJson, overlayJson);
            result.put("ok", true);
            result.put("merged", net.sf.json.JSONObject.fromObject(merged.toString()));
            return result;
        } catch (JsonSyntaxException | IllegalStateException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * FR-15b: resolves the effective (merged) configuration from the two Config Sets' ACTIVE
     * versions (never the live 3-panel "Merged result" panel's in-progress draft, per FR-16/FR-41)
     * and tokenizes it. Both versions are re-fetched fresh from {@link #repository} via
     * {@link #getCommonActiveVersion()}/{@link #getActiveVersion()} — no code path here touches
     * anything Monaco-editor-supplied.
     */
    @Override
    com.google.gson.JsonObject computeTemplate() {
        ConfigSetVersion commonActive = getCommonActiveVersion();
        ConfigSetVersion envActive = getActiveVersion();
        String commonJson = commonActive.getContentJson();
        String envJson = envActive == null ? null : envActive.getContentJson();
        return TemplateGenerator.fromEffective(commonJson, envJson);
    }

    /**
     * Only the COMMON side gates availability — an env layer with no active version yet is a
     * legitimate empty overlay ({@link EffectiveConfigResolver} already treats a null/blank
     * {@code envPatchJson} as {@code {}}), so the "disabled until something to template" guard from
     * FR-40/41 is keyed on the COMMON Config Set's active version, not the env one.
     */
    @Override
    ConfigSetVersion getActiveVersionForTemplate() {
        return getCommonActiveVersion();
    }
}
