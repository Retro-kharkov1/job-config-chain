package io.jenkins.plugins.jobconfigchain.ui;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import net.sf.json.JSONObject;
import org.htmlunit.html.HtmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The delete / restore / purge lifecycle as it is actually reachable: through the page objects the
 * browser talks to, not through the repository underneath.
 *
 * <p>The refusals matter more than the happy paths here. Every one of them is a place where a wrong
 * answer is silent - a Config Set that vanishes while a job still needs it, an archive overwritten
 * by a page that thought it was creating something new, or a name check that only ever ran in the
 * browser. Each test below names the thing that would otherwise go unnoticed.</p>
 *
 * <p>Endpoints are called directly in Java rather than driven through the browser. These are
 * {@code @JavaScriptMethod}s with no classic {@code do*} sibling, so a direct call IS the
 * production path - the same code a proxy call reaches.</p>
 */
public class ConfigSetLifecycleUiTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private final ConfigSetRepository repository = new ConfigSetRepository();

    private ConfigSet seed(String projectKey) {
        ConfigSet set = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        int v1 = set.addVersion("{\"a\":1}", "initial", "alice", 1L);
        set.addVersion("{\"a\":2}", "second", "bob", 2L);
        set.activate(v1);
        set.putSecretManifestEntry("db.password", "cred-id");
        repository.save(set);
        return set;
    }

    private CommonConfigSetPage page(String projectKey) {
        return new CommonConfigSetPage(projectKey, repository);
    }

    private JSONObject confirm(String name) {
        JSONObject payload = new JSONObject();
        payload.put("confirmName", name);
        return payload;
    }

    /** A job whose versions name the given keys, activating the version at {@code activeIndex}. */
    private void jobReferencing(String jobName, List<String> keyPerVersion, int activeIndex)
            throws Exception {
        FreeStyleProject job = jenkins.createFreeStyleProject(jobName);
        JobConfigTemplateProperty property = new JobConfigTemplateProperty();
        List<Integer> numbers = new ArrayList<>();
        for (String key : keyPerVersion) {
            List<BaseConfigReference> chain = new ArrayList<>();
            if (key != null) {
                chain.add(BaseConfigReference.active(key));
            }
            numbers.add(property.addVersion("{}", "seed", "alice", 1L, chain, "JSON"));
        }
        property.activate(numbers.get(activeIndex));
        job.addProperty(property);
    }

    // ---- Refusals -------------------------------------------------------------------------------

    @Test
    public void deleteIsRefusedWhenTheTypedNameDoesNotMatch() throws Exception {
        seed("typo-app");

        // The browser compares the typed name too, but that is presentation: this endpoint is a
        // real URL and is reachable without the page at all.
        JSONObject result = page("typo-app").jsDeleteConfigSet(confirm("typo-ap").toString());

        assertFalse(result.getBoolean("ok"));
        assertEquals("NAME_MISMATCH", result.getString("errorCode"));
        assertNotNull("nothing may have been withdrawn", repository.findCommon("typo-app"));
    }

    @Test
    public void deleteIsRefusedWhileAJobIsUsingItRightNow() throws Exception {
        seed("in-use-app");
        jobReferencing("uses-it", List.of("in-use-app"), 0);

        JSONObject result = page("in-use-app").jsDeleteConfigSet(confirm("in-use-app").toString());

        assertFalse(result.getBoolean("ok"));
        assertEquals("REFERENCED", result.getString("errorCode"));
        assertEquals("the operator must be told which job, not merely that something blocks it",
                "uses-it", result.getJSONArray("activeJobs").getJSONObject(0).getString("fullName"));
        assertNotNull(repository.findCommon("in-use-app"));
    }

    @Test
    public void deleteIsAllowedWhenOnlyAnOlderJobVersionNamesIt() throws Exception {
        seed("historical-app");
        // v1 names it, v2 does not, v2 is active. Nothing breaks today, and withdrawal is
        // reversible - so this must go through, with the fact still reported.
        jobReferencing("rolled-forward", java.util.Arrays.asList("historical-app", null), 1);

        JSONObject preflight = page("historical-app").jsDeletePreflight();
        assertFalse("an old reference must not block a reversible withdrawal",
                preflight.getBoolean("blocked"));
        assertEquals("but it must still be reported, because a rollback would fail",
                1, preflight.getJSONArray("historicalJobs").size());

        JSONObject result = page("historical-app").jsDeleteConfigSet(confirm("historical-app").toString());
        assertTrue(result.getBoolean("ok"));
        assertNull(repository.findCommon("historical-app"));
    }

    @Test
    public void purgeIsRefusedWhenEvenAnOldJobVersionNamesIt() throws Exception {
        seed("historical-app");
        jobReferencing("rolled-forward", java.util.Arrays.asList("historical-app", null), 1);
        page("historical-app").jsDeleteConfigSet(confirm("historical-app").toString());

        // The asymmetry with the test above is the whole policy: there is no restore after a purge
        // to repair a rollback it would have broken.
        JSONObject result = page("historical-app").jsPurgeConfigSet(confirm("historical-app").toString());

        assertFalse(result.getBoolean("ok"));
        assertEquals("REFERENCED", result.getString("errorCode"));
        assertNotNull("the record must survive a refused purge",
                repository.findCommonIncludingDeleted("historical-app"));
    }

    @Test
    public void purgeIsRefusedForAConfigSetThatIsStillLive() throws Exception {
        seed("live-app");

        JSONObject result = page("live-app").jsPurgeConfigSet(confirm("live-app").toString());

        assertFalse(result.getBoolean("ok"));
        assertEquals("NOT_DELETED", result.getString("errorCode"));
        assertNotNull(repository.findCommon("live-app"));
    }

    // ---- The archive-clobbering hazard ----------------------------------------------------------

    @Test
    public void bindingASecretOnADeletedPageIsRefusedAndLeavesTheArchiveIntact() throws Exception {
        seed("withdrawn-app");
        page("withdrawn-app").jsDeleteConfigSet(confirm("withdrawn-app").toString());

        // addSecret creates a brand-new ConfigSet when the live lookup returns null - which a
        // withdrawn record now does. Without the guard this would write an empty record over two
        // versions of history.
        JSONObject result = page("withdrawn-app")
                .jsAddSecret("{\"path\":\"new.secret\",\"credentialId\":\"cred-id\"}");

        assertFalse(result.getBoolean("ok"));
        assertEquals("DELETED", result.getString("errorCode"));

        ConfigSet survivor = repository.findCommonIncludingDeleted("withdrawn-app");
        assertEquals("the archived history must be untouched", 2, survivor.getVersions().size());
        assertTrue(survivor.isDeleted());
    }

    @Test
    public void savingOverADeletedNameIsRefused() throws Exception {
        seed("withdrawn-app");
        page("withdrawn-app").jsDeleteConfigSet(confirm("withdrawn-app").toString());

        // What creating a new Config Set with a withdrawn name amounts to: this page is reached for
        // any key, and a save is how a Config Set comes into being.
        JSONObject result = page("withdrawn-app")
                .jsSave("{\"content\":\"{}\",\"note\":\"new\",\"activate\":false}");

        assertFalse(result.getBoolean("ok"));
        assertEquals(2, repository.findCommonIncludingDeleted("withdrawn-app").getVersions().size());
    }

    // ---- Happy paths ----------------------------------------------------------------------------

    @Test
    public void deleteKeepsTheHistoryAndRestorePutsItBackWhole() throws Exception {
        seed("round-trip-app");

        assertTrue(page("round-trip-app").jsDeleteConfigSet(confirm("round-trip-app").toString())
                .getBoolean("ok"));

        ConfigSet withdrawn = repository.findCommonIncludingDeleted("round-trip-app");
        assertTrue(withdrawn.isDeleted());
        assertEquals(2, withdrawn.getVersions().size());
        assertEquals("cred-id", withdrawn.getSecretsManifest().get("db.password"));
        assertNull("it must be gone from the live inventory", repository.findCommon("round-trip-app"));

        assertTrue(page("round-trip-app").jsRestoreConfigSet().getBoolean("ok"));

        ConfigSet restored = repository.findCommon("round-trip-app");
        assertNotNull(restored);
        assertEquals(2, restored.getVersions().size());
        assertEquals(1, restored.getActiveVersionNumber());
        assertEquals("cred-id", restored.getSecretsManifest().get("db.password"));
    }

    @Test
    public void purgeFreesTheNameForReuse() throws Exception {
        seed("purge-app");
        page("purge-app").jsDeleteConfigSet(confirm("purge-app").toString());

        assertTrue(page("purge-app").jsPurgeConfigSet(confirm("purge-app").toString())
                .getBoolean("ok"));

        assertNull(repository.findCommonIncludingDeleted("purge-app"));
        // And the name is creatable again, which is what makes purge the way out of a reserved one.
        seed("purge-app");
        assertNotNull(repository.findCommon("purge-app"));
    }

    // ---- Rendering ------------------------------------------------------------------------------

    @Test
    public void theDeleteButtonRendersOnALiveConfigSetAndNotOnAnAbsentOne() throws Exception {
        seed("rendered-app");

        HtmlPage live = jenkins.createWebClient().goTo("manage/configTemplates/rendered-app/");
        assertNotNull("a live Config Set offers the delete action",
                live.getElementById("deleteConfigSetBtn"));

        HtmlPage absent = jenkins.createWebClient().goTo("manage/configTemplates/never-created/");
        assertNull("there is nothing to delete before the first save",
                absent.getElementById("deleteConfigSetBtn"));
    }

    @Test
    public void aDeletedPageOffersRestoreAndPurgeAndNeverTheCreateItNotice() throws Exception {
        seed("withdrawn-app");
        page("withdrawn-app").jsDeleteConfigSet(confirm("withdrawn-app").toString());

        HtmlPage rendered = jenkins.createWebClient().goTo("manage/configTemplates/withdrawn-app/");
        String text = rendered.asNormalizedText();

        assertNotNull(rendered.getElementById("restoreConfigSetBtn"));
        assertNotNull(rendered.getElementById("purgeConfigSetBtn"));
        assertNull("a withdrawn Config Set must not offer deletion again",
                rendered.getElementById("deleteConfigSetBtn"));
        // The important one: rendering "does not exist yet - Save creates it" over an archive is an
        // invitation to clobber it, which is exactly what reserving the name prevents.
        assertFalse("the does-not-exist-yet notice must never appear on a withdrawn Config Set",
                text.contains("Save creates it"));
        assertTrue("and its history must still be visible", text.contains("initial"));
    }

    @Test
    public void theJobPageNeverOffersDeletion() throws Exception {
        FreeStyleProject job = jenkins.createFreeStyleProject("untouched-job");

        HtmlPage rendered = jenkins.createWebClient().goTo("job/" + job.getName() + "/configTemplates/");

        assertNull("deletion is a global Config Set action only",
                rendered.getElementById("deleteConfigSetBtn"));
    }

    @Test
    public void theListShowsDeletedSetsSeparatelyFromLiveOnes() throws Exception {
        seed("still-live");
        seed("withdrawn-app");
        page("withdrawn-app").jsDeleteConfigSet(confirm("withdrawn-app").toString());

        HtmlPage list = jenkins.createWebClient().goTo("manage/configTemplates/");

        assertNotNull("the toggle appears only when something is deleted",
                list.getElementById("showDeletedToggle"));
        assertNotNull("the deleted rows ride in an inert template, out of the sortable table",
                list.getElementById("deletedRowsTemplate"));
        assertTrue(list.asXml().contains("withdrawn-app"));
    }

    @Test
    public void theFlashBannerOnlyEchoesAKeyThatIsReallyDeleted() throws Exception {
        seed("withdrawn-app");
        page("withdrawn-app").jsDeleteConfigSet(confirm("withdrawn-app").toString());

        HtmlPage real = jenkins.createWebClient().goTo("manage/configTemplates/?deleted=withdrawn-app");
        assertTrue(real.asNormalizedText().contains("withdrawn-app"));

        // Validated against the repository rather than echoed, so a hand-typed parameter renders
        // nothing at all.
        HtmlPage invented = jenkins.createWebClient().goTo("manage/configTemplates/?deleted=never-existed");
        assertFalse(invented.asNormalizedText().contains("never-existed"));
    }
}
