package io.jenkins.plugins.jobconfigchain.safetynet;

import hudson.FilePath;
import hudson.model.Result;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.attachOverride;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.run;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runOk;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBase;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.validate;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeBytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 end-to-end: encoding fallback and explicit {@code encoding}, BOM, single-pass substitution and
 * the warn-only workspace containment, driven through real pipeline builds.
 */
@WithJenkins
public class StepsP2HardeningTest {

    private static final String FILE = "app.cfg";
    private static final String BIN = "BIN";
    private static final byte[] LATIN1_CAFE = {'c', 'a', 'f', (byte) 0xE9, '=', '#', '{', 'K', '}', '#'};

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    private WorkflowJob jobWith(String name, String baseJson) throws Exception {
        String base = name + "-base";
        seedBase(base, baseJson);
        WorkflowJob job = newPipelineJob(j, name);
        attachOverride(job, "{}", chainOf(BaseConfigReference.active(base)), null);
        return job;
    }

    private static boolean agentDefaultDecodes(byte[] bytes) {
        try {
            Charset.defaultCharset().newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    // ---- encoding -------------------------------------------------------------------------------

    @Test
    public void invalidUtf8_fallsBackToAgentDefaultWithWarning_orFailsLikeBefore() throws Exception {
        WorkflowJob job = jobWith("p2-fallback", "{\"K\":\"v\"}");
        byte[] expectedIfDecodable = {'c', 'a', 'f', (byte) 0xE9, '=', 'v'};

        if (agentDefaultDecodes(LATIN1_CAFE)) {
            // default charset can read the bytes (e.g. ISO-8859-1/Cp1252): today's behaviour, plus a warning
            WorkflowRun run = runOk(j, job, writeBytes(FILE, LATIN1_CAFE), substitute("file: '" + FILE + "'"),
                    echoBytes(BIN, FILE));
            assertTrue(j.getLog(run).contains("[configTemplateSync][WARN] Target file '" + FILE
                    + "' is not valid UTF-8"), j.getLog(run));
            assertArrayEquals(expectedIfDecodable, logBytes(j, run, BIN));
        } else {
            // default charset cannot read them either (UTF-8 / US-ASCII default): failed before, fails now
            WorkflowRun run = run(j, job, Result.FAILURE, writeBytes(FILE, LATIN1_CAFE),
                    substitute("file: '" + FILE + "'"));
            assertTrue(j.getLog(run).contains("cannot be decoded as UTF-8 nor in the agent default charset"),
                    j.getLog(run));
        }
    }

    @Test
    public void explicitEncoding_substitute_readsAndWritesThatCharset() throws Exception {
        WorkflowJob job = jobWith("p2-enc-sub", "{\"K\":\"v\"}");

        WorkflowRun run = runOk(j, job, writeBytes(FILE, LATIN1_CAFE),
                substitute("file: '" + FILE + "', encoding: 'ISO-8859-1'"), echoBytes(BIN, FILE));

        assertArrayEquals(new byte[] {'c', 'a', 'f', (byte) 0xE9, '=', 'v'}, logBytes(j, run, BIN));
        assertFalse(j.getLog(run).contains("[WARN]"), j.getLog(run));
    }

    @Test
    public void explicitEncoding_validate_readsThatCharset() throws Exception {
        WorkflowJob job = jobWith("p2-enc-val", "{\"K\":\"v\"}");

        WorkflowRun run = runOk(j, job, writeBytes(FILE, LATIN1_CAFE),
                validate("file: '" + FILE + "', encoding: 'ISO-8859-1'"));

        assertTrue(j.getLog(run).contains("Validation passed"), j.getLog(run));
    }

    @Test
    public void explicitUtf8_onInvalidBytes_failsWithNoFallback() throws Exception {
        WorkflowJob job = jobWith("p2-enc-strict", "{\"K\":\"v\"}");

        WorkflowRun run = run(j, job, Result.FAILURE, writeBytes(FILE, LATIN1_CAFE),
                substitute("file: '" + FILE + "', encoding: 'UTF-8'"));

        assertTrue(j.getLog(run).contains("cannot be decoded as UTF-8 (the configured 'encoding')"), j.getLog(run));
    }

    @Test
    public void unknownEncoding_failsWithClearMessage_forBothSteps() throws Exception {
        WorkflowJob job = jobWith("p2-enc-unknown", "{\"K\":\"v\"}");

        WorkflowRun sub = run(j, job, Result.FAILURE, writeBytes(FILE, LATIN1_CAFE),
                substitute("file: '" + FILE + "', encoding: 'no-such-charset'"));
        assertTrue(j.getLog(sub).contains("[configTemplateSync] Unknown or unsupported 'encoding': 'no-such-charset'"),
                j.getLog(sub));

        WorkflowRun val = run(j, job, Result.FAILURE, writeBytes(FILE, LATIN1_CAFE),
                validate("file: '" + FILE + "', encoding: 'no-such-charset'"));
        assertTrue(j.getLog(val).contains("Unknown or unsupported 'encoding'"), j.getLog(val));
    }

    @Test
    public void utf8Bom_isPreservedByteForByte_byValidateAndSubstitute() throws Exception {
        WorkflowJob job = jobWith("p2-bom", "{\"K\":\"v\"}");
        byte[] input = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'k', '=', '#', '{', 'K', '}', '#'};
        byte[] expected = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'k', '=', 'v'};

        WorkflowRun run = runOk(j, job, writeBytes(FILE, input), validate("file: '" + FILE + "'"),
                substitute("file: '" + FILE + "'"), echoBytes(BIN, FILE));

        assertArrayEquals(expected, logBytes(j, run, BIN));
    }

