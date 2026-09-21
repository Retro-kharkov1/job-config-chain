package io.jenkins.plugins.jobconfigchain.ui;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Structural guard over every localization bundle in the UI package.
 *
 * <p>Written after an owner report (2026-09-21) that the plugin showed different languages on
 * different pages. The cause was not a mistranslation but missing files: five of eight bundles had
 * no Ukrainian counterpart at all, and because the SharedBlocks bundles are included by BOTH pages,
 * even a translated page rendered English inside its editor, version-history and secrets sections.
 * Nothing in the build noticed. These tests make that a build failure instead of something only a
 * human browsing the UI in a non-English locale can spot.
 *
 * <p>The bundles are read from the source tree rather than the classpath on purpose: this asserts
 * on the FILE SET, and a missing file is invisible to a classpath lookup - ResourceBundle silently
 * falls back to the base bundle, which is precisely the bug being guarded.
 */
public class BundleParityTest {

    private static final File UI_RESOURCES =
            new File("src/main/resources/io/jenkins/plugins/jobconfigchain/ui");

    /** Locale suffixes every base bundle must ship. */
    private static final List<String> REQUIRED_LOCALES = List.of("uk", "ru");

    private static List<File> baseBundles() throws IOException {
        assertTrue("UI resource directory must exist: " + UI_RESOURCES.getAbsolutePath(),
                UI_RESOURCES.isDirectory());
        try (var paths = Files.walk(UI_RESOURCES.toPath())) {
            List<File> bases = paths
                    .map(java.nio.file.Path::toFile)
                    .filter(f -> f.getName().endsWith(".properties"))
                    .filter(f -> !f.getName().matches(".*_[a-z]{2}(_[A-Z]{2})?\\.properties"))
                    .sorted()
                    .collect(Collectors.toList());
            assertFalse("expected to discover at least one base bundle", bases.isEmpty());
            return bases;
        }
    }

    private static Properties load(File f) throws IOException {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static File sibling(File base, String locale) {
        return new File(base.getParentFile(),
                base.getName().replace(".properties", "_" + locale + ".properties"));
    }

    @Test
    public void everyBaseBundleHasATranslationForEveryRequiredLocale() throws Exception {
        List<String> missing = new ArrayList<>();
        for (File base : baseBundles()) {
            for (String locale : REQUIRED_LOCALES) {
                if (!sibling(base, locale).isFile()) {
                    missing.add(sibling(base, locale).getPath());
                }
            }
        }
        assertTrue("every base bundle must ship a translation for " + REQUIRED_LOCALES
                + ", these are absent: " + String.join(" | ", missing), missing.isEmpty());
    }

    @Test
    public void translationsCoverExactlyTheKeysOfTheirBaseBundle() throws Exception {
        List<String> problems = new ArrayList<>();
        for (File base : baseBundles()) {
            var baseKeys = new TreeSet<>(load(base).stringPropertyNames());
            for (String locale : REQUIRED_LOCALES) {
                File t = sibling(base, locale);
                if (!t.isFile()) {
                    continue; // reported by the test above
                }
                var keys = new TreeSet<>(load(t).stringPropertyNames());
                var untranslated = new TreeSet<>(baseKeys);
                untranslated.removeAll(keys);
                var orphaned = new TreeSet<>(keys);
                orphaned.removeAll(baseKeys);
                if (!untranslated.isEmpty()) {
                    problems.add(t.getName() + " is missing keys: " + untranslated);
                }
                if (!orphaned.isEmpty()) {
                    // An orphan is dead weight at best, and usually a key renamed in the base
                    // bundle while the translation kept the old spelling - which renders as the
                    // raw key to the user.
                    problems.add(t.getName() + " has keys absent from its base bundle: " + orphaned);
                }
            }
        }
        assertTrue(String.join(" | ", problems), problems.isEmpty());
    }

    /**
     * The standing owner rule: a Russian-locale controller must render Ukrainian text. Asserting
     * the two bundles are value-identical is what stops a later edit to the Ukrainian text from
     * silently leaving the Russian locale on the old wording.
     */
    @Test
    public void russianBundlesCarryExactlyTheUkrainianText() throws Exception {
        List<String> drift = new ArrayList<>();
        for (File base : baseBundles()) {
            File uk = sibling(base, "uk");
            File ru = sibling(base, "ru");
            if (!uk.isFile() || !ru.isFile()) {
                continue;
            }
            Properties ukProps = load(uk);
            Properties ruProps = load(ru);
            for (String key : new TreeSet<>(ukProps.stringPropertyNames())) {
                String ukValue = ukProps.getProperty(key);
                String ruValue = ruProps.getProperty(key);
                if (!ukValue.equals(ruValue)) {
                    drift.add(ru.getName() + "[" + key + "] uk=" + ukValue + " ru=" + ruValue);
                }
            }
        }
        assertTrue("ru bundles must carry the Ukrainian text verbatim (owner rule, 2026-09-21): "
                + String.join(" | ", drift), drift.isEmpty());
    }

    @Test
    public void noTranslatedValueIsLeftEmpty() throws Exception {
        List<String> empties = new ArrayList<>();
        for (File base : baseBundles()) {
            for (String locale : REQUIRED_LOCALES) {
                File t = sibling(base, locale);
                if (!t.isFile()) {
                    continue;
                }
                Properties p = load(t);
                for (String key : new TreeSet<>(p.stringPropertyNames())) {
                    if (p.getProperty(key).trim().isEmpty()) {
                        empties.add(t.getName() + "[" + key + "]");
                    }
                }
            }
        }
        assertEquals("a translated key with an empty value renders as blank UI, which is worse "
                + "than falling back to English: " + empties, List.of(), empties);
    }
}
