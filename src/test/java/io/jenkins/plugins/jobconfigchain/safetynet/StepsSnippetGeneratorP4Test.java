package io.jenkins.plugins.jobconfigchain.safetynet;

import io.jenkins.plugins.jobconfigchain.steps.ConfigChainSubstituteStep;
import io.jenkins.plugins.jobconfigchain.steps.ConfigChainValidateStep;
import io.jenkins.plugins.jobconfigchain.steps.SetupConfigChainStep;
import org.htmlunit.html.HtmlOption;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.workflow.cps.Snippetizer;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.jenkins.plugins.jobconfigchain.safetynet.ChainTestKit.postForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkpoint B4 (review point C16, gap G2): the three steps are usable from the Snippet Generator - a form
 * with every parameter, a help file per field, defaults that produce an empty snippet argument list, and a
 * generated snippet that round-trips to the same step object. UI only: no step behavior is asserted here.
 */
@WithJenkins
public class StepsSnippetGeneratorP4Test {

    private static final String BASE = "/io/jenkins/plugins/jobconfigchain/steps/";

    private record StepCase(Class<? extends Step> type, String function, List<String> fields) {
    }

    private static final List<StepCase> CASES = List.of(
            new StepCase(ConfigChainValidateStep.class, "configChainValidate",
                    List.of("file", "useBase", "configKey", "version", "encoding")),
            new StepCase(ConfigChainSubstituteStep.class, "configChainSubstitute",
                    List.of("file", "useBase", "configKey", "version", "redeployFromRun", "encoding")),
            new StepCase(SetupConfigChainStep.class, "setupConfigChain",
                    List.of("file", "useBase", "configKey", "version", "redeployFromRun")));

