package io.jenkins.plugins.jobconfigchain.ui;

import hudson.model.Job;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.JobConfigTemplateVersion;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Finds the Jobs whose base chains name a given global Config Set, so that withdrawing or purging
 * one is never a silent act.
 *
 * <p>Lives in {@code ui} rather than {@code persistence} because it reads
 * {@link JobConfigTemplateProperty}; the other arrangement would invert the package dependency.</p>
 *
 * <p><b>It walks every version of every Job, not only the active one.</b> A Job version history is
 * append-only and activating an older version is a one-click metadata flip with no revalidation, so
 * a chain buried in an old version becomes live the moment somebody rolls back — and a rollback is
 * exactly the move an operator reaches for under pressure. Reporting only the active version would
 * let a deletion look clean while planting a failure for the next person, in an unrelated context,
 * at the worst possible moment.</p>
 *
 * <p>What differs between the two operations is the <em>policy</em>, not the scan: withdrawal is
 * refused only when an ACTIVE version references the set (it is reversible, and taking a set out of
 * service is itself the honest way to discover who still needs it), while purge is refused when ANY
 * version does, because nothing can be recovered afterwards. Both report the full picture.</p>
 *
 * <p>Config Sets cannot reference each other — {@code ConfigSet#addVersion} rejects a base chain on
 * a COMMON-role set, the only surviving role — so nothing here scans other Config Sets. A test pins
 * that rejection, so the day the invariant relaxes this omission fails the build rather than
 * quietly becoming a gap.</p>
 */
public final class ConfigSetUsageScanner {

    private ConfigSetUsageScanner() {
    }

    /** One Job that names the Config Set somewhere in its history. */
    public static final class JobUsage {

        private final String fullName;
        private final String url;
        private final List<Integer> versionNumbers;
        private final boolean activeVersionReferences;

        JobUsage(String fullName, String url, List<Integer> versionNumbers,
                 boolean activeVersionReferences) {
            this.fullName = fullName;
            this.url = url;
            this.versionNumbers = Collections.unmodifiableList(new ArrayList<>(versionNumbers));
            this.activeVersionReferences = activeVersionReferences;
        }

        public String getFullName() {
            return fullName;
        }

        public String getUrl() {
            return url;
        }

        /** Every version of this Job whose base chain names the Config Set, ascending. */
        public List<Integer> getVersionNumbers() {
            return versionNumbers;
        }

        /** Whether the version this Job is currently using is among them. */
        public boolean isActiveVersionReferences() {
            return activeVersionReferences;
        }
    }

    /** What a scan found. */
    public static final class Usage {

        private final List<JobUsage> jobs;

        Usage(List<JobUsage> jobs) {
            this.jobs = Collections.unmodifiableList(new ArrayList<>(jobs));
        }

        public List<JobUsage> getJobs() {
            return jobs;
        }

        /** Jobs whose ACTIVE version names the Config Set — the ones a withdrawal would break now. */
        public List<JobUsage> getActiveJobs() {
            List<JobUsage> active = new ArrayList<>();
            for (JobUsage job : jobs) {
                if (job.isActiveVersionReferences()) {
                    active.add(job);
                }
            }
            return active;
        }

        /** Jobs that name it only in versions they are not currently using. */
        public List<JobUsage> getHistoricalOnlyJobs() {
            List<JobUsage> historical = new ArrayList<>();
            for (JobUsage job : jobs) {
                if (!job.isActiveVersionReferences()) {
                    historical.add(job);
                }
            }
            return historical;
        }

        /** Withdrawal is refused only for a reference that is live right now. */
        public boolean blocksDelete() {
            return !getActiveJobs().isEmpty();
        }

        /** Purge is refused for any reference at all, because nothing survives it to be repaired. */
        public boolean blocksPurge() {
            return !jobs.isEmpty();
        }
    }

    /** Scans every Job on this controller. */
    public static Usage scan(String projectKey) {
        // allItems is declared over the raw Job type, so the result is copied into a wildcard list
        // rather than handed straight to the overload below - which keeps that overload, the one
        // the tests drive, free of raw types.
        List<Job<?, ?>> jobs = new ArrayList<>();
        for (Job<?, ?> job : Jenkins.get().allItems(Job.class)) {
            jobs.add(job);
        }
        return scan(projectKey, jobs);
    }

    /**
     * Scans the given Jobs. This overload exists so the whole rule is unit-testable without
     * standing up a Jenkins instance — the policy above is the part most worth pinning with tests,
     * and it should not require a controller to exercise.
     */
    public static Usage scan(String projectKey, Iterable<? extends Job<?, ?>> jobs) {
        Objects.requireNonNull(projectKey, "projectKey");
        List<JobUsage> found = new ArrayList<>();
        for (Job<?, ?> job : jobs) {
            JobConfigTemplateProperty property = job.getProperty(JobConfigTemplateProperty.class);
            if (property == null) {
                continue;
            }
            List<Integer> referencing = new ArrayList<>();
            boolean activeReferences = false;
            for (JobConfigTemplateVersion version : property.getVersions()) {
                if (!referencesKey(version, projectKey)) {
                    continue;
                }
                referencing.add(version.getVersionNumber());
                if (version.getVersionNumber() == property.getActiveVersionNumber()) {
                    activeReferences = true;
                }
            }
            if (!referencing.isEmpty()) {
                found.add(new JobUsage(job.getFullName(), job.getUrl(), referencing, activeReferences));
            }
        }
        return new Usage(found);
    }

    private static boolean referencesKey(JobConfigTemplateVersion version, String projectKey) {
        List<BaseConfigReference> chain = version.getBaseChain();
        if (chain == null) {
            return false;
        }
        for (BaseConfigReference reference : chain) {
            // Exact and case-sensitive, matching how the storage key is derived: a key that differs
            // only in case is a different Config Set on disk, so treating them as the same here
            // would report a dependency that does not exist.
            if (projectKey.equals(reference.getProjectKey())) {
                return true;
            }
        }
        return false;
    }
}
