package io.github.retrokharkov1.configtemplatesync;

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end coverage of {@code configTemplateSubstitute}'s optional {@code buildVersion} param
 * (§4a build-version config pinning): a binding gets recorded on a real substitution, and a later
 * substitution for the SAME buildVersion replays the pinned versions even after a newer version
 * has since been activated — while a call with no buildVersion keeps using current-active.
 */
public class ConfigTemplateSubstituteBuildVersionPinningTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final String FILE_TEXT =
            "{\"T\":\"#{AppSettings.TimeoutSeconds}#\",\"F\":\"#{AppSettings.FeatureX}#\"}";

    private String runSubstitute(String jobName, String buildVersionArg) throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, jobName);
        String buildVersionParam = buildVersionArg == null ? "" : ", buildVersion: '" + buildVersionArg + "'";
        job.setDefinition(new CpsFlowDefinition(String.join("\n",
                "node {",
                "  writeFile file: 'appsettings.Production.json', text: '" + FILE_TEXT + "'",
                "  configTemplateSubstitute(commonConfigSet: 'pin-common', envConfigSet: 'pin-dev', "
                        + "file: 'appsettings.Production.json'" + buildVersionParam + ")",
                "  echo 'RESULT=' + readFile('appsettings.Production.json')",
                "}"), true));
        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0).get());
        return j.getLog(run);
    }

    @Test
    public void pinnedBuildVersionReplaysOriginalVersionsAfterNewerVersionActivated() throws Exception {
        ConfigSetRepository repo = ConfigSetRepository.onMaster();
        int commonV1 = repo.save("pin-common", "{\"AppSettings\":{\"TimeoutSeconds\":\"30\"}}", null, "v1", "alice", true);
        int devV1 = repo.save("pin-dev", "{\"AppSettings\":{\"FeatureX\":\"on\"}}", null, "v1", "alice", true);

        // First deploy of build 1.0.0 -> uses (and pins) v1/v1.
        String log1 = runSubstitute("pin-first-deploy", "1.0.0");
        assertThat(log1, containsString("RESULT={\"T\":\"30\",\"F\":\"on\"}"));
        assertThat(log1, containsString("Recorded binding: buildVersion='1.0.0'"));

        ConfigDeploymentBinding binding = ConfigDeploymentBindingRepository.onMaster().find("pin-dev", "1.0.0");
        assertThat(binding.toString(), containsString("commonVersionNumber=" + commonV1));
        assertThat(binding.toString(), containsString("envVersionNumber=" + devV1));

        // Activate newer versions - "currently active" has moved on.
        int commonV2 = repo.save("pin-common", "{\"AppSettings\":{\"TimeoutSeconds\":\"99\"}}", null, "v2", "bob", true);
        int devV2 = repo.save("pin-dev", "{\"AppSettings\":{\"FeatureX\":\"off\"}}", null, "v2", "bob", true);

        // A fresh build with no buildVersion arg must use current-active (v2), unchanged default behavior.
        String logNoPin = runSubstitute("pin-no-buildversion", null);
        assertThat(logNoPin, containsString("RESULT={\"T\":\"99\",\"F\":\"off\"}"));

        // Redeploying build 1.0.0 must replay the ORIGINAL pinned v1/v1 values, not the now-active v2/v2.
        String logReplay = runSubstitute("pin-redeploy-1-0-0", "1.0.0");
        assertThat(logReplay, containsString("RESULT={\"T\":\"30\",\"F\":\"on\"}"));
        assertThat(logReplay, containsString("is pinned to common=v" + commonV1 + ", env=v" + devV1));

        // A different, never-before-seen buildVersion has no binding yet -> falls back to current-active,
        // then records ITS OWN binding at v2/v2.
        String logNewBuild = runSubstitute("pin-new-buildversion", "2.0.0");
        assertThat(logNewBuild, containsString("RESULT={\"T\":\"99\",\"F\":\"off\"}"));
        ConfigDeploymentBinding newBinding = ConfigDeploymentBindingRepository.onMaster().find("pin-dev", "2.0.0");
        assertThat(newBinding.toString(), containsString("commonVersionNumber=" + commonV2));
        assertThat(newBinding.toString(), containsString("envVersionNumber=" + devV2));
    }
}
