package io.jenkins.plugins.jobconfigchain.ui;

import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import org.htmlunit.SilentCssErrorHandler;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
@WithJenkins
public class ConfirmDialogMessageTest {

    private JenkinsRule jenkins;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        jenkins = rule;
    }

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
        assertTrue(expected >= 2,
                "purge.dialogMessage is expected to name the Config Set more than once - if it "
                        + "no longer does, this test has stopped guarding what it was written for");
        assertEquals(expected, occurrences(rendered, "dialogmsg-purge-demo"),
                "every {0} in the purge prompt must become the Config Set's name: " + rendered);
        assertFalse(rendered.contains("{0}"),
                "the purge prompt must not show a raw placeholder: " + rendered);
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
            assertFalse(rendered.contains("{0}"),
                    "locale '" + tag + "' leaves a placeholder unrendered: " + rendered);
            assertEquals(occurrences(template, "{0}"), occurrences(rendered, "x"),
                    "locale '" + tag + "' must substitute every {0}");
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

    // ---- Bug fix regression guard (2026-09-28) ---------------------------------------------------
    //
    // Live-browser QA: the Delete/Purge confirm-by-name dialog's OK button was correctly disabled
    // while the input was empty, but became clickable the instant ANY text was typed - including
    // text that did not match the required Config Set name. requireConfirmedName (server side)
    // already refuses a mismatch unconditionally, so this was never a data-safety hole, but it broke
    // the disabled-until-correct promise the button visually makes. The fix (confirmByName's
    // enforceNameMatchOnOpenDialog/findDialogOkButton/wireDialogNameInput, duplicated identically in
    // all three host index.js files) re-disables the OK button on every keystroke that does not
    // exactly match, on top of (never instead of) core's own allowEmpty:false gate.
    //
    // This test runs the ACTUAL shipped window.ctsyncConfirmByName in this page's REAL DOM (scripting
    // enabled, unlike every other test in this class) - not a syntax check, not a string-content
    // assertion. It stands in for core's real dialog.prompt() with a fixture that mirrors the ACTUAL
    // markup a live Jenkins 2.568.3 controller renders (captured 2026-09-28: a native &lt;dialog&gt;
    // element, one data-id="input" text input, one data-id="cancel" button, one data-id="ok" button
    // carrying the class string 'jenkins-button jenkins-button--primary jenkins-!-destructive-color'
    // - see findDialogOkButton's own comment). An earlier version of this fixture instead modelled
    // this plugin's own UNVERIFIED assumption about that shape (a bare 'jenkins-button--destructive'
    // class, no data-id at all) - core never renders that, so the fix's primary selector silently
    // never matched in production, and this fixture could not have caught it because it matched the
    // wrong, hand-guessed shape rather than reality. It also models core's documented
    // allowEmpty:false contract (disable exactly when the field is empty, re-evaluated on every
    // keystroke - the same mechanism the live QA report observed). It proves this plugin's own
    // gating logic is correct given that shape; it cannot prove a live Jenkins controller's dialog
    // actually renders that exact shape - only a real browser against a live controller can do that,
    // which is exactly the kind of check that found this defect in the first place.
    @Test
    public void confirmByNameDialog_okButtonStaysDisabledUntilTheTypedNameMatches() throws Exception {
        seed("dialogmsg-gate-demo");
        HtmlPage page = listPageWithScripting();

        String script =
                "  var dlg = document.createElement('dialog'); dlg.className = 'jenkins-dialog';"
                        + "  var input = document.createElement('input'); input.type = 'text';"
                        + "  input.className = 'jenkins-input'; input.setAttribute('data-id', 'input');"
                        + "  var cancelBtn = document.createElement('button'); cancelBtn.textContent = 'Cancel';"
                        + "  cancelBtn.className = 'jenkins-button'; cancelBtn.setAttribute('data-id', 'cancel');"
                        + "  var okBtn = document.createElement('button'); okBtn.textContent = 'Delete';"
                        + "  okBtn.className = 'jenkins-button jenkins-button--primary jenkins-!-destructive-color';"
                        + "  okBtn.setAttribute('data-id', 'ok');"
                        + "  okBtn.disabled = true;"
                        // Models core's own allowEmpty:false contract, independently of this
                        // plugin's fix under test - re-evaluated on every keystroke, exactly the
                        // mechanism the live QA report observed ("enabled as soon as any text is
                        // typed").
                        + "  input.addEventListener('input', function () { okBtn.disabled = (input.value.length === 0); });"
                        + "  dlg.appendChild(input); dlg.appendChild(cancelBtn); dlg.appendChild(okBtn);"
                        + "  document.body.appendChild(dlg);"
                        + "  window.dialog = { prompt: function () {"
                        + "    return { then: function () { return this; }, catch: function () { return this; } };"
                        + "  } };"
                        + "  window.ctsyncConfirmByName({ expectedName: 'dialogmsg-gate-demo', title: 't', "
                        + "      message: 'm', okLabel: 'Delete', onConfirm: function () {} });"
                        + "  function fireInput() {"
                        + "    var evt;"
                        + "    try { evt = new Event('input', { bubbles: true }); }"
                        + "    catch (e) { evt = document.createEvent('Event'); evt.initEvent('input', true, true); }"
                        + "    input.dispatchEvent(evt);"
                        + "  }"
                        + "  var results = [];"
                        + "  input.value = 'wrong-name'; fireInput(); results.push(okBtn.disabled);"
                        + "  input.value = 'dialogmsg-gate-demo'; fireInput(); results.push(okBtn.disabled);"
                        + "  input.value = 'wrong-again'; fireInput(); results.push(okBtn.disabled);"
                        + "  return JSON.stringify(results);";

        String result = js(page, script);
        assertEquals("[true,false,true]", result, "the OK button must stay disabled for a "
                + "mismatched name, become enabled the instant the typed value exactly matches, "
                + "and go back to disabled the instant it stops matching again: " + result);
    }

    // ---- Bug fix regression guard (2026-09-28), part 2: primary selector, not positional luck ----
    //
    // The test above would ALSO pass if findDialogOkButton fell through to its text-based fallback
    // and happened to land on the right button by position - it proves the gating logic works given
    // SOME correct button reference, not that data-id="ok" is what supplied it. This test makes that
    // distinguishable: a decoy button (no data-id, a label that is not the Cancel label, positioned
    // BEFORE the real OK button) is exactly what the old "first button whose label isn't Cancel"
    // fallback would seize on. If findDialogOkButton ever regresses to picking by position instead
    // of by data-id, this fixture makes it grab the decoy - and the assertions below would fail
    // because the real OK button would never be re-locked on a mismatched name.
    @Test
    public void confirmByNameDialog_findsTheOkButtonByDataIdNotByPositionAmongNonCancelButtons()
            throws Exception {
        seed("dialogmsg-gate-demo-2");
        HtmlPage page = listPageWithScripting();

        String script =
                "  var dlg = document.createElement('dialog'); dlg.className = 'jenkins-dialog';"
                        + "  var input = document.createElement('input'); input.type = 'text';"
                        + "  input.className = 'jenkins-input'; input.setAttribute('data-id', 'input');"
                        + "  var cancelBtn = document.createElement('button'); cancelBtn.textContent = 'Cancel';"
                        + "  cancelBtn.className = 'jenkins-button'; cancelBtn.setAttribute('data-id', 'cancel');"
                        // Decoy: not Cancel-labelled, no data-id, rendered before the real OK button
                        // - a positional ("first non-Cancel button") fallback would return this one.
                        + "  var decoyBtn = document.createElement('button'); decoyBtn.textContent = 'Learn more';"
                        + "  decoyBtn.className = 'jenkins-button';"
                        + "  var okBtn = document.createElement('button'); okBtn.textContent = 'Delete';"
                        + "  okBtn.className = 'jenkins-button jenkins-button--primary jenkins-!-destructive-color';"
                        + "  okBtn.setAttribute('data-id', 'ok');"
                        + "  okBtn.disabled = true;"
                        + "  input.addEventListener('input', function () { okBtn.disabled = (input.value.length === 0); });"
                        + "  dlg.appendChild(input); dlg.appendChild(cancelBtn); dlg.appendChild(decoyBtn);"
                        + "  dlg.appendChild(okBtn);"
                        + "  document.body.appendChild(dlg);"
                        + "  window.dialog = { prompt: function () {"
                        + "    return { then: function () { return this; }, catch: function () { return this; } };"
                        + "  } };"
                        + "  window.ctsyncConfirmByName({ expectedName: 'dialogmsg-gate-demo-2', title: 't', "
                        + "      message: 'm', okLabel: 'Delete', onConfirm: function () {} });"
                        + "  function fireInput() {"
                        + "    var evt;"
                        + "    try { evt = new Event('input', { bubbles: true }); }"
                        + "    catch (e) { evt = document.createEvent('Event'); evt.initEvent('input', true, true); }"
                        + "    input.dispatchEvent(evt);"
                        + "  }"
                        + "  var results = [];"
                        + "  input.value = 'wrong-name'; fireInput(); results.push(okBtn.disabled);"
                        + "  input.value = 'dialogmsg-gate-demo-2'; fireInput(); results.push(okBtn.disabled);"
                        + "  input.value = 'wrong-again'; fireInput(); results.push(okBtn.disabled);"
                        + "  results.push(decoyBtn.disabled);"
                        + "  return JSON.stringify(results);";

        String result = js(page, script);
        assertEquals("[true,false,true,false]", result, "the real OK button (data-id=\"ok\") must "
                + "be the one gated on the typed name even with a non-Cancel decoy button rendered "
                + "earlier in the dialog, and the decoy must never be touched: " + result);
    }
}
