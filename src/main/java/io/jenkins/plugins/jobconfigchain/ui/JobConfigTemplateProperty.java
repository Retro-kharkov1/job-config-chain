package io.jenkins.plugins.jobconfigchain.ui;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreePaths;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.model.JobConfigTemplateVersion;
import io.jenkins.plugins.jobconfigchain.model.SecretPlaceholder;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Job-scoped sibling of {@code ConfigSet} (tech-lead design contract, 2026-09-09): holds a single
 * {@link Job}'s own Config Chain content directly as a {@link JobProperty}, rather than
 * associating the job with a separate {@code (projectKey, role, environment)}-keyed {@code
 * ConfigSet} elsewhere. Replaces {@code ConfigTemplatesJobProperty} (the old association-only
 * property) wholesale.
 *
 * <p>{@link #addVersion} deliberately mirrors {@code ConfigSet#addVersion}'s logic 1:1 — same
 * contentType resolution/locking-on-first-save, same secret-placeholder enforcement, same monotonic
 * version numbering — but is an independent implementation, never a delegating call into {@code
 * ConfigSet}: a {@link Job} is not a {@code (projectKey, role, environment)}-keyed entity, so there
 * is no real {@code ConfigSet} for it to delegate to.</p>
 */
public class JobConfigTemplateProperty extends JobProperty<Job<?, ?>> {

    private static final long serialVersionUID = 1L;

    /** Append-only version history. Never remove or mutate an entry once added. */
    private final List<JobConfigTemplateVersion> versions = new ArrayList<>();

    /** 0 means "no active version yet"; otherwise exactly one version has this number. */
    private int activeVersionNumber;

    /**
     * This job's structured content format, chosen once at first Save and immutable thereafter —
     * {@code null} until the first version exists, mirroring {@code ConfigSet#contentType}'s own
     * "choose once, locked forever" contract (see multi-format-content.md's "Content type model" section).
     */
    private ContentType contentType;

    /** Dotted path within this job's content -> Jenkins credential ID supplying the real value. NOT versioned. */
    private final Map<String, String> secretsManifest = new LinkedHashMap<>();

    public JobConfigTemplateProperty() {
    }

    public List<JobConfigTemplateVersion> getVersions() {
        return Collections.unmodifiableList(versions);
    }

    public JobConfigTemplateVersion getVersion(int versionNumber) {
        for (JobConfigTemplateVersion v : versions) {
            if (v.getVersionNumber() == versionNumber) {
                return v;
            }
        }
        return null;
    }

    public int getActiveVersionNumber() {
        return activeVersionNumber;
    }

    public JobConfigTemplateVersion getActiveVersion() {
        if (activeVersionNumber == 0) {
            return null;
        }
        return getVersion(activeVersionNumber);
    }

    public ContentType getContentType() {
        return contentType;
    }

    public Map<String, String> getSecretsManifest() {
        return Collections.unmodifiableMap(secretsManifest);
    }

    public void putSecretManifestEntry(String dottedPath, String credentialId) {
        secretsManifest.put(
                Objects.requireNonNull(dottedPath, "dottedPath"),
                Objects.requireNonNull(credentialId, "credentialId"));
    }

    /**
     * Mirrors {@code ConfigSet#removeSecretManifestEntry} exactly — a pure in-place manifest
     * mutation, never a new version.
     *
     * @return {@code true} if an entry for {@code dottedPath} existed and was removed.
     */
    public boolean removeSecretManifestEntry(String dottedPath) {
        return secretsManifest.remove(Objects.requireNonNull(dottedPath, "dottedPath")) != null;
    }

    /**
     * Appends a new version, mirroring {@code ConfigSet#addVersion}'s logic (contentType
     * resolution/locking on first save, secret-placeholder enforcement, monotonic version
     * numbering) as an independent implementation. Never activates the new version itself — call
     * {@link #activate(int)} explicitly if desired.
     *
     * @param contentTypeParamOrNull meaningful ONLY on this property's very first saved version
     *                               (mirrors {@code ConfigSetPage#saveImpl}'s own "only meaningful
     *                               on first save" contract) — defaults to {@link ContentType#JSON}
     *                               when absent/blank, exactly like {@code ConfigSet}'s own
     *                               absent-picker default. Ignored (the already-committed, immutable
     *                               {@link #contentType} is used instead) once a first version
     *                               already exists.
     * @return the newly assigned, monotonically increasing version number.
     */
    public int addVersion(String contentJson, String note, String author, long timestampEpochMillis,
                           List<BaseConfigReference> baseChain, String contentTypeParamOrNull) {
        ContentType resolvedContentType;
        if (versions.isEmpty()) {
            resolvedContentType = (contentTypeParamOrNull == null || contentTypeParamOrNull.trim().isEmpty())
                    ? ContentType.JSON : ContentType.valueOf(contentTypeParamOrNull);
        } else {
            resolvedContentType = contentType;
        }
        enforceSecretPlaceholders(contentJson, resolvedContentType);
        this.contentType = resolvedContentType;
        int nextVersionNumber = nextVersionNumber();
        versions.add(new JobConfigTemplateVersion(nextVersionNumber, contentJson, note, author,
                timestampEpochMillis, baseChain == null ? Collections.emptyList() : baseChain));
        return nextVersionNumber;
    }

    private int nextVersionNumber() {
        int max = 0;
        for (JobConfigTemplateVersion v : versions) {
            max = Math.max(max, v.getVersionNumber());
        }
        return max + 1;
    }

    /**
     * Structurally rejects a save where a manifest-declared path's leaf value in {@code content} is
     * not the literal placeholder marker — mirrors {@code ConfigSet#enforceSecretPlaceholders}
     * exactly.
     */
    private void enforceSecretPlaceholders(String content, ContentType type) {
        if (secretsManifest.isEmpty()) {
            return;
        }
        TreeNode root = TreeFormats.forType(type).parse(content);
        if (!root.isObject()) {
            throw new IllegalArgumentException(
                    io.jenkins.plugins.jobconfigchain.model.Messages.JobProperty_ContentMustBeObject(type));
        }
        for (String dottedPath : secretsManifest.keySet()) {
            TreeNode leaf = TreePaths.get(root, dottedPath);
            if (leaf == null) {
                continue;
            }
            String asString = !leaf.isObject() && !leaf.isNull() ? leaf.leafAsString() : null;
            if (asString == null || !asString.equals(SecretPlaceholder.VALUE)) {
                throw new IllegalArgumentException(
                        io.jenkins.plugins.jobconfigchain.model.Messages.SecretPath_MustHoldPlaceholder(dottedPath, SecretPlaceholder.VALUE));
            }
        }
    }

    /**
     * Activates (or rolls back to) a previously-saved version — a pure metadata flip, mirrors
     * {@code ConfigSet#activate} exactly.
     */
    public void activate(int versionNumber) {
        if (getVersion(versionNumber) == null) {
            throw new IllegalArgumentException(io.jenkins.plugins.jobconfigchain.model.Messages.Version_NotFound(versionNumber));
        }
        this.activeVersionNumber = versionNumber;
    }

    /**
     * Keeps the job's own live property across a job Configure -> Save. {@code Job.doConfigSubmit}
     * rebuilds the property list from the submitted form; for a descriptor with no form entry it calls
     * {@code reconfigure(req, null)} and keeps whatever is returned. Returning {@code this} (the live
     * instance, never a snapshot taken when the form was rendered) preserves all versions and a version
     * added through the plugin's own save endpoints while the Configure page was open.
     */
    @Override
    public JobProperty<?> reconfigure(StaplerRequest2 req, JSONObject form) {
        return this;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.ConfigTemplates_DisplayName();
        }

        /**
         * Always {@code true} so that this descriptor takes part in the property rebuild done on every
         * job Configure -> Save; with {@code false} the property would be silently dropped. The content
         * is still viewed and edited only on the job's own {@code /job/<name>/configChains} page (see
         * {@link ConfigTemplatesJobAction}): the descriptor is an "invisible property" (core's
         * {@code ReconfigurableDescribable} pattern) — an empty {@code config.jelly}, a
         * {@link HideFromJobConfigure} visibility filter, {@link #newInstance} returning {@code null}
         * and {@link JobConfigTemplateProperty#reconfigure} returning the live instance.
         */
        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return true;
        }

        /**
         * Never creates a property from a form: Save must not give a job without the property an empty
         * one, and the Pipeline {@code properties([...])} generator yields no property.
         */
        @Override
        public JobProperty<?> newInstance(StaplerRequest2 req, JSONObject formData) {
            return null;
        }
    }

    /** Hides the descriptor from rendered descriptor lists (job Configure page); persistence does not depend on it. */
    @Extension
    public static class HideFromJobConfigure extends DescriptorVisibilityFilter {
        @Override
        public boolean filter(Object context, Descriptor descriptor) {
            return !(descriptor instanceof DescriptorImpl);
        }
    }
}
