package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.Extension;
import hudson.model.RootAction;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerProxy;

import java.util.List;

/**
 * The global "Config Templates" admin screen (FR-30), reachable at {@code /configTemplates},
 * modeled on Jenkins' own Manage Jenkins &#8594; Managed Files list/edit interaction shape (see
 * "UI reference groundings" in the requirements spec). Lists every common Config Set and dispatches
 * to a per-project {@link ProjectConfigPage} at {@code /configTemplates/<projectKey>/} via
 * Stapler's {@code getDynamic(String)} catch-all hook.
 *
 * <p><b>Why {@code getDynamic}, not {@code getProject(String)}:</b> a named getter with a
 * {@code String} parameter is exactly the pattern Jenkins core's post-SECURITY-595 Stapler
 * routing-decision filter blocks by default for any class not already on its whitelist (confirmed
 * empirically: {@code getProject(String)} was rejected with a 404 and a
 * "add to the whitelist" warning during test-writing for this milestone) — that whitelist is an
 * admin-side runtime file (`stapler-whitelist.txt`), not something a plugin can ship pre-approved.
 * {@code getDynamic(String)} is Stapler's dedicated, explicitly-opted-into wildcard-dispatch
 * extension point (distinct from the implicit getter-name-matches-URL-token convention that filter
 * targets) and is unaffected by it — the standard pattern for "the remaining path segment is a
 * caller-supplied key, not a fixed property name" (e.g. `ItemGroup#getItem(String)`-style folder
 * navigation elsewhere in Jenkins core uses the same catch-all shape).</p>
 */
@Extension
public class ConfigTemplatesRootAction implements RootAction, StaplerProxy {

    public static final String URL_NAME = "configTemplates";

    private final ConfigSetRepository repository;

    public ConfigTemplatesRootAction() {
        this(new ConfigSetRepository());
    }

    ConfigTemplatesRootAction(ConfigSetRepository repository) {
        this.repository = repository;
    }

    @Override
    public String getIconFileName() {
        // only shown to users who can see it; icon resolution mirrors Jenkins core's Managed Files link
        return "symbol-document-text-outline-plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "Config Templates";
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    /** Permission gate: this whole section is admin-only, same as Manage Jenkins itself. */
    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return this;
    }

    public List<io.github.retrokharkov1.configtemplatesync.model.ConfigSet> getCommonConfigSets() {
        return repository.listAllCommon();
    }

    /** Stapler catch-all dispatch: {@code /configTemplates/<projectKey>/...} (FR-30/FR-34 grouping). */
    public Object getDynamic(String projectKey) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return new ProjectConfigPage(projectKey, repository);
    }

    ConfigSetRepository getRepository() {
        return repository;
    }
}
