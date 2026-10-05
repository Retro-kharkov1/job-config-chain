package io.jenkins.plugins.jobconfigchain.safetynet;

import hudson.model.Result;
import hudson.PluginWrapper;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.addBaseVersion;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.addOverrideVersion;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runScript;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBase;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBaseWithSecret;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedCredential;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.stepLogLines;
import static io.jenkins.plugins.jobconfigchain.safetynet.PinnedScenario.*;

/**
 * GENERATOR for the committed upgrade fixture — NOT part of the normal test run (it only runs with
 * {@code -Djcc.fixture.generate=true}, and is tagged {@code fixture-generator}).
 *
 * <p><b>Do not re-run it from a later code state.</b> The committed fixture is the "previous release"
 * every later phase upgrades FROM; regenerating it with fixed code would erase exactly the
 * difference the upgrade tests exist to catch. To regenerate legitimately, check out the pinned
 * commit named in the fixture's {@code PROVENANCE.txt} first.</p>
 *
 * <p>It runs the scenario of {@link PinnedScenario} against the plugin code in the working tree (using
 * the CURRENT step names), lets Jenkins shut down while two Pipeline builds are paused, and copies the
 * resulting {@code JENKINS_HOME} (jobs with their finished and paused builds, the plugin's storage
 * directory, credentials and their keys) plus golden files into
 * {@code -Djcc.fixture.outDir} (default {@code target/fixture-out}) — in the
 * {@code @LocalData} directory layout. Nothing in the fixture is hand-written.</p>
 *
 * <p>Command (from the repo root, inside the Maven container):
 * {@code mvn test -Dtest=PinnedFixtureGeneratorTest -Djcc.fixture.generate=true
 * -Djcc.fixture.outDir=/repo/src/test/resources/io/jenkins/plugins/jobconfigchain/safetynet/UpgradeFromPinnedBuildTest/pinned_build}</p>
 */
@Tag("fixture-generator")
@EnabledIfSystemProperty(named = "jcc.fixture.generate", matches = "true")
public class PinnedFixtureGeneratorTest {

    /** What from the generated home is committed; everything else (plugins, war, logs, caches) is noise. */
    private static final List<String> HOME_WHITELIST = List.of(
            "jobs", "config-template-sync", "credentials.xml", "secrets", "secret.key",
            "secret.key.not-so-secret");

    @RegisterExtension
    final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    private final Map<String, byte[]> golden = new LinkedHashMap<>();
    private String provenance;

