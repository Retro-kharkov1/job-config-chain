package io.jenkins.plugins.jobconfigchain.safetynet;

import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.nio.charset.StandardCharsets;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.PLACEHOLDER;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.attachOverride;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runOk;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBaseWithSecret;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedCredential;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Safety net D — exact substitution OUTPUT for current valid inputs, so that the planned
 * single-pass substitution refactor cannot change what normal configs produce. Input and expected
 * output are compared as raw bytes (input written and output read through the pipeline's Base64
 * mode), so line endings, trailing text and special characters are checked verbatim.
 *
 * <p>What is pinned down, all of it behaviour of the current sequential
 * {@code String.replace}-based substitution on valid input:</p>
 * <ul>
 *   <li>string / number / boolean leaves render as their plain text (number {@code 42}, boolean
 *       {@code false});</li>
 *   <li>a token may appear several times, and tokens may sit directly next to each other;</li>
 *   <li>replacement values are inserted LITERALLY: {@code $1}, {@code \}, {@code $&} in a config
 *       value, in a secret from Credentials and in an env override are not regex replacement syntax
 *       (a {@code Matcher.replaceAll} based refactor would break this unless it quotes them);</li>
 *   <li>CRLF line endings and the absence of a trailing newline are preserved;</li>
 *   <li>text that only resembles a token ({@code ${Not.A.Token}}, an unterminated {@code #{...})
 *       is left alone;</li>
 *   <li>a non-secret path takes an env var of the same name over the config value; a secret path
 *       never does.</li>
 * </ul>
 *
 * <p>Deliberately NOT pinned (undefined / order-dependent today, a refactor may legitimately change
 * them): a secret or env value that itself contains token-shaped text {@code #{x}#} (currently the
 * post-substitution check fails the build only when a token remains), and null leaves.</p>
 */
@WithJenkins
public class SecretSubstitutionCharacterizationTest {

    private static final String FILE = "app.cfg";
    private static final String BIN = "BIN";

    private static final String CREDENTIAL_ID = "sub-cred";
    /** Contains regex-replacement specials and braces, but no "#{". */
    private static final String SECRET_VALUE = "P@ss$1\\w!{x}&$&";

    private static final String BASE_JSON = "{"
            + "\"Str\":\"plain\","
            + "\"Num\":42,"
            + "\"Flag\":false,"
            + "\"Special\":\"a$1\\\\b$&c\\\\1\","
            + "\"Cred\":{\"Pw\":\"" + PLACEHOLDER + "\"},"
            + "\"Svc\":{\"Url\":\"https://svc.local/p?a=1&b=2\"}"
            + "}";

    private static final String TEMPLATE =
            "str=#{Str}#\r\n"
                    + "int=#{Num}# bool=#{Flag}#\r\n"
                    + "special=#{Special}# repeat=#{Str}#/#{Str}# adj=#{Num}##{Str}#\r\n"
                    + "secret=#{Cred.Pw}#\r\n"
                    + "nested=#{Svc.Url}# keep=${Not.A.Token} lone=#{unclosed";

    private static final String EXPECTED =
            "str=plain\r\n"
                    + "int=42 bool=false\r\n"
                    + "special=a$1\\b$&c\\1 repeat=plain/plain adj=42plain\r\n"
                    + "secret=" + SECRET_VALUE + "\r\n"
                    + "nested=https://svc.local/p?a=1&b=2 keep=${Not.A.Token} lone=#{unclosed";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    private WorkflowJob seededJob(String name, String baseKey) throws Exception {
        seedCredential(CREDENTIAL_ID, SECRET_VALUE);
        seedBaseWithSecret(baseKey, "Cred.Pw", CREDENTIAL_ID, BASE_JSON);
        WorkflowJob job = newPipelineJob(j, name);
        attachOverride(job, "{}", chainOf(BaseConfigReference.active(baseKey)), null);
        return job;
    }

    @Test
    public void validConfig_producesTheExactExpectedBytes_valuesInsertedLiterally() throws Exception {
        WorkflowJob job = seededJob("sub-exact", "sub-base");

        WorkflowRun run = runOk(j, job,
                writeBytes(FILE, TEMPLATE.getBytes(StandardCharsets.US_ASCII)),
                substitute("file: '" + FILE + "'"),
                echoBytes(BIN, FILE));

        assertEquals(EXPECTED, new String(logBytes(j, run, BIN), StandardCharsets.US_ASCII));
    }

    @Test
    public void nonSecretEnvOverrideIsInsertedLiterally_secretPathIgnoresItsEnvVar() throws Exception {
        WorkflowJob job = seededJob("sub-env", "sub-env-base");
        String envValue = "http://env/?x=$1&y=%20";

        WorkflowRun run = runOk(j, job,
                writeBytes(FILE, "u=#{Svc.Url}# s=#{Cred.Pw}#".getBytes(StandardCharsets.US_ASCII)),
                "withEnv(['Svc.Url=" + envValue + "', 'Cred.Pw=env-must-be-ignored']) {",
                "  " + substitute("file: '" + FILE + "'"),
                "}",
                echoBytes(BIN, FILE));

        assertEquals("u=" + envValue + " s=" + SECRET_VALUE,
                new String(logBytes(j, run, BIN), StandardCharsets.US_ASCII));
    }
}
