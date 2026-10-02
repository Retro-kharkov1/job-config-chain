package io.jenkins.plugins.jobconfigchain.safetynet;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.util.Secret;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.model.SecretPlaceholder;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Shared helpers for the "safety net" characterization tests of the five business processes
 * (config chain resolution, base versioning, job overrides, deploy pipeline steps, build-pinned
 * redeploy). Every name that a rename (step function, URL segment, form field) would touch is
 * declared ONCE here, so such a rename is a one-line change in this file and nowhere else.
 *
 * <p>Deliberately uses only the public surface: pipeline runs, the plugin's HTTP endpoints and the
 * persisted models/repositories. No package-private or private plugin API is touched.</p>
 */
final class ChainTestKit {

    private ChainTestKit() {
    }

    // ------------------------------------------------------------------------------------------
    // Names that a rename would touch (pipeline step functions)
    // ------------------------------------------------------------------------------------------
    static final String STEP_SETUP = "setupConfigTemplate";
    static final String STEP_VALIDATE = "configTemplateValidate";
    static final String STEP_SUBSTITUTE = "configTemplateSubstitute";

    // ------------------------------------------------------------------------------------------
    // Names that a rename would touch (HTTP surface of the admin pages)
    // ------------------------------------------------------------------------------------------
    /** Global base-config page: {@code <context>/configTemplates/<configKey>/<endpoint>}. */
    static final String GLOBAL_PAGE_URL_PREFIX = "configTemplates/";
    /** Job page: {@code <context>/job/<jobName>/configTemplates/<endpoint>}. */
    static final String JOB_PAGE_URL_SEGMENT = "configTemplates";
    static final String ENDPOINT_SAVE = "submitSave";
    static final String ENDPOINT_ACTIVATE = "activateVersion";

    static final String FIELD_CONTENT = "content";
    static final String FIELD_NOTE = "note";
    static final String FIELD_ACTIVATE = "activate";
    static final String FIELD_BASE_CHAIN = "baseChainJson";
    static final String FIELD_VERSION = "version";

    static final String AUTHOR = "safety-net";

    // ------------------------------------------------------------------------------------------
    // Pipeline script building
    // ------------------------------------------------------------------------------------------

    static String setup(String namedArgs) {
        return STEP_SETUP + "(" + namedArgs + ")";
    }

    static String validate(String namedArgs) {
        return STEP_VALIDATE + "(" + namedArgs + ")";
    }

    static String substitute(String namedArgs) {
        return STEP_SUBSTITUTE + "(" + namedArgs + ")";
    }

    /** Groovy single-quoted literal for {@code s}; handles backslash, quote, CR and LF. */
    static String groovyLiteral(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n") + "'";
    }

    static String writeText(String file, String text) {
        return "writeFile file: " + groovyLiteral(file) + ", text: " + groovyLiteral(text);
    }

    /** Writes the exact bytes (no charset involved anywhere) via the pipeline's Base64 mode. */
    static String writeBytes(String file, byte[] bytes) {
        return "writeFile file: " + groovyLiteral(file) + ", text: '"
                + Base64.getEncoder().encodeToString(bytes) + "', encoding: 'Base64'";
    }

    /** Echoes {@code TAG:<file text>} on one log line. Only for single-line, ASCII-safe files. */
    static String echoText(String tag, String file) {
        return "echo \"" + tag + ":${readFile('" + file + "')}\"";
    }

    /** Echoes {@code TAG:<base64 of the raw file bytes>} on one log line. Keep files short (< ~50 bytes). */
    static String echoBytes(String tag, String file) {
        return "echo \"" + tag + ":${readFile(file: '" + file + "', encoding: 'Base64')}\"";
    }

    static WorkflowJob newPipelineJob(JenkinsRule j, String name) throws Exception {
        return j.createProject(WorkflowJob.class, name);
    }

    /** Replaces the job's definition by {@code node { lines }} and runs one build to {@code expected}. */
    static WorkflowRun run(JenkinsRule j, WorkflowJob job, Result expected, String... lines) throws Exception {
        job.setDefinition(new CpsFlowDefinition("node {\n" + String.join("\n", lines) + "\n}", true));
        return j.assertBuildStatus(expected, job.scheduleBuild2(0));
    }

