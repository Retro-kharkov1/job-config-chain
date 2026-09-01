package io.github.retrokharkov1.configtemplatesync.ui;

import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole;
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
}
