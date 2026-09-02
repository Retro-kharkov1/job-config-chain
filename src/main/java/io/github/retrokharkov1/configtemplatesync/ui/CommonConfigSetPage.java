package io.github.retrokharkov1.configtemplatesync.ui;

import io.github.retrokharkov1.configtemplatesync.merge.TemplateGenerator;
import io.github.retrokharkov1.configtemplatesync.merge.tree.TreeNode;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetVersion;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository;

/**
 * The global Config Set edit page (FR-30/31/32/33) at
 * {@code /configTemplates/project/<projectKey>/common}.
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

    public String getDisplayName() {
        return projectKey + "-common";
    }

    /** FR-15a: walks this Config Set's own active version content directly, no merge. */
    @Override
    TreeNode computeTemplate() {
        ConfigSetVersion active = getActiveVersion();
        return TemplateGenerator.fromContent(active.getContentJson(), getConfigSet().getContentType());
    }

    @Override
    ContentType getTemplateContentType() {
        return getConfigSet().getContentType();
    }

    @Override
    ConfigSetVersion getActiveVersionForTemplate() {
        return getActiveVersion();
    }
}
