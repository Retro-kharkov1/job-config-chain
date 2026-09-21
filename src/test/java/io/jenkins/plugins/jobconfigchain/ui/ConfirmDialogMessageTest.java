package io.jenkins.plugins.jobconfigchain.ui;

import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import org.htmlunit.SilentCssErrorHandler;
import org.htmlunit.html.HtmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the text of the confirmation dialogs - the one surface in this plugin where a rendering
 * bug is most expensive, because it is what an operator reads before destroying a version history.
 *
 * <p>Exists because of a real defect: {@code format} was built on {@code String.replace} with a
 * string pattern, which substitutes the FIRST match only. {@code purge.dialogMessage} names the
 * Config Set twice - "destroys the history of X" and "type X to confirm" - so the prompt shipped
 * with a literal <code>{0}</code> in its second half, in all three locales. Every other template
 * happened to use each placeholder once, so nothing else showed it.
 *
 * <p>The templates come from the real resource bundles and the substitution runs through the real
 * page script, so neither half of the pair can be quietly replaced by a convenient stand-in: a
 * hand-typed template here would test a string this plugin never renders.
 */
public class ConfirmDialogMessageTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private static final String BUNDLE =
            "io.jenkins.plugins.jobconfigchain.ui.ConfigTemplatesRootAction.index";

    private void seed(String projectKey) {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common",
                ContentType.JSON);
        int version = common.addVersion("{\"a\":1}", "seed", "seed-author", 1L);
        common.activate(version);
        repository.save(common);
    }

    private HtmlPage listPageWithScripting() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(true);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.setCssErrorHandler(new SilentCssErrorHandler());
        HtmlPage page = wc.goTo("manage/configTemplates/");
        wc.waitForBackgroundJavaScript(3000);
        return page;
    }

    private static String js(HtmlPage page, String body) {
        return String.valueOf(page.executeJavaScript("(function () {" + body + "})()")
                .getJavaScriptResult());
    }

    /** Runs the page's own formatter over the page's own copy of a template. */
    private static String formatted(HtmlPage page, String datasetKey, String value) {
        return js(page,
                "  var tpl = document.getElementById('ctsyncListStrings');"
                        + "  if (!tpl) { return 'NO STRINGS TEMPLATE'; }"
                        + "  var t = tpl.dataset['" + datasetKey + "'];"
                        + "  if (!t) { return 'NO TEMPLATE ' + '" + datasetKey + "'; }"
                        + "  return window.ctsyncFormatMessage(t, '" + value + "');");
    }

    /** A JS string literal for an arbitrary bundle value, so no dependency is guessed at. */
    private static String jsLiteral(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                // Cyrillic locales are the point of this helper - keep them out of the source
                // encoding question entirely.
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('\"').toString();
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    public void thePurgePromptNamesTheConfigSetInBothPlacesItPromisesTo() throws Exception {
        seed("dialogmsg-purge-demo");
        HtmlPage page = listPageWithScripting();

        String rendered = formatted(page, "purgeDialogMessage", "dialogmsg-purge-demo");

        // The template is the shipped one, so this count is the real contract, not a chosen number.
        String template = ResourceBundle.getBundle(BUNDLE, Locale.ENGLISH)
                .getString("purge.dialogMessage");
        int expected = occurrences(template, "{0}");
        assertTrue("purge.dialogMessage is expected to name the Config Set more than once - if it "
                        + "no longer does, this test has stopped guarding what it was written for",
                expected >= 2);
        assertEquals("every {0} in the purge prompt must become the Config Set's name: "
                        + rendered,
                expected, occurrences(rendered, "dialogmsg-purge-demo"));
        assertFalse("the purge prompt must not show a raw placeholder: " + rendered,
                rendered.contains("{0}"));
    }

    /**
     * The same check in every locale. The defect was locale-independent, but a translator is free
     * to repeat a placeholder in a language that reads better that way, and would have no reason
     * to suspect the renderer could not cope.
     */
    @Test
    public void everyLocalesPurgePromptSubstitutesAllOfItsPlaceholders() throws Exception {
        seed("dialogmsg-locale-demo");
        HtmlPage page = listPageWithScripting();

        for (String tag : new String[] {"", "uk", "ru"}) {
            Locale locale = tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
            String template;
            try {
                template = ResourceBundle.getBundle(BUNDLE, locale).getString("purge.dialogMessage");
            } catch (MissingResourceException missing) {
                throw new AssertionError("locale '" + tag + "' has no purge.dialogMessage", missing);
            }
            String rendered = js(page,
                    "  return window.ctsyncFormatMessage(" + jsLiteral(template) + ", 'x');");
            assertFalse("locale '" + tag + "' leaves a placeholder unrendered: " + rendered,
                    rendered.contains("{0}"));
            assertEquals("locale '" + tag + "' must substitute every {0}",
                    occurrences(template, "{0}"), occurrences(rendered, "x"));
        }
    }

    /**
     * A Config Set name is operator-supplied text, and replacement STRINGS give $ a meaning. A
     * function replacer is what keeps it literal; this pins that choice.
     */
    @Test
    public void aNameContainingDollarSyntaxIsInsertedLiterally() throws Exception {
        seed("dialogmsg-dollar-demo");
        HtmlPage page = listPageWithScripting();

        String rendered = js(page,
                "  return window.ctsyncFormatMessage('type {0} to confirm', \"a$&b$'c\");");
        assertEquals("type a$&b$'c to confirm", rendered);
    }

    /** An argument the caller forgot must read as an untranslated template, never "undefined". */
    @Test
    public void aPlaceholderWithNoArgumentIsLeftStandingRatherThanPrintingUndefined()
            throws Exception {
        seed("dialogmsg-missing-demo");
        HtmlPage page = listPageWithScripting();

        String rendered = js(page, "  return window.ctsyncFormatMessage('{0} then {1}', 'only');");
        assertEquals("only then {1}", rendered);
    }
}
