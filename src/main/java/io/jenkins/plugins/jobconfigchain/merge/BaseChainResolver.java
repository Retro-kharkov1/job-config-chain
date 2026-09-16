package io.jenkins.plugins.jobconfigchain.merge;

import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetVersion;
import io.jenkins.plugins.jobconfigchain.model.PinMode;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves each {@link BaseConfigReference} in an ordered chain to its actual {@link ConfigSet}/
 * {@link ConfigSetVersion} pair (ACTIVE -> {@code getActiveVersion()}, PINNED ->
 * {@code getVersion(n)}). Shared by {@code steps.StepSupport} (fail-loud, wraps a missing reference as
 * an {@code AbortException}) and {@code ui.EnvConfigSetPage} (forgiving, wraps a missing reference as a
 * structured {@code ok:false} preview error) — the resolution RULE is identical in both contexts, only
 * the failure-handling shape differs, which is exactly why this class returns nulls instead of
 * throwing: it lets each caller decide how to fail.
 */
public final class BaseChainResolver {

    private BaseChainResolver() {
    }

    public static final class ResolvedReference {
        public final BaseConfigReference reference;
        public final ConfigSet configSet;       // null if no such project's common Config Set exists
        public final ConfigSetVersion version;   // null if configSet is null, or the active/pinned version is missing

        ResolvedReference(BaseConfigReference reference, ConfigSet configSet, ConfigSetVersion version) {
            this.reference = reference;
            this.configSet = configSet;
            this.version = version;
        }
    }

    /** Never throws. Callers inspect each entry's {@code configSet}/{@code version} for null. */
    public static List<ResolvedReference> resolve(ConfigSetRepository repository, List<BaseConfigReference> chain) {
        List<ResolvedReference> out = new ArrayList<>();
        for (BaseConfigReference ref : chain) {
            ConfigSet configSet = repository.findCommon(ref.getProjectKey());
            ConfigSetVersion version = configSet == null ? null
                    : ref.getPinMode() == PinMode.PINNED
                        ? configSet.getVersion(ref.getPinnedVersionNumber())
                        : configSet.getActiveVersion();
            out.add(new ResolvedReference(ref, configSet, version));
        }
        return out;
    }
}
