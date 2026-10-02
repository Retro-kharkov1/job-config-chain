package io.jenkins.plugins.jobconfigchain.ui;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.jobconfigchain.model.ConfigSet;
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guard for how the plugin's three pages load their CSS/JS and which core UI classes
 * they use: assets arrive through {@code st:adjunct} (once per page, shared + page-specific), the
 * adjunct basenames never collide with a view, and the legacy {@code setting-input}/{@code alert
 * alert-*} classes are gone from every plugin resource.
 */
@WithJenkins
public class PageAssetsStructureTest {

    private static final String PKG = "io/jenkins/plugins/jobconfigchain";
    private static final String ADJUNCTS = "adjuncts/";

    private JenkinsRule jenkins;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        jenkins = rule;
    }

    private JenkinsRule.WebClient client() {
        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        return wc;
    }

    private static int count(String html, String needle) {
        int n = 0;
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(html);
        while (m.find()) {
            n++;
        }
        return n;
    }

    private void assertLoadsOnce(String html, String page, String pageStyle, String pageScript) {
        String sharedStyle = PKG + "/adjuncts/ctsyncSharedStyle.css";
        String sharedScript = PKG + "/adjuncts/ctsyncSharedScript.js";
        assertEquals(1, count(html, sharedStyle), page + ": shared stylesheet must be linked exactly once");
        assertEquals(1, count(html, sharedScript), page + ": shared script must be loaded exactly once");
        assertEquals(1, count(html, PKG + "/adjuncts/" + pageStyle + ".css"), page + ": page stylesheet exactly once");
        assertEquals(1, count(html, PKG + "/adjuncts/" + pageScript + ".js"), page + ": page script exactly once");
        // shared first, then page-specific, so the page script can rely on the shared helpers
        assertTrue(html.indexOf(sharedScript) < html.indexOf(PKG + "/adjuncts/" + pageScript + ".js"),
                page + ": shared script must precede the page script");
        assertTrue(html.indexOf(sharedStyle) < html.indexOf(PKG + "/adjuncts/" + pageStyle + ".css"),
                page + ": shared stylesheet must precede the page stylesheet");
        assertFalse(html.contains("getViewResource"), page + ": no view-resource script/link");
        assertFalse(html.contains("setting-input"), page + ": legacy setting-input class must be gone");
        assertFalse(Pattern.compile("class=\"[^\"]*\\balert alert-").matcher(html).find(),
                page + ": legacy alert alert-* classes must be gone");
    }

    @Test
    public void globalListPageLoadsItsAdjunctsOnce() throws Exception {
        String html = client().goTo("manage/configChains/").getWebResponse().getContentAsString();
        assertLoadsOnce(html, "global list", "ctsyncRootStyle", "ctsyncRootScript");
    }

    @Test
    public void commonEditorPageLoadsItsAdjunctsOnce() throws Exception {
        ConfigSet common = new ConfigSet("assets1", ConfigSetRole.COMMON, null, "Common", ContentType.JSON);
        common.activate(common.addVersion("{\"a\":1}", "seed", "seed-author", 1L));
        new ConfigSetRepository().save(common);
        String html = client().goTo("manage/configChains/assets1/").getWebResponse().getContentAsString();
        assertLoadsOnce(html, "common editor", "ctsyncCommonStyle", "ctsyncCommonScript");
    }

    @Test
    public void jobPageLoadsItsAdjunctsOnce() throws Exception {
        FreeStyleProject job = jenkins.createFreeStyleProject("assets-job");
        String html = client().goTo("job/" + job.getName() + "/configChains/").getWebResponse().getContentAsString();
        assertLoadsOnce(html, "job page", "ctsyncJobStyle", "ctsyncJobScript");
    }

    @Test
    public void adjunctBasenamesNeverShadowAView() throws Exception {
        // An adjunct name also loads <name>.jelly, so none of ours may be called index.* and none
        // may sit next to a same-named jelly.
        Path dir = Path.of("src/main/resources", PKG, "adjuncts");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                assertFalse(name.startsWith("index."), "adjunct must not be named index.*: " + name);
                assertFalse(name.endsWith(".jelly"), "adjunct directory must hold only css/js: " + name);
            }
        }
    }

    @Test
    public void noPluginResourceUsesLegacySettingInput() throws Exception {
        List<String> offenders = new java.util.ArrayList<>();
        for (String root : List.of("src/main/resources/io", "src/main/resources/lib")) {
            try (Stream<Path> paths = Files.walk(Path.of(root))) {
                for (Path p : paths.filter(Files::isRegularFile).toList()) {
                    String n = p.getFileName().toString();
                    if (!(n.endsWith(".jelly") || n.endsWith(".css") || n.endsWith(".js") || n.endsWith(".html"))) {
                        continue;
                    }
                    if (read(p).contains("setting-input")) {
                        offenders.add(p.toString());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "setting-input was replaced by jenkins-input in core 2.320: " + offenders);
    }

    @Test
    public void sharedJellyIsATaglibNotAHelperClass() throws Exception {
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/lib/jobconfigchain/taglib")),
                "the shared blocks must live in a taglib (lib/jobconfigchain with a taglib marker)");
        assertFalse(Files.exists(Path.of("src/main/java", PKG, "ui/SharedBlocks.java")),
                "the st:include helper class must be gone");
        try (Stream<Path> paths = Files.walk(Path.of("src/main/resources/io"))) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".jelly")).toList()) {
                assertFalse(read(p).contains("<st:include"), "page views must use the taglib: " + p);
            }
        }
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
