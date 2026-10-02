package io.jenkins.plugins.jobconfigchain.safetynet;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.PLACEHOLDER;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.echoBytes;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.setup;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.substitute;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.validate;
import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.writeText;

/**
 * The single definition of the "pinned previous build" upgrade scenario, shared by
 * {@link PinnedFixtureGeneratorTest} (which ran it ONCE on the pre-fix code to produce the committed
 * {@code @LocalData} fixture and golden files) and {@link UpgradeFromPinnedBuildTest} (which loads that
 * fixture with the current code and replays the same scripts). Keeping both sides on one definition is
 * what makes the golden comparison meaningful.
 *
 * <p>All deployed content is ASCII on purpose: the encoding behaviour is covered separately by
 * {@link StepsUtf8EncodingTest}; mixing it in here would make the upgrade tests depend on the default
 * charset of the JVM that runs them.</p>
 */
final class PinnedScenario {

    private PinnedScenario() {
    }

    /** Resource directory name under {@code UpgradeFromPinnedBuildTest/} holding the pinned Jenkins home. */
    static final String FIXTURE = "pinned_build";

    static final String FILE = "app.cfg";
    static final String BIN = "BIN";

    static final String CRED = "fx-cred";
    static final String CRED_VALUE = "FixtureS3cret";

    // ---- finished-builds part: job with base chain + override, 3 builds ------------------------
    static final String JOB = "fx-job";
    static final String BASE = "fx-base";
    static final String BASE2 = "fx-base2";
    static final String TEMPLATE =
            "host=#{Db.Host}# port=#{Db.Port}# pw=#{Db.Password}# name=#{App.Name}# own=#{Own}#";

    static final String BASE_V1 = "{\"Db\":{\"Host\":\"base-host-v1\",\"Password\":\"" + PLACEHOLDER
            + "\"},\"App\":{\"Name\":\"fixture\"}}";
    static final String BASE_V2 = "{\"Db\":{\"Host\":\"base-host-v2\",\"Password\":\"" + PLACEHOLDER
            + "\"},\"App\":{\"Name\":\"fixture\"}}";
    static final String BASE2_V1 = "{\"Db\":{\"Port\":5432}}";
    static final String BASE2_V2 = "{\"Db\":{\"Port\":6543}}";
    static final String OWN_V1 = "{\"Own\":\"own-v1\"}";
    static final String OWN_V2 = "{\"Own\":\"own-v2\"}";

    // ---- in-flight part ----------------------------------------------------------------------
    static final String IF_JOB_1 = "fx-inflight-1";
    static final String IF_JOB_2 = "fx-inflight-2";
    static final String IF_CONTROL = "fx-if-control";
    static final String IF_BASE = "fx-if-base";
    static final String IF_BASE_JSON = "{\"Db\":{\"Host\":\"if-host\",\"Password\":\"" + PLACEHOLDER + "\"}}";
    static final String IF_OWN = "{\"Own\":\"if-own\"}";
    static final String IF_TEMPLATE = "host=#{Db.Host}# pw=#{Db.Password}# own=#{Own}#";

    /** The pause: a Pipeline {@code waitUntil} released by setting the build description to {@link #GO}. */
    static final String GO = "go";
    static final String GATE = "waitUntil { currentBuild.description == '" + GO + "' }";
    /** Console text {@code waitUntil} prints each time it polls and finds the condition false. */
    static final String GATE_POLL_MESSAGE = "Will try again";

    // ---- golden file names (relative to the fixture home) -----------------------------------
    static String goldenDeployed(String key) {
        return "golden/" + key + ".deployed";
    }

    static String goldenStepLog(String key) {
        return "golden/" + key + ".steplog";
    }

    static final String G_BUILD1 = "fx-job-build1-live-before-bump";
    static final String G_BUILD2 = "fx-job-build2-live-after-bump";
    static final String G_BUILD3 = "fx-job-build3-redeploy-of-1";
    static final String G_INFLIGHT = "fx-inflight-control";

    // ---- scripts (identical on the generating and the consuming side) -----------------------

    static String nodeBlock(String... lines) {
        return "node {\n" + String.join("\n", lines) + "\n}";
    }

    /** Builds #1 and #2 of {@link #JOB}: all three steps, zero-argument after setup. */
    static String liveDeployScript() {
        return nodeBlock(
                setup("file: '" + FILE + "'"),
                writeText(FILE, TEMPLATE),
                validate(""),
                substitute(""),
                echoBytes(BIN, FILE));
    }

    /** Build #3 of {@link #JOB}: redeploy of build #1 driven through setup. */
    static String redeployOfBuild1Script() {
        return nodeBlock(
                setup("file: '" + FILE + "', redeployFromRun: '1'"),
                writeText(FILE, TEMPLATE),
                validate("version: 1"),
                substitute(""),
                echoBytes(BIN, FILE));
    }

    /** Reference run for the in-flight jobs: same steps, no pause. */
    static String controlScript() {
        return nodeBlock(
                setup("file: '" + FILE + "'"),
                writeText(FILE, IF_TEMPLATE),
                validate(""),
                substitute(""),
                echoBytes(BIN, FILE));
    }

    /** Paused right after {@code setupConfigTemplate}; validate and substitute still to run. */
    static String inflight1Script() {
        return setup("file: '" + FILE + "'") + "\n"
                + GATE + "\n"
                + nodeBlock(
                        writeText(FILE, IF_TEMPLATE),
                        validate(""),
                        substitute(""),
                        echoBytes(BIN, FILE));
    }

    /** Setup and validate already done in a finished {@code node} block; paused before substitute. */
    static String inflight2Script() {
        return nodeBlock(
                        setup("file: '" + FILE + "'"),
                        writeText(FILE, IF_TEMPLATE),
                        validate(""))
                + "\n" + GATE + "\n"
                + nodeBlock(
                        writeText(FILE, IF_TEMPLATE),
                        substitute(""),
                        echoBytes(BIN, FILE));
    }
}
