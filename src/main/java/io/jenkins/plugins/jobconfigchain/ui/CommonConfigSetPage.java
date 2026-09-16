package io.jenkins.plugins.jobconfigchain.ui;

import io.jenkins.plugins.jobconfigchain.merge.TemplateGenerator;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.bind.JavaScriptMethod;

/**
 * The global Config Set edit page (see admin-ui.md's "Global level" section), served directly at
 * {@code /configTemplates/<projectKey>/} — this IS the common Config Set editor, not a separate
 * page one URL segment deeper (owner decision, 2026-09-14: the {@code /common} URL segment and the
 * near-empty hub page that used to precede it are both retired).
 */
public class CommonConfigSetPage extends ConfigSetPage {

    CommonConfigSetPage(String projectKey, ConfigSetRepository repository) {
        super(projectKey, repository);
    }

    @Override
    ConfigSetRole getRole() {
        return ConfigSetRole.COMMON;
    }

    @Override
    String getEnvironment() {
        return null;
    }

    @Override
    String getDisplayNameSeed() {
        return projectKey + " (common)";
    }

    /**
     * The {@code -common} title suffix is retired (owner decision, 2026-09-14): this page is now
     * the ONLY page reachable under {@code /configTemplates/<projectKey>/}, so there is nothing
     * left to disambiguate from — mirrors {@link ConfigTemplatesJobAction#getDisplayName()} already
     * carrying no role suffix of its own.
     */
    public String getDisplayName() {
        return projectKey;
    }

    // ---- Template generation (see pipeline-steps.md's "Template generation" section and
    // admin-ui.md's "Template generation (UI action)" section) ----
    // Own copy, unchanged from the pre-2026-09-12 shared ConfigSetPage#doRenderTemplate/
    // #jsGenerateTemplate/#isTemplateAvailable — this page has no override/base-chain draft
    // concept at all (its own single editor IS the committed content), so "generate from the
    // current unsaved draft" (the new EnvConfigSetPage/ConfigTemplatesJobAction contract) has no
    // meaning here; the original "ACTIVE version(s) only, never a draft" guarantee is
    // deliberately kept as-is, including the disabled-button gating on {@link #isExists()}.

    /**
     * Jelly-visible: whether the "Generate Template" action should render enabled (disabled with a
     * "no active version yet" label when there is nothing to template).
     */
    public boolean isTemplateAvailable() {
        return getActiveVersion() != null;
    }

    @jenkins.security.stapler.StaplerDispatchable
    public JSONObject doRenderTemplate() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return renderTemplateImpl();
    }

    @JavaScriptMethod(name = "generateTemplate")
    public JSONObject jsGenerateTemplate() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return renderTemplateImpl();
    }

    private JSONObject renderTemplateImpl() {
        JSONObject result = new JSONObject();
        ConfigSetVersion active = getActiveVersion();
        if (active == null) {
            result.put("ok", false);
            result.put("error", "No active version yet — nothing to template.");
            return result;
        }
        ContentType type = getConfigSet().getContentType();
        TreeNode template = TemplateGenerator.fromContent(active.getContentJson(), type);
        result.put("ok", true);
        result.put("contentType", type.name());
        result.put("template", TreeFormats.forType(type).serialize(template));
        return result;
    }
}