    @Test
    void generatePinnedFixture() throws Throwable {
        File out = new File(System.getProperty("jcc.fixture.outDir", "target/fixture-out"));
        if (out.exists() && out.list() != null && out.list().length > 0) {
            throw new IllegalStateException("Refusing to generate into a non-empty directory: " + out
                    + " (the fixture is pinned; delete it deliberately first if you really mean to regenerate)");
        }

        sessions.then(this::runScenarioOnCurrentCode);

        copyWhitelisted(sessions.getHome().toPath(), out.toPath());
        for (Map.Entry<String, byte[]> entry : golden.entrySet()) {
            Path target = out.toPath().resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        Files.write(out.toPath().resolve("PROVENANCE.txt"), provenance.getBytes(StandardCharsets.UTF_8));
    }

    private void runScenarioOnCurrentCode(JenkinsRule r) throws Throwable {
        PluginWrapper plugin = Jenkins.get().getPluginManager().getPlugin("job-config-chain");
        provenance = "Pinned previous build for the upgrade tests (see UpgradeFromPinnedBuildTest).\n"
                + "\n"
                + "case:        no released .hpi exists (plugin not yet hosted) -> locally built, per the plan's P0\n"
                + "             'second preference': the working tree BEFORE any P1+ change, with the step names\n"
                + "             setupConfigTemplate / configTemplateValidate / configTemplateSubstitute.\n"
                + "commit id:   NOT RECORDED BY THE GENERATOR (the QA agent has no git access) - the committer\n"
                + "             must fill in the id of the commit this fixture is based on:  <FILL IN>\n"
                + "generated:   " + Instant.now() + "\n"
                + "plugin:      " + (plugin == null ? "job-config-chain (version unavailable)" : plugin.getShortName()
                        + " " + plugin.getVersion()) + "\n"
                + "jenkins:     " + Jenkins.VERSION + "\n"
                + "java:        " + System.getProperty("java.vendor") + " " + System.getProperty("java.version")
                + " (default charset " + java.nio.charset.Charset.defaultCharset() + ")\n"
                + "generator:   PinnedFixtureGeneratorTest + PinnedScenario (do not re-run from later code)\n";

        // ---- finished builds ------------------------------------------------------------------
        seedCredential(CRED, CRED_VALUE);
        seedBaseWithSecret(BASE, "Db.Password", CRED, BASE_V1);
        seedBase(BASE2, BASE2_V1);
        WorkflowJob job = newPipelineJob(r, JOB);
        List<BaseConfigReference> chain = chainOf(BaseConfigReference.active(BASE),
                BaseConfigReference.pinned(BASE2, 1));
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        // Non-ASCII note on purpose: exercises UTF-8 persistence of config.xml across the upgrade.
        int v1 = property.addVersion(OWN_V1, "first version café", ChainTestKit.AUTHOR, 1L, chain, null);
        property.activate(v1);
        job.addProperty(property);

        capture(G_BUILD1, r, runScript(r, job, Result.SUCCESS, liveDeployScript()), JOB);

        addBaseVersion(BASE, BASE_V2, "bump host", true);
        addBaseVersion(BASE2, BASE2_V2, "bump port", true);
        addOverrideVersion(job, OWN_V2, chain, true);

        capture(G_BUILD2, r, runScript(r, job, Result.SUCCESS, liveDeployScript()), JOB);
        capture(G_BUILD3, r, runScript(r, job, Result.SUCCESS, redeployOfBuild1Script()), JOB);

        // ---- in-flight builds -----------------------------------------------------------------
        seedBaseWithSecret(IF_BASE, "Db.Password", CRED, IF_BASE_JSON);
        List<BaseConfigReference> ifChain = chainOf(BaseConfigReference.active(IF_BASE));

        WorkflowJob control = newPipelineJob(r, IF_CONTROL);
        ChainTestKit.attachOverride(control, IF_OWN, ifChain, null);
        capture(G_INFLIGHT, r, runScript(r, control, Result.SUCCESS, controlScript()), IF_CONTROL);

        WorkflowJob inflight1 = newPipelineJob(r, IF_JOB_1);
        ChainTestKit.attachOverride(inflight1, IF_OWN, ifChain, null);
        startAndAwaitPause(r, inflight1, inflight1Script());

        WorkflowJob inflight2 = newPipelineJob(r, IF_JOB_2);
        ChainTestKit.attachOverride(inflight2, IF_OWN, ifChain, null);
        startAndAwaitPause(r, inflight2, inflight2Script());
        // Returning ends the session: Jenkins shuts down with both builds suspended, which is what
        // writes their program.dat / flow nodes in the pinned build's format.
    }

    private void capture(String key, JenkinsRule r, WorkflowRun run, String jobName) throws IOException {
        golden.put(goldenDeployed(key), logBytes(r, run, BIN));
        golden.put(goldenStepLog(key), stepLogLines(r.getLog(run), jobName).getBytes(StandardCharsets.UTF_8));
    }

    private static void startAndAwaitPause(JenkinsRule r, WorkflowJob job, String script) throws Exception {
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        r.waitForMessage(GATE_POLL_MESSAGE, run);
    }

    private static void copyWhitelisted(Path home, Path out) throws IOException {
        Files.createDirectories(out);
        for (String name : HOME_WHITELIST) {
            Path source = home.resolve(name);
            if (Files.exists(source)) {
                copyTree(source, out.resolve(name));
            }
        }
    }

    /** Copies regular files and directories only; symlinks (Jenkins' lastSuccessful etc.) are skipped. */
    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                    Files.copy(file, target.resolve(source.relativize(file).toString()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
