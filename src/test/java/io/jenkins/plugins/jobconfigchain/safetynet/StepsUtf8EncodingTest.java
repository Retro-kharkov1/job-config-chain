package io.jenkins.plugins.jobconfigchain.safetynet;

import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.attachOverride;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.chainOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.logBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.newPipelineJob;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.propertyOf;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.runOk;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBase;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedBaseWithSecret;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.seedCredential;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.validate;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.PLACEHOLDER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Safety net C — the steps must read and write the target file as UTF-8, whatever the default
 * charset of the JVM they run in (review finding: the steps use {@code FilePath#readToString()} /
 * {@code FilePath#write(String, null)}, i.e. the platform default charset).
 *
 * <p>Input bytes are written, and output bytes read back, through the pipeline's Base64 file mode, so
 * the assertion is byte-exact and no charset is involved in the test itself. Non-ASCII text is built
 * from {@code \}u escapes so the test source encoding cannot matter.</p>
 *
 * <p><b>When these are red / meaningful:</b> the built-in node's "agent JVM" is the test JVM, so the
 * three step tests below FAIL on the current code only when that JVM's default charset is not UTF-8
 * (JDK 17 on Linux with a POSIX/C locale, JDK 17 on Windows, or any JDK started with e.g.
 * {@code -Dfile.encoding=ISO-8859-1} / {@code COMPAT}). On a UTF-8-default JVM (JDK 18+ default,
 * JEP 400) they PASS TRIVIALLY on current code and then only act as a regression guard. Every
 * assertion message names the JVM's default charset so a green run can be classified. To force them
 * red on any host run e.g. {@code -DargLine="-Dfile.encoding=ISO-8859-1"} (check
 * {@code mvn help:effective-pom} that the parent POM's own {@code argLine} does not already pin
 * {@code file.encoding}, and that the flag is honored by the JDK in use). The persistence test at the
 * end is charset-independent (the repositories use XmlFile, which is UTF-8) and must be green
 * everywhere; it guards the UTF-8 file I/O fix against regressing persistence.</p>
 */
@WithJenkins
public class StepsUtf8EncodingTest {

    static final String CHARSET_SENSITIVE = "encoding-charset-sensitive";

    private static final String FILE = "app.cfg";
    private static final String BIN = "BIN";

    private static final String CYRILLIC = "Привіт";       // Privit
    private static final String ACCENTS = "café naïve";
    private static final String EM_DASH = "—";
    private static final String CJK = "日本";
    private static final String KEY_CYRILLIC = "Ключ";               // Klyuch

    private static String charsetNote() {
        return " [JVM default charset = " + Charset.defaultCharset() + "]";
    }

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    @Tag(CHARSET_SENSITIVE)
    public void substitute_nonAsciiValueAndSurroundingText_isWrittenBackByteExactAsUtf8() throws Exception {
        seedBase("enc-val-base", "{\"Greeting\":\"" + CYRILLIC + ", " + ACCENTS + "\"}");
        WorkflowJob job = newPipelineJob(j, "enc-value");
        attachOverride(job, "{}", chainOf(BaseConfigReference.active("enc-val-base")), null);
        byte[] input = ("msg=#{Greeting}# " + EM_DASH + " " + CJK + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("msg=" + CYRILLIC + ", " + ACCENTS + " " + EM_DASH + " " + CJK + "\n")
                .getBytes(StandardCharsets.UTF_8);

        WorkflowRun run = runOk(j, job, writeBytes(FILE, input), substitute("file: '" + FILE + "'"),
                echoBytes(BIN, FILE));

        assertArrayEquals(expected, logBytes(j, run, BIN),
                "substituted file must be byte-exact UTF-8" + charsetNote());
    }

    @Test
    @Tag(CHARSET_SENSITIVE)
    public void substitute_nonAsciiTokenPath_isResolvedFromTheUtf8File() throws Exception {
        seedBase("enc-key-base", "{\"" + KEY_CYRILLIC + "\":{\"Val\":\"ok\"}}");
        WorkflowJob job = newPipelineJob(j, "enc-key");
        attachOverride(job, "{}", chainOf(BaseConfigReference.active("enc-key-base")), null);
        byte[] input = ("v=#{" + KEY_CYRILLIC + ".Val}#").getBytes(StandardCharsets.UTF_8);

        WorkflowRun run = runOk(j, job, writeBytes(FILE, input), substitute("file: '" + FILE + "'"),
                echoBytes(BIN, FILE));

        assertArrayEquals("v=ok".getBytes(StandardCharsets.UTF_8), logBytes(j, run, BIN),
                "a token whose path is non-ASCII must be matched against the config key" + charsetNote());
    }

    @Test
    @Tag(CHARSET_SENSITIVE)
    public void validate_nonAsciiTokenPath_matchesTheConfigKeyInTheUtf8File() throws Exception {
        seedBase("enc-val2-base", "{\"" + KEY_CYRILLIC + "\":{\"Val\":\"ok\"}}");
        WorkflowJob job = newPipelineJob(j, "enc-validate");
        attachOverride(job, "{}", chainOf(BaseConfigReference.active("enc-val2-base")), null);
        byte[] input = ("v=#{" + KEY_CYRILLIC + ".Val}#").getBytes(StandardCharsets.UTF_8);

        // runOk fails the test if the build fails (token read back garbled => "Missing config keys").
        runOk(j, job, writeBytes(FILE, input), validate("file: '" + FILE + "'"));
    }

    @Test
    @Tag(CHARSET_SENSITIVE)
    public void substitute_nonAsciiSecretFromCredentials_isWrittenBackAsUtf8() throws Exception {
        String secret = "päss-" + CJK;
        seedCredential("enc-cred", secret);
        seedBaseWithSecret("enc-sec-base", "Pw", "enc-cred", "{\"Pw\":\"" + PLACEHOLDER + "\"}");
        WorkflowJob job = newPipelineJob(j, "enc-secret");
        attachOverride(job, "{}", chainOf(BaseConfigReference.active("enc-sec-base")), null);
        byte[] input = "pw=#{Pw}#".getBytes(StandardCharsets.UTF_8);

        WorkflowRun run = runOk(j, job, writeBytes(FILE, input), substitute("file: '" + FILE + "'"),
                echoBytes(BIN, FILE));

        assertArrayEquals(("pw=" + secret).getBytes(StandardCharsets.UTF_8), logBytes(j, run, BIN),
                "secret value must be byte-exact UTF-8" + charsetNote());
    }

    @Test
    public void nonAsciiContent_survivesBaseRepositoryAndJobConfigXmlRoundTrip_onAnyDefaultCharset()
            throws Exception {
        String content = "{\"" + KEY_CYRILLIC + "\":\"" + CYRILLIC + " " + ACCENTS + " " + CJK + "\"}";
        seedBase("enc-persist-base", content);
        WorkflowJob job = newPipelineJob(j, "enc-persist");
        attachOverride(job, content, chainOf(BaseConfigReference.active("enc-persist-base")), null);
        job.save();

        j.jenkins.reload();

        ConfigSet reloadedBase = new ConfigSetRepository().findCommon("enc-persist-base");
        assertNotNull(reloadedBase);
        assertEquals(content, reloadedBase.getActiveVersion().getContentJson(), "base content" + charsetNote());
        JobConfigTemplateProperty reloadedOverride = propertyOf(j, "enc-persist");
        assertNotNull(reloadedOverride);
        assertEquals(content, reloadedOverride.getActiveVersion().getContentJson(),
                "job override content" + charsetNote());
    }
}