    /** Like {@link #run} but takes the complete script (for pipelines with blocks outside {@code node}). */
    static WorkflowRun runScript(JenkinsRule j, WorkflowJob job, Result expected, String script) throws Exception {
        job.setDefinition(new CpsFlowDefinition(script, true));
        return j.assertBuildStatus(expected, job.scheduleBuild2(0));
    }

    static WorkflowRun runOk(JenkinsRule j, WorkflowJob job, String... lines) throws Exception {
        return run(j, job, Result.SUCCESS, lines);
    }

    /** The text after {@code TAG:} on the first console line that starts with it. */
    static String logValue(JenkinsRule j, Run<?, ?> run, String tag) throws IOException {
        String needle = tag + ":";
        String log = j.getLog(run);
        for (String line : log.split("\\R")) {
            if (line.startsWith(needle)) {
                return line.substring(needle.length());
            }
        }
        throw new AssertionError("No console line starting with '" + needle + "' in log:\n" + log);
    }

    static byte[] logBytes(JenkinsRule j, Run<?, ?> run, String tag) throws IOException {
        return Base64.getMimeDecoder().decode(logValue(j, run, tag));
    }

    /** Prefix every message of the plugin's steps carries in the build log. */
    static final String STEP_LOG_PREFIX = "[configTemplateSync]";

    /** Placeholder standing for the job name inside golden step-log files. */
    static final String JOB_PLACEHOLDER = "@JOB@";