    // ---- single-pass substitution ---------------------------------------------------------------

    @Test
    public void valueContainingAnotherKnownToken_isNotReExpanded_andTheFinalCheckStillAborts() throws Exception {
        WorkflowJob job = jobWith("p2-reexpand", "{\"A\":\"x#{B}#y\",\"B\":\"bee\"}");

        WorkflowRun run = run(j, job, Result.FAILURE, writeBytes(FILE, "a=#{A}# b=#{B}#".getBytes(StandardCharsets.UTF_8)),
                substitute("file: '" + FILE + "'"));

        assertTrue(j.getLog(run).contains(
                "[configTemplateSync] Substitution incomplete — tokens remain unresolved: [#{B}#]"), j.getLog(run));
    }

    @Test
    public void valuesWithDollarBackslashAndBraces_areWrittenLiterally() throws Exception {
        WorkflowJob job = jobWith("p2-literal", "{\"A\":\"$1\\\\${x}$&\\\\\"}");
        byte[] expected = "a=$1\\${x}$&\\".getBytes(StandardCharsets.UTF_8);

        WorkflowRun run = runOk(j, job, writeBytes(FILE, "a=#{A}#".getBytes(StandardCharsets.UTF_8)),
                substitute("file: '" + FILE + "'"), echoBytes(BIN, FILE));

        assertArrayEquals(expected, logBytes(j, run, BIN));
    }

    @Test
    public void unresolvedToken_stillAbortsWithTheSameMessage() throws Exception {
        WorkflowJob job = jobWith("p2-unresolved", "{\"K\":\"v\"}");
        // validate would reject this file first; substitute alone keeps its own final check.
        WorkflowRun run = run(j, job, Result.FAILURE,
                writeBytes(FILE, "#{K}# #{Missing.Key}#".getBytes(StandardCharsets.UTF_8)),
                substitute("file: '" + FILE + "'"));

        String log = j.getLog(run);
        assertTrue(log.contains("Missing config keys") || log.contains("Substitution incomplete"), log);
    }

    // ---- workspace containment (warn only) ------------------------------------------------------

    @Test
    public void targetOutsideTheWorkspace_warnsButStillSucceeds() throws Exception {
        WorkflowJob job = jobWith("p2-outside", "{\"K\":\"v\"}");
        // First build creates the workspace; the sibling file lives next to it, outside it.
        runOk(j, job, "writeFile file: 'seed.txt', text: 'x'");
        FilePath workspace = j.jenkins.getWorkspaceFor(job);
        FilePath outside = workspace.getParent().child("p2-outside-target.cfg");
        outside.write("k=#{K}#", "UTF-8");

        WorkflowRun run = runOk(j, job, validate("file: '../p2-outside-target.cfg'"),
                substitute("file: '../p2-outside-target.cfg'"));

        String log = j.getLog(run);
        assertTrue(log.contains("[configTemplateSync][WARN] Target file '../p2-outside-target.cfg' resolves outside the workspace"),
                log);
        assertTrue(outside.readToString().equals("k=v"), outside.readToString());
        outside.delete();
    }

    @Test
    public void targetInsideTheWorkspace_producesNoContainmentWarning() throws Exception {
        WorkflowJob job = jobWith("p2-inside", "{\"K\":\"v\"}");

        WorkflowRun run = runOk(j, job, writeBytes("sub/" + FILE, "k=#{K}#".getBytes(StandardCharsets.UTF_8)),
                validate("file: 'sub/" + FILE + "'"), substitute("file: 'sub/" + FILE + "'"));

        assertFalse(j.getLog(run).contains("outside the workspace"), j.getLog(run));
    }
}
