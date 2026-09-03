package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;

import java.util.Collection;
import java.util.Collections;

/**
 * Contributes a {@link ConfigTemplatesJobAction} sidebar link to every {@link Job}'s page
 * ({@code /job/<name>/}), so the plugin is discoverable from any job without needing to already
 * know about {@code /manage/}.
 *
 * <p>Extension point: {@link jenkins.model.TransientActionFactory}
 * (https://javadoc.jenkins.io/jenkins/model/TransientActionFactory.html) — "Allows you to add
 * actions to any kind of object at once." This is the correct core mechanism for contributing an
 * action to every instance of a given {@code Actionable} type (here, every {@link Job}) without
 * that type itself needing to know about this plugin, as opposed to a per-class {@code Action}
 * hand-wired into a specific job type.</p>
 *
 * <p><b>Permission gate</b> mirrors {@link ConfigTemplatesRootAction}: the link itself is only
 * contributed for users holding {@link Jenkins#ADMINISTER}, consistent with how the rest of this
 * plugin (root list, per-project pages) is gated. The destination page independently re-checks
 * this same permission on every request via {@code ConfigTemplatesRootAction#getTarget()}, so
 * this factory-level check is a visibility convenience, not the sole enforcement point.</p>
 */
@Extension
public class ConfigTemplatesJobActionFactory extends TransientActionFactory<Job> {

    @Override
    public Class<Job> type() {
        return Job.class;
    }

    @Override
    public Collection<? extends Action> createFor(Job target) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null || !jenkins.hasPermission(Jenkins.ADMINISTER)) {
            return Collections.emptySet();
        }
        ConfigTemplatesJobProperty property =
                (ConfigTemplatesJobProperty) target.getProperty((Class) ConfigTemplatesJobProperty.class);
        return Collections.singleton(new ConfigTemplatesJobAction(property));
    }
}
