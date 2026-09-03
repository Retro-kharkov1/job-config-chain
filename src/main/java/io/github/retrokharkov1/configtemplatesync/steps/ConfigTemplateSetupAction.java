package io.github.retrokharkov1.configtemplatesync.steps;

import hudson.model.InvisibleAction;

import java.io.Serializable;

/**
 * Build-scoped storage for {@code setupConfigTemplate}'s parameters (FR-90). Attached to the
 * {@link hudson.model.Run} via {@code addOrReplaceAction} (never plain {@code addAction} — see
 * {@link SetupConfigTemplateStep.Execution#run()}), so a same-build, zero/partial-argument
 * {@code configTemplateValidate()}/{@code configTemplateSubstitute()} call can read back whichever
 * parameters it did not itself explicitly supply (FR-91/FR-93). {@code InvisibleAction} — this state
 * is a pipeline-internal implementation detail, never rendered on the build's own UI page.
 */
final class ConfigTemplateSetupAction extends InvisibleAction implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String projectKey;
    private final String environment;
    private final String file;
    private final String redeployFromRun;
    private final boolean useBase;
    private final Integer version;

    ConfigTemplateSetupAction(String projectKey, String environment, String file, String redeployFromRun,
                               boolean useBase, Integer version) {
        this.projectKey = projectKey;
        this.environment = environment;
        this.file = file;
        this.redeployFromRun = redeployFromRun;
        this.useBase = useBase;
        this.version = version;
    }

    String getProjectKey() {
        return projectKey;
    }

    String getEnvironment() {
        return environment;
    }

    String getFile() {
        return file;
    }

    String getRedeployFromRun() {
        return redeployFromRun;
    }

    boolean isUseBase() {
        return useBase;
    }

    Integer getVersion() {
        return version;
    }
}
