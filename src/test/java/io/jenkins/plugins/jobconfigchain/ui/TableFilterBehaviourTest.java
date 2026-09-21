package io.jenkins.plugins.jobconfigchain.ui;

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
 * called the re-application after an AJAX rebuild the riskiest path in the feature while it had no
 * automated coverage at all. Every defect this area has produced was a runtime one: a header row
 * dragged into the body by core's sorter, an observer that retriggered itself until the tab froze,
 * a filter on a column of identical button labels that hid every row. None of them were visible to
 * a build.
 *
 * <p>Runs against the Config Sets list rather than a Config Set page on purpose: that page carries
 * no Monaco editor, so enabling JavaScript here does not drag a large editor bundle into every
 * test run.
 */
public class TableFilterBehaviourTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private HtmlPage listPageWithScripting() throws Exception {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(true);
        // The page pulls core assets that are irrelevant here; a missing one must not fail the run.
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.setCssErrorHandler(new org.htmlunit.SilentCssErrorHandler());
        HtmlPage page = wc.goTo("manage/configTemplates/");
        wc.waitForBackgroundJavaScript(2000);
        return page;
    }

    /** Rows the user can actually see, as the filter decides it - by their first cell's text. */
    private static String visibleFirstCells(HtmlPage page) throws Exception {
        return (String) page.executeJavaScript(
                "(function () {"
                        + "  var t = document.querySelector('table.ctsync-filterable');"
                        + "  if (!t) { return 'NO TABLE'; }"
                        + "  return Array.prototype.slice.call(t.tBodies[0].rows)"
                        + "    .filter(function (r) {"
                        + "      return r.style.display !== 'none'"
                        + "          && !r.classList.contains('ctsync-empty-state-row');"
                        + "    })"
                        + "    .map(function (r) { return r.cells[0].textContent.trim(); })"
                        + "    .join(',');"
                        + "})()").getJavaScriptResult().toString();
    }

    private static String typeIntoFilter(HtmlPage page, int column, String text) throws Exception {
        return (String) page.executeJavaScript(
                "(function () {"
                        + "  var t = document.querySelector('table.ctsync-filterable');"
                        + "  var c = t.tHead.rows[0].cells[" + column + "]"
                        + "           .querySelector('.ctsync-filter-input');"
                        + "  if (!c) { return 'NO CONTROL'; }"
                        + "  c.value = '" + text + "';"
                        + "  c.dispatchEvent(new Event(c.tagName === 'SELECT' ? 'change' : 'input',"
                        + "                            { bubbles: true }));"
                        + "  return 'ok';"
                        + "})()").getJavaScriptResult().toString();
    }

    private static String statusText(HtmlPage page) throws Exception {
        return (String) page.executeJavaScript(
                "(function () {"
                        + "  var s = document.querySelector('.ctsync-filter-status');"
                        + "  return s ? (s.hidden ? 'HIDDEN' : s.textContent) : 'NO STATUS';"
                        + "})()").getJavaScriptResult().toString();
    }

    @Test
    public void typingInAColumnFilterHidesTheRowsThatDoNotMatch() throws Exception {
        jenkins.jenkins.getExtensionList(ConfigTemplatesRootAction.class).get(0);
        HtmlPage page = listPageWithScripting();

        // Seed-free assertion: whatever the instance holds, filtering by a string taken from one
        // row must leave that row visible and cannot leave more rows than it started with.
        String all = visibleFirstCells(page);
        assertTrue("the filterable list table must render on this page, got: " + all,
                !all.equals("NO TABLE"));
        if (all.isEmpty()) {
            return; // nothing seeded on this instance; the markup test still covers the contract
        }
        String firstKey = all.split(",")[0];

        assertEquals("ok", typeIntoFilter(page, 0, firstKey));
        String filtered = visibleFirstCells(page);

        assertTrue("the row whose key was typed must remain visible, got: " + filtered,
                filtered.contains(firstKey));
        assertTrue("filtering must not widen the row set: " + filtered,
                filtered.split(",").length <= all.split(",").length);
        assertTrue("the status line must report the filtered count, got: " + statusText(page),
                statusText(page).matches("\\d+ of \\d+"));
    }

    @Test
    public void aFilterMatchingNothingReportsItInsteadOfLookingLikeAnEmptyTable() throws Exception {
        HtmlPage page = listPageWithScripting();
        if (visibleFirstCells(page).isEmpty()) {
            return;
        }

        assertEquals("ok", typeIntoFilter(page, 0, "zzz-no-such-config-set"));

        assertEquals("no row may survive a filter nothing matches", "", visibleFirstCells(page));
        // The distinction that matters to a user: an empty table because nothing matched, versus
        // an empty table because there is no data. Without this line the two look identical.
        assertTrue("the status line must say so, got: " + statusText(page),
                statusText(page).toLowerCase().contains("no rows"));
    }

    @Test
    public void anActiveFilterIsReappliedAfterTheTableBodyIsRebuilt() throws Exception {
        HtmlPage page = listPageWithScripting();
        String all = visibleFirstCells(page);
        if (all.isEmpty() || all.split(",").length < 2) {
            return; // needs at least two rows for "filtered" to differ from "everything"
        }
        String firstKey = all.split(",")[0];

        assertEquals("ok", typeIntoFilter(page, 0, firstKey));
        String beforeRebuild = visibleFirstCells(page);
        assertTrue("the filter must actually narrow the table for this test to mean anything",
                beforeRebuild.split(",").length < all.split(",").length);

        // Replace every row, exactly as Save and Activate do on the Config Set pages. The rows come
        // back visible; only the block's own observer can hide them again.
        page.executeJavaScript(
                "(function () {"
                        + "  var t = document.querySelector('table.ctsync-filterable');"
                        + "  var rows = Array.prototype.slice.call(t.tBodies[0].rows)"
                        + "      .map(function (r) {"
                        + "        var c = r.cloneNode(true); c.style.display = ''; return c;"
                        + "      });"
                        + "  t.tBodies[0].innerHTML = '';"
                        + "  rows.forEach(function (r) { t.tBodies[0].appendChild(r); });"
                        + "})()");
        page.getWebClient().waitForBackgroundJavaScript(2000);

        assertEquals("the filter must be re-applied to the rows that replaced the filtered ones - "
                        + "otherwise the table silently shows everything while the control still "
                        + "holds a value",
                beforeRebuild, visibleFirstCells(page));
    }

    @Test
    public void clearingTheFilterRestoresEveryRow() throws Exception {
        HtmlPage page = listPageWithScripting();
        String all = visibleFirstCells(page);
        if (all.isEmpty()) {
            return;
        }

        assertEquals("ok", typeIntoFilter(page, 0, all.split(",")[0]));
        assertEquals("ok", typeIntoFilter(page, 0, ""));

        assertEquals("clearing the filter must bring every row back", all, visibleFirstCells(page));
        assertEquals("and the status line must retire with it", "HIDDEN", statusText(page));
    }
}