    private static String resource(String path) throws Exception {
        try (InputStream in = StepsSnippetGeneratorP4Test.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String json(StepCase c, String fieldsJson) {
        return "{\"stapler-class\":\"" + c.type().getName() + "\",\"$class\":\"" + c.type().getName() + "\""
                + fieldsJson + "}";
    }

    private static String generate(JenkinsRule j, StepCase c, String fieldsJson) throws Exception {
        ChainTestKit.HttpReply reply = postForm(j, "pipeline-syntax/generateSnippet", "json", json(c, fieldsJson));
        assertEquals(200, reply.status(), reply.body());
        return reply.body().trim();
    }

    // The form offers exactly the data-bound parameters, and every field has help (G2, stub copy).
    @Test
    public void everyFieldHasConfigEntryAndHelp_andStepHasHelp() throws Exception {
        for (StepCase c : CASES) {
            String dir = BASE + c.type().getSimpleName() + "/";
            String jelly = resource(dir + "config.jelly");
            Matcher m = Pattern.compile("<f:entry[^>]*field=\"(\\w+)\"").matcher(jelly);
            List<String> found = new ArrayList<>();
            while (m.find()) {
                found.add(m.group(1));
            }
            assertEquals(c.fields(), found, c.function() + " config.jelly fields");
            for (String field : found) {
                assertFalse(resource(dir + "help-" + field + ".html").isBlank(), c.function() + " help-" + field);
            }
            assertFalse(resource(dir + "help.html").isBlank());
            assertEquals(new TreeSet<>(c.fields()),
                    new TreeSet<>(new DescribableModel<>(c.type()).getParameters().stream()
                            .map(p -> p.getName()).toList()), c.function() + " parameters");
        }
    }

    // Encoding help must state the documented semantics (pipeline-steps.md, "File encoding: UTF-8").
    @Test
    public void encodingHelp_statesDefaultAndStrictSemantics() throws Exception {
        for (String step : List.of("ConfigChainValidateStep", "ConfigChainSubstituteStep")) {
            String help = resource(BASE + step + "/help-encoding.html");
            assertTrue(help.contains("UTF-8"), help);
            assertTrue(help.contains("fallback"), help);
            assertTrue(help.contains("strict"), help);
        }
    }

    // The Pipeline Syntax page lists each new step; selecting it lazily renders real form controls.
    @Test
    public void pipelineSyntaxPage_rendersFormFieldsForEachNewStep(JenkinsRule j) throws Exception {
        for (StepCase c : CASES) {
            JenkinsRule.WebClient wc = j.createWebClient();
            HtmlPage page = wc.goTo("pipeline-syntax/");
            HtmlSelect select = null;
            HtmlOption option = null;
            for (Object node : page.getByXPath("//select")) {
                HtmlSelect candidate = (HtmlSelect) node;
                for (HtmlOption o : candidate.getOptions()) {
                    if (o.getText().startsWith(c.function() + ":")) {
                        select = candidate;
                        option = o;
                    }
                }
            }
            assertNotNull(option, c.function() + " is listed in the generator");
            select.setSelectedAttribute(option, true);
            wc.waitForBackgroundJavaScript(10_000);
            String html = page.asXml();
            assertFalse(html.contains("This step has not yet defined any visual configuration")
                    && !html.contains("name=\"_.file\""), c.function() + " placeholder instead of the form");
            for (String field : c.fields()) {
                assertFalse(page.getElementsByName("_." + field).isEmpty(),
                        c.function() + " form control for " + field);
            }
        }
    }

    // Defaults only: the generated call has only what the user changed.
    @Test
    public void defaultsOnly_generateMinimalSnippet(JenkinsRule j) throws Exception {
        String formDefaults = ",\"file\":\"app.cfg\",\"useBase\":false,\"configKey\":\"\",\"version\":\"\","
                + "\"redeployFromRun\":\"\",\"encoding\":\"\"";
        for (StepCase c : CASES) {
            assertEquals(c.function() + " file: 'app.cfg'", generate(j, c, formDefaults), c.function());
        }
    }

    // Customised values appear; round trip: snippet arguments -> same step object.
    @Test
    public void customisedValues_generateSnippet_andRoundTrip(JenkinsRule j) throws Exception {
        String custom = ",\"file\":\"conf/app.cfg\",\"useBase\":true,\"configKey\":\"shared\",\"version\":\"3\","
                + "\"redeployFromRun\":\"12\",\"encoding\":\"ISO-8859-1\"";
        assertEquals("configChainValidate configKey: 'shared', encoding: 'ISO-8859-1', file: 'conf/app.cfg', "
                        + "useBase: true, version: 3",
                generate(j, CASES.get(0), custom));
        assertEquals("configChainSubstitute configKey: 'shared', encoding: 'ISO-8859-1', file: 'conf/app.cfg', "
                        + "redeployFromRun: '12', useBase: true, version: 3",
                generate(j, CASES.get(1), custom));
        assertEquals("setupConfigChain configKey: 'shared', file: 'conf/app.cfg', redeployFromRun: '12', "
                        + "useBase: true, version: 3",
                generate(j, CASES.get(2), custom));

        ConfigChainSubstituteStep step = new ConfigChainSubstituteStep();
        step.setFile("conf/app.cfg");
        step.setUseBase(true);
        step.setConfigKey("shared");
        step.setVersion(3);
        step.setRedeployFromRun("12");
        step.setEncoding("ISO-8859-1");
        DescribableModel<ConfigChainSubstituteStep> model = new DescribableModel<>(ConfigChainSubstituteStep.class);
        Map<String, ?> described = model.uninstantiate2(step).getArguments();
        ConfigChainSubstituteStep back = model.instantiate(described);
        assertEquals("conf/app.cfg", back.getFile());
        assertTrue(back.isUseBase());
        assertEquals("shared", back.getConfigKey());
        assertEquals(Integer.valueOf(3), back.getVersion());
        assertEquals("12", back.getRedeployFromRun());
        assertEquals("ISO-8859-1", back.getEncoding());
        assertEquals(Snippetizer.object2Groovy(step), generate(j, CASES.get(1), custom));
    }

    // Jelly defaults (unchecked box, empty text/number fields) equal the property defaults.
    @Test
    public void formDefaultsEqualPropertyDefaults(JenkinsRule j) {
        ConfigChainValidateStep v = new ConfigChainValidateStep();
        ConfigChainSubstituteStep s = new ConfigChainSubstituteStep();
        SetupConfigChainStep u = new SetupConfigChainStep();
        assertFalse(v.isUseBase());
        assertFalse(s.isUseBase());
        assertFalse(u.isUseBase());
        assertNull(v.getVersion());
        assertNull(v.getConfigKey());
        assertNull(v.getEncoding());
        assertNull(s.getEncoding());
        assertNull(s.getRedeployFromRun());
        assertNull(u.getRedeployFromRun());
        s.setEncoding("");
        assertNull(s.getEncoding(), "blank form value means not supplied");
        s.setFile("x");
        assertEquals("configChainSubstitute file: 'x'", Snippetizer.object2Groovy(s));
    }
}
