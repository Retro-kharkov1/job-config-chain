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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Behavioural coverage for the table filter: drives the real script in a real DOM.
 *
 * <p>Its sibling {@link TableFilterMarkupTest} guards the markup contract, but a contract test
 * cannot tell whether the filter actually hides the right rows - and a review of UI-32 rightly
 * called re-application after an AJAX rebuild the riskiest path in the feature while it had no
 * automated coverage at all. Every defect this area has produced was a runtime one: a header row
 * dragged into the body by core's sorter, an observer that retriggered itself until the tab froze,
 * a filter over a column of identical button labels that hid every row. None were visible to a
 * build.
 *
 * <p>Each test seeds its own Config Sets, following {@link ConfigTemplatesUiTest}'s convention of
 * uniquely-named per-test fixtures. Seeding is not optional here: a first draft of this class
 * skipped when the instance held no rows, and since a fresh JenkinsRule holds none, all four tests
 * passed without asserting anything. Rows the test created itself are also what make the
 * assertions exact rather than relative.
 *
 * <p>Runs against the Config Sets list rather than a Config Set page on purpose: that page carries
 * no Monaco editor, so enabling JavaScript here does not drag an editor bundle into the test run.
 */
public class TableFilterBehaviourTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private void seed(String projectKey, ContentType type) {
        ConfigSetRepository repository = new ConfigSetRepository();
        ConfigSet common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", type);
        int version = common.addVersion(type == ContentType.JSON
                ? "{\"a\":1}"
                : "<root><a>1</a></root>", "seed", "seed-author", 1L);
        common.activate(version);
        repository.save(common);
    }

    private HtmlPage listPageWithScripting() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(true);
        // The page pulls core assets irrelevant to this test; a missing one must not fail the run.
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

    /** Rows the user can actually see, as the filter decides it, by their first cell's text. */
    private static String visibleKeys(HtmlPage page) {
        return js(page,
                "  var t = document.querySelector('table.ctsync-filterable');"
                        + "  if (!t) { return 'NO TABLE'; }"
                        + "  return Array.prototype.slice.call(t.tBodies[0].rows)"
                        + "    .filter(function (r) {"
                        + "      return r.style.display !== 'none'"
                        + "          && !r.classList.contains('ctsync-empty-state-row');"
                        + "    })"
                        + "    .map(function (r) { return r.cells[0].textContent.trim(); })"
                        + "    .sort().join(',');");
    }

    private static String setFilter(HtmlPage page, int column, String value) {
        return js(page,
                "  var t = document.querySelector('table.ctsync-filterable');"
                        + "  var c = t.tHead.rows[0].cells[" + column + "]"
                        + "           .querySelector('.ctsync-filter-input');"
                        + "  if (!c) { return 'NO CONTROL'; }"
                        + "  c.value = '" + value + "';"
                        + "  c.dispatchEvent(new Event(c.tagName === 'SELECT' ? 'change' : 'input',"
                        + "                            { bubbles: true }));"
                        + "  return 'ok';");
    }

    private static String status(HtmlPage page) {
        return js(page,
                "  var s = document.querySelector('.ctsync-filter-status');"
                        + "  return s ? (s.hidden ? 'HIDDEN' : s.textContent) : 'NO STATUS';");
    }

    @Test
    public void aColumnFilterHidesEveryRowThatDoesNotMatch() throws Exception {
        seed("filterbehav-alpha", ContentType.JSON);
        seed("filterbehav-beta", ContentType.JSON);
        seed("othername-gamma", ContentType.JSON);

        HtmlPage page = listPageWithScripting();
        assertEquals("all three seeded rows must render before filtering",
                "filterbehav-alpha,filterbehav-beta,othername-gamma", visibleKeys(page));

        assertEquals("ok", setFilter(page, 0, "filterbehav"));

        assertEquals("only the rows whose name contains the typed text may remain",
                "filterbehav-alpha,filterbehav-beta", visibleKeys(page));
        assertEquals("the status line must report how many of how many survive",
                "2 of 3", status(page));
    }

    @Test
    public void anEnumColumnFiltersByTheExactPickedValue() throws Exception {
        seed("enumbehav-json", ContentType.JSON);
        seed("enumbehav-xml", ContentType.XML);

        HtmlPage page = listPageWithScripting();
        // Column 1 is Type, which declares itself enumerable and so renders a picker. Picking XML
        // must not also match a value that merely contains it.
        assertEquals("ok", setFilter(page, 1, "XML"));

        assertEquals("only the XML row may survive", "enumbehav-xml", visibleKeys(page));
        assertEquals("1 of 2", status(page));
    }

    @Test
    public void aFilterMatchingNothingSaysSoInsteadOfLookingLikeAnEmptyTable() throws Exception {
        seed("nomatch-one", ContentType.JSON);
        seed("nomatch-two", ContentType.JSON);

        HtmlPage page = listPageWithScripting();
        assertEquals("ok", setFilter(page, 0, "zzz-no-such-config-set"));

        assertEquals("no row may survive a filter nothing matches", "", visibleKeys(page));
        // The distinction that matters to a user: an empty table because nothing matched, versus
        // an empty table because there is no data. Without this line the two look identical.
        assertTrue("the status line must say nothing matched, got: " + status(page),
                status(page).toLowerCase().contains("no rows"));
    }

    @Test
    public void anActiveFilterIsReappliedAfterTheTableBodyIsRebuilt() throws Exception {
        seed("rebuild-keep", ContentType.JSON);
        seed("rebuild-drop", ContentType.JSON);
        seed("unrelated-row", ContentType.JSON);

        HtmlPage page = listPageWithScripting();
        assertEquals("ok", setFilter(page, 0, "rebuild-keep"));
        assertEquals("rebuild-keep", visibleKeys(page));

        // Replace every row, exactly as Save and Activate do on the Config Set pages. The rows come
        // back visible; only the block's own observer can hide them again.
        js(page, "  var t = document.querySelector('table.ctsync-filterable');"
                + "  var rows = Array.prototype.slice.call(t.tBodies[0].rows)"
                + "      .map(function (r) {"
                + "        var c = r.cloneNode(true); c.style.display = ''; return c;"
                + "      });"
                + "  t.tBodies[0].innerHTML = '';"
                + "  rows.forEach(function (r) { t.tBodies[0].appendChild(r); });"
                + "  return 'rebuilt';");
        page.getWebClient().waitForBackgroundJavaScript(3000);

        assertEquals("the filter must be re-applied to the rows that replaced the filtered ones - "
                        + "otherwise the table silently shows everything while the control still "
                        + "holds a value",
                "rebuild-keep", visibleKeys(page));
        assertEquals("1 of 3", status(page));
    }

    @Test
    public void clearingTheFilterRestoresEveryRow() throws Exception {
        seed("restore-one", ContentType.JSON);
        seed("restore-two", ContentType.JSON);

        HtmlPage page = listPageWithScripting();
        String all = visibleKeys(page);
        assertEquals("restore-one,restore-two", all);

        assertEquals("ok", setFilter(page, 0, "restore-one"));
        assertEquals("restore-one", visibleKeys(page));

        assertEquals("ok", setFilter(page, 0, ""));
        assertEquals("clearing the filter must bring every row back", all, visibleKeys(page));
        assertEquals("and the status line must retire with it", "HIDDEN", status(page));
    }
}