    /**
     * The plugin's own console lines of a build (those starting with {@link #STEP_LOG_PREFIX}), one per
     * line, with the quoted job name {@code 'jobName'} replaced by {@link #JOB_PLACEHOLDER} so the text
     * can be compared across differently named jobs. Everything else in the console (Pipeline noise,
     * workspace paths) is deliberately ignored.
     */
    static String stepLogLines(String consoleLog, String jobName) {
        StringBuilder sb = new StringBuilder();
        for (String line : consoleLog.split("\\R")) {
            if (line.startsWith(STEP_LOG_PREFIX)) {
                sb.append(line.replace("'" + jobName + "'", "'" + JOB_PLACEHOLDER + "'")).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Seeding (persisted models, not private API)
    // ------------------------------------------------------------------------------------------

    static ConfigSet seedBase(String configKey, String json) {
        return seedBase(configKey, json, ContentType.JSON);
    }

    static ConfigSet seedBase(String configKey, String content, ContentType type) {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet base = new ConfigSet(configKey, ConfigSetRole.COMMON, null, "Common", type);
        int v = base.addVersion(content, "seed", AUTHOR, 1L);
        base.activate(v);
        repository.save(base);
        return base;
    }

    /** Seeds a base whose {@code secretPath} is declared secret (bound to {@code credentialId}). */
    static ConfigSet seedBaseWithSecret(String configKey, String secretPath, String credentialId, String json) {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet base = new ConfigSet(configKey, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        base.putSecretManifestEntry(secretPath, credentialId);
        int v = base.addVersion(json, "seed", AUTHOR, 1L);
        base.activate(v);
        repository.save(base);
        return base;
    }

    /** Appends a version to an existing base (re-read from disk first), optionally activating it. */
    static int addBaseVersion(String configKey, String content, String note, boolean activate) {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet base = repository.findCommon(configKey);
        int v = base.addVersion(content, note, AUTHOR, System.currentTimeMillis());
        if (activate) {
            base.activate(v);
        }
        repository.save(base);
        return v;
    }

    static void seedCredential(String id, String secret) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, id, "safety-net credential", Secret.fromString(secret)));
        SystemCredentialsProvider.getInstance().save();
    }

    static List<BaseConfigReference> chainOf(BaseConfigReference... references) {
        return Arrays.asList(references);
    }

    /** Attaches a job-level override (its own version 1, activated) to a pipeline job. */
    static JobConfigTemplateProperty attachOverride(WorkflowJob job, String overrideContent,
                                                    List<BaseConfigReference> chain, String contentTypeOrNull)
            throws Exception {
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        int v = property.addVersion(overrideContent, "seed", AUTHOR, 1L, chain, contentTypeOrNull);
        property.activate(v);
        job.addProperty(property);
        return property;
    }

    /** Appends a version to the job's existing override (re-read from the job), optionally activating it. */
    static int addOverrideVersion(Job<?, ?> job, String overrideContent, List<BaseConfigReference> chain,
                                  boolean activate) throws IOException {
        JobConfigTemplateProperty property = job.getProperty(JobConfigTemplateProperty.class);
        int v = property.addVersion(overrideContent, "bump", AUTHOR, System.currentTimeMillis(), chain, null);
        if (activate) {
            property.activate(v);
        }
        job.save();
        return v;
    }

    /** The job's override as currently held by the live Jenkins instance (re-fetched, never cached). */
    static JobConfigTemplateProperty propertyOf(JenkinsRule j, String jobFullName) {
        Job<?, ?> job = j.jenkins.getItemByFullName(jobFullName, Job.class);
        return job == null ? null : job.getProperty(JobConfigTemplateProperty.class);
    }

    // ------------------------------------------------------------------------------------------
    // HTTP against the plugin's own endpoints
    // ------------------------------------------------------------------------------------------

    record HttpReply(int status, String body) {
        /** Successful classic form saves answer with a redirect back to the page. */
        boolean redirected() {
            return status == 302;
        }

        JSONObject json() {
            return JSONObject.fromObject(body);
        }
    }

    static String globalEndpoint(String configKey, String endpoint) {
        return GLOBAL_PAGE_URL_PREFIX + configKey + "/" + endpoint;
    }

    static String jobEndpoint(String jobName, String endpoint) {
        return "job/" + jobName + "/" + JOB_PAGE_URL_SEGMENT + "/" + endpoint;
    }

    static String baseChainJson(BaseConfigReference... references) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < references.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"projectKey\":\"").append(references[i].getProjectKey())
                    .append("\",\"pinMode\":\"").append(references[i].getPinMode().name())
                    .append("\",\"pinnedVersionNumber\":").append(references[i].getPinnedVersionNumber())
                    .append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * POSTs a classic form to {@code relativeUrl} (relative to the Jenkins context path) without
     * following redirects and without crumbs, and returns status + body. {@code namesAndValues} is a
     * flat name,value,name,value... list.
     */
    static HttpReply postForm(JenkinsRule j, String relativeUrl, String... namesAndValues) throws Exception {
        j.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false);
        wc.getOptions().setJavaScriptEnabled(false);
        List<NameValuePair> parameters = new ArrayList<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            parameters.add(new NameValuePair(namesAndValues[i], namesAndValues[i + 1]));
        }
        WebRequest request = new WebRequest(new URL(wc.getContextPath() + relativeUrl), HttpMethod.POST);
        request.setRequestParameters(parameters);
        Page page = wc.getPage(request);
        WebResponse response = page.getWebResponse();
        return new HttpReply(response.getStatusCode(), response.getContentAsString());
    }

    /** Saves a new version through the global base-config page. {@code activate} maps to the page's flag. */
    static HttpReply saveBaseVersion(JenkinsRule j, String configKey, String content, String note,
                                     boolean activate) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(FIELD_CONTENT, content, FIELD_NOTE, note));
        if (activate) {
            args.add(FIELD_ACTIVATE);
            args.add("true");
        }
        return postForm(j, globalEndpoint(configKey, ENDPOINT_SAVE), args.toArray(new String[0]));
    }

    static HttpReply activateBaseVersion(JenkinsRule j, String configKey, int version) throws Exception {
        return postForm(j, globalEndpoint(configKey, ENDPOINT_ACTIVATE), FIELD_VERSION, String.valueOf(version));
    }

    /** Saves a new version of the job's own override through the job's Config Templates page. */
    static HttpReply saveJobVersion(JenkinsRule j, String jobName, String content, String note,
                                    BaseConfigReference[] chain, boolean activate) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(FIELD_CONTENT, content, FIELD_NOTE, note,
                FIELD_BASE_CHAIN, baseChainJson(chain)));
        if (activate) {
            args.add(FIELD_ACTIVATE);
            args.add("true");
        }
        return postForm(j, jobEndpoint(jobName, ENDPOINT_SAVE), args.toArray(new String[0]));
    }

    static HttpReply activateJobVersion(JenkinsRule j, String jobName, int version) throws Exception {
        return postForm(j, jobEndpoint(jobName, ENDPOINT_ACTIVATE), FIELD_VERSION, String.valueOf(version));
    }

    /** The literal stored in a secret-declared leaf, re-exported so tests need not import the model class. */
    static final String PLACEHOLDER = SecretPlaceholder.VALUE;
}
