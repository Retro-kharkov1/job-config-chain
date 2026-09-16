package io.jenkins.plugins.jobconfigchain.steps;

import hudson.model.InvisibleAction;

import java.io.Serializable;

/**
 * Build-scoped storage for {@code setupConfigTemplate}'s parameters (see pipeline-steps.md's
 * "setupConfigTemplate build-scoped convenience step" section). Attached to the
 * {@link hudson.model.Run} via {@code addOrReplaceAction} (never plain {@code addAction} — see
 * {@link SetupConfigTemplateStep.Execution#run()}), so a same-build, zero/partial-argument
 * {@code configTemplateValidate()}/{@code configTemplateSubstitute()} call can read back whichever
 * parameters it did not itself explicitly supply. {@code InvisibleAction} — this state
 * is a pipeline-internal implementation detail, never rendered on the build's own UI page.
 *
 * <p><b>Parameter shape (2026-09-14):</b> {@code projectKey}/{@code environment} are gone — every
 * call resolves against the calling Job's own attached local config by construction (see
 * pipeline-steps.md's "Pipeline call resolution — the final parameter model"). {@code configKey} is
 * meaningful only together with {@code useBase: true} (matrix rows 6/7).</p>
 */
final class ConfigTemplateSetupAction extends InvisibleAction implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String file;
    private final String redeployFromRun;
    private final boolean useBase;
    private final String configKey;
    private final Integer version;

    ConfigTemplateSetupAction(String file, String redeployFromRun, boolean useBase, String configKey,
                               Integer version) {
        this.file = file;
        this.redeployFromRun = redeployFromRun;
        this.useBase = useBase;
        this.configKey = configKey;
        this.version = version;
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

    String getConfigKey() {
        return configKey;
    }

    Integer getVersion() {
        return version;
    }
}
