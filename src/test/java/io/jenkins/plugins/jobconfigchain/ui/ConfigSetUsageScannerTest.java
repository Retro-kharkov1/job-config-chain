package io.jenkins.plugins.jobconfigchain.ui;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the rule that decides whether a Config Set can be withdrawn or purged.
 *
 * <p>The scan itself is uninteresting; the policy on top of it is the whole point, and it is not
 * symmetric. Withdrawal is refused only when a Job is using the Config Set <em>right now</em>,
 * because withdrawal is reversible and taking a set out of service is itself the honest way to find
 * out who still needs it. Purge is refused for any reference at all, including one buried in a
 * version nobody has activated for a year, because nothing survives a purge to be repaired
 * afterwards.</p>
 *
 * <p>Getting that asymmetry wrong in either direction is costly and silent: block everything and a
 * mature controller can never delete anything, since version history is never pruned and almost
 * every key appears in some old chain; block nothing and a rollback breaks months later, in an
 * unrelated context, for somebody who did not make the decision.</p>
 */
public class ConfigSetUsageScannerTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    /**
     * Gives a Job a Config Templates property whose versions name the given base keys, and
     * activates the version at {@code activeIndex}.
     */
    private FreeStyleProject jobReferencing(String jobName, List<String> keyPerVersion, int activeIndex)
            throws Exception {
        FreeStyleProject job = jenkins.createFreeStyleProject(jobName);
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        List<Integer> versionNumbers = new ArrayList<>();
        for (String key : keyPerVersion) {
            List<BaseConfigReference> chain = new ArrayList<>();
            if (key != null) {
                chain.add(BaseConfigReference.active(key));
            }
            versionNumbers.add(property.addVersion("{}", "seed", "alice", 1L, chain, "JSON"));
        }
        property.activate(versionNumbers.get(activeIndex));
        job.addProperty(property);
        return job;
    }

    @Test
    public void aJobUsingItRightNowBlocksBothOperations() throws Exception {
        FreeStyleProject job = jobReferencing("uses-it-now", List.of("shared-db"), 0);

        ConfigSetUsageScanner.Usage usage = ConfigSetUsageScanner.scan("shared-db", List.of(job));

        assertEquals(1, usage.getJobs().size());
        assertEquals("uses-it-now", usage.getJobs().get(0).getFullName());
        assertTrue(usage.getJobs().get(0).isActiveVersionReferences());
        assertTrue("a live reference must stop a withdrawal", usage.blocksDelete());
        assertTrue("and it must certainly stop a purge", usage.blocksPurge());
    }

    @Test
    public void aReferenceOnlyInAnOlderVersionWarnsButDoesNotBlockWithdrawal() throws Exception {
        // v1 names the Config Set, v2 does not, and v2 is active. Nothing breaks today - but
        // activating v1 is one click, so the fact must still be reported.
        FreeStyleProject job = jobReferencing("rolled-forward", java.util.Arrays.asList("shared-db", null), 1);

        ConfigSetUsageScanner.Usage usage = ConfigSetUsageScanner.scan("shared-db", List.of(job));

        assertEquals("the historical reference must still be found", 1, usage.getJobs().size());
        assertFalse(usage.getJobs().get(0).isActiveVersionReferences());
        assertEquals(List.of(1), usage.getJobs().get(0).getVersionNumbers());
        assertEquals(1, usage.getHistoricalOnlyJobs().size());
        assertTrue(usage.getActiveJobs().isEmpty());

        assertFalse("withdrawal is reversible, so a historical reference only warns",
                usage.blocksDelete());
        assertTrue("purge is not reversible, so the same reference stops it",
                usage.blocksPurge());
    }

    @Test
    public void everyReferencingVersionIsReported() throws Exception {
        FreeStyleProject job = jobReferencing("references-twice",
                java.util.Arrays.asList("shared-db", null, "shared-db"), 1);

        ConfigSetUsageScanner.Usage usage = ConfigSetUsageScanner.scan("shared-db", List.of(job));

        assertEquals("both referencing versions must be named, so the operator sees the scope",
                List.of(1, 3), usage.getJobs().get(0).getVersionNumbers());
    }

    @Test
    public void jobsWithoutThePropertyOrWithoutAChainAreIgnored() throws Exception {
        FreeStyleProject plain = jenkins.createFreeStyleProject("no-property");
        FreeStyleProject emptyChain = jobReferencing("empty-chain", java.util.Collections.singletonList(null), 0);
        FreeStyleProject other = jobReferencing("uses-something-else", List.of("unrelated-key"), 0);

        ConfigSetUsageScanner.Usage usage =
                ConfigSetUsageScanner.scan("shared-db", List.of(plain, emptyChain, other));

        assertTrue("nothing here names the key, so nothing may be reported",
                usage.getJobs().isEmpty());
        assertFalse(usage.blocksDelete());
        assertFalse(usage.blocksPurge());
    }

    @Test
    public void keysAreMatchedExactly() throws Exception {
        // Storage keys are case-sensitive filenames, so "Shared-DB" is a different Config Set from
        // "shared-db". Matching loosely here would report a dependency that does not exist and
        // block a deletion for no reason.
        FreeStyleProject differentCase = jobReferencing("different-case", List.of("Shared-DB"), 0);
        FreeStyleProject prefix = jobReferencing("prefix-only", List.of("shared-db-extra"), 0);

        ConfigSetUsageScanner.Usage usage =
                ConfigSetUsageScanner.scan("shared-db", List.of(differentCase, prefix));

        assertTrue(usage.getJobs().isEmpty());
    }
}
