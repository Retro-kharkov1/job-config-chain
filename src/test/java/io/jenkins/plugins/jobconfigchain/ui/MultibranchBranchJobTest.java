package io.jenkins.plugins.jobconfigchain.ui;

import jenkins.branch.Branch;
import jenkins.branch.BranchProjectFactory;
import jenkins.scm.api.SCMHead;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multibranch branch jobs are {@link WorkflowJob}s, so the Config Chains action and property apply to them
 * too. Proves the property is not lost by what branch indexing does to an existing branch job (it decorates
 * the job and re-applies the branch via {@link BranchProjectFactory}) nor by saving/reloading the project.
 */
@WithJenkins
public class MultibranchBranchJobTest {

    @Test
    public void branchJobProperty_survivesBranchReapplyAndProjectResave_andJobGetsTheAction(JenkinsRule j)
            throws Exception {
        WorkflowMultiBranchProject project = j.createProject(WorkflowMultiBranchProject.class, "mbp");
        @SuppressWarnings("unchecked")
        BranchProjectFactory<WorkflowJob, ?> factory = (BranchProjectFactory<WorkflowJob, ?>) project.getProjectFactory();
        Branch branch = new Branch("src", new SCMHead("main"), new hudson.scm.NullSCM(), Collections.emptyList());
        WorkflowJob job = factory.newInstance(branch);
        assertEquals(project, job.getParent(), "a branch job lives inside the multibranch project");

        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v1 = property.addVersion("{\"a\":1}", "n", "alice", 1L, Collections.emptyList(), "JSON");
        property.activate(v1);
        job.addProperty(property);
        job.save();
        assertEquals(1, job.getActions(ConfigTemplatesJobAction.class).size(),
                "a branch job is a Pipeline job and gets the Config Chains action");

        // What indexing does to an already-existing branch job, repeated.
        Branch moved = new Branch("src", new SCMHead("main"), new hudson.scm.NullSCM(), Collections.emptyList());
        factory.setBranch(job, moved);
        factory.decorate(job);
        job.save();
        project.save();

        JobConfigTemplateProperty kept = job.getProperty(JobConfigTemplateProperty.class);
        assertNotNull(kept, "the property must survive the branch being re-applied");
        assertEquals("{\"a\":1}", kept.getVersion(v1).getContentJson());
        assertTrue(job.getConfigFile().asString().contains(JobConfigTemplateProperty.class.getName()));
    }
}
