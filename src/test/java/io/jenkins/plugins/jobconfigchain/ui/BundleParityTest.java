package io.jenkins.plugins.jobconfigchain.ui;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guard over every localization bundle and help file the UI ships.
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
 *
 * <p><b>Adding a language</b> is only a matter of adding its files and listing it in
 * {@link #SUPPORTED_LOCALES}: every check below is driven by that one list.
 */
public class BundleParityTest {

    /**
     * THE list of supported locales (besides the English default). Every base bundle and every help
     * file must have a sibling for each of them.
     */
    static final List<String> SUPPORTED_LOCALES = List.of(
            "uk", "ru",
            "de", "fr", "it", "es", "es_AR", "pt_BR", "pt_PT", "ca", "nl", "sv_SE", "da", "nb_NO",
            "fi", "et", "lv", "lt", "pl", "cs", "sk", "sl", "hu", "ro", "bg", "sr", "el", "tr", "he",
            "ja", "ko", "zh_CN", "zh_TW", "en_GB");

    /**
     * The writing system(s) each non-Latin locale is written in. A locale absent from this map is
     * written in Latin script. Used to fail a file that is in the wrong language altogether.
     */
    static final Map<String, Set<Character.UnicodeScript>> NON_LATIN_SCRIPTS = Map.of(
            "uk", Set.of(Character.UnicodeScript.CYRILLIC),
            "ru", Set.of(Character.UnicodeScript.CYRILLIC),
            "bg", Set.of(Character.UnicodeScript.CYRILLIC),
            "sr", Set.of(Character.UnicodeScript.CYRILLIC),
            "el", Set.of(Character.UnicodeScript.GREEK),
            "he", Set.of(Character.UnicodeScript.HEBREW),
            "ja", Set.of(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN),
            "ko", Set.of(Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN),
            "zh_CN", Set.of(Character.UnicodeScript.HAN),
            "zh_TW", Set.of(Character.UnicodeScript.HAN));

    /** Locales whose text is allowed to equal the English text (British English). */
    static final Set<String> MAY_EQUAL_ENGLISH = Set.of("en_GB");

    /**
     * Minimum share of English-sentence values a Latin-script, non-English locale must actually
     * change. The shipped bundles sit at 93% or more; short shared words ("Base", "Version") keep
     * the rest from reaching 100%.
     */
    private static final double MIN_TRANSLATED_SHARE = 0.80;

    /**
     * Locales that deliberately show another locale's text. Owner rule (2026-09-21, reconfirmed
     * 2026-10-04): a Russian-locale controller renders UKRAINIAN, there is no Russian translation.
     * An aliased locale's files must be identical to their source's, which is also what stops a
     * later edit of the Ukrainian text from silently leaving the Russian locale on the old wording.
     * (Java's ResourceBundle lookup and Stapler's help-file lookup have no per-locale fallback
     * hook, so the files are duplicated rather than redirected.)
     */
    static final Map<String, String> ALIASES = Map.of("ru", "uk");

    private static final File UI_RESOURCES =
            new File("src/main/resources/io/jenkins/plugins/jobconfigchain/ui");

    private static final File TAGLIB_RESOURCES =
            new File("src/main/resources/lib/jobconfigchain");

    /** Pipeline step forms: one config.properties per step directory. */
    private static final File STEP_RESOURCES =
            new File("src/main/resources/io/jenkins/plugins/jobconfigchain/steps");

    /** Domain-level validation messages shown on the admin pages. */
    private static final File MODEL_RESOURCES =
            new File("src/main/resources/io/jenkins/plugins/jobconfigchain/model");

    /** Localized in-UI help files for the admin pages. */
    private static final File PAGE_HELP =
            new File("src/main/resources/io/jenkins/plugins/jobconfigchain/ui/PageHelp");

    private static final File ADJUNCTS =
            new File("src/main/resources/io/jenkins/plugins/jobconfigchain/adjuncts");

    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");
    private static final Pattern RUSSIAN_ONLY = Pattern.compile("[ыэъёЫЭЪЁ]");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{3,}");

    /** Values that are the same technical token in every language. */
    private static final Set<String> TECHNICAL_VALUES = Set.of("XML", "JSON", "YAML");

    private static List<File> baseBundles() throws IOException {
        List<File> bases = new ArrayList<>();
        for (File root : List.of(UI_RESOURCES, TAGLIB_RESOURCES, STEP_RESOURCES, MODEL_RESOURCES)) {
            assertTrue(root.isDirectory(), "resource directory must exist: " + root.getAbsolutePath());
            try (var paths = Files.walk(root.toPath())) {
                bases.addAll(paths
                        .map(java.nio.file.Path::toFile)
                        .filter(f -> f.getName().endsWith(".properties"))
                        .filter(f -> !f.getName().matches(".*_[a-z]{2}(_[A-Z]{2})?\\.properties"))
                        .sorted()
                        .collect(Collectors.toList()));
            }
        }
        assertFalse(bases.isEmpty(), "expected to discover at least one base bundle");
        return bases;
    }

    /** Every help file of the plugin: the page help and each step's field help. */
    private static List<File> baseHelpFiles() {
        List<File> files = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        dirs.add(PAGE_HELP);
        File[] steps = STEP_RESOURCES.listFiles(File::isDirectory);
        assertTrue(steps != null && steps.length > 0, "step resource directories must exist");
        dirs.addAll(List.of(steps));
        for (File dir : dirs) {
            File[] found = dir.listFiles((d, n) -> n.matches("help(-[A-Za-z]+)?\\.html"));
            assertTrue(found != null, dir + " must exist");
            files.addAll(List.of(found));
        }
        return files;
    }

    private static Properties load(File f) throws IOException {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static File sibling(File base, String locale) {
        String name = base.getName();
        int dot = name.lastIndexOf('.');
        return new File(base.getParentFile(), name.substring(0, dot) + "_" + locale + name.substring(dot));
    }

    private static String text(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ---- Bundles ---------------------------------------------------------------------------------

    @Test
    public void everyBaseBundleHasATranslationForEveryRequiredLocale() throws Exception {
        List<String> missing = new ArrayList<>();
        for (File base : baseBundles()) {
            for (String locale : SUPPORTED_LOCALES) {
                if (!sibling(base, locale).isFile()) {
                    missing.add(sibling(base, locale).getPath());
                }
            }
        }
        assertTrue(missing.isEmpty(), "every base bundle must ship a translation for " + SUPPORTED_LOCALES
                + ", these are absent: " + String.join(" | ", missing));
    }

    @Test
    public void translationsCoverExactlyTheKeysOfTheirBaseBundle() throws Exception {
        List<String> problems = new ArrayList<>();
        for (File base : baseBundles()) {
            var baseKeys = new TreeSet<>(load(base).stringPropertyNames());
            for (String locale : SUPPORTED_LOCALES) {
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
        assertTrue(problems.isEmpty(), String.join(" | ", problems));
    }

    /** An aliased locale (ru) carries exactly its source locale's (uk) text, key by key. */
    @Test
    public void aliasedLocalesCarryExactlyTheirSourceText() throws Exception {
        List<String> drift = new ArrayList<>();
        for (File base : baseBundles()) {
            for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
                File source = sibling(base, alias.getValue());
                File copy = sibling(base, alias.getKey());
                if (!source.isFile() || !copy.isFile()) {
                    continue;
                }
                Properties sourceProps = load(source);
                Properties copyProps = load(copy);
                for (String key : new TreeSet<>(sourceProps.stringPropertyNames())) {
                    String expected = sourceProps.getProperty(key);
                    String actual = copyProps.getProperty(key);
                    if (!expected.equals(actual)) {
                        drift.add(copy.getName() + "[" + key + "] " + alias.getValue() + "=" + expected
                                + " " + alias.getKey() + "=" + actual);
                    }
                }
            }
        }
        assertTrue(drift.isEmpty(), "aliased locale bundles must carry the source text verbatim (owner rule): "
                + String.join(" | ", drift));
    }

    /**
     * The Ukrainian files are Ukrainian: any value that is a sentence in the English bundle comes out
     * in Cyrillic, with none of the letters only Russian uses. Script, not translation quality.
     */
    @Test
    public void ukrainianBundlesAreInUkrainian() throws Exception {
        List<String> problems = new ArrayList<>();
        for (File base : baseBundles()) {
            File uk = sibling(base, "uk");
            if (!uk.isFile()) {
                continue;
            }
            Properties en = load(base);
            Properties p = load(uk);
            for (String key : new TreeSet<>(p.stringPropertyNames())) {
                String value = p.getProperty(key);
                if (RUSSIAN_ONLY.matcher(value).find()) {
                    problems.add(uk.getName() + "[" + key + "] uses letters Ukrainian does not have: " + value);
                }
                if (LATIN_WORD.matcher(en.getProperty(key, "")).find()
                        && !CYRILLIC.matcher(value).find() && !TECHNICAL_VALUES.contains(value)) {
                    problems.add(uk.getName() + "[" + key + "] is not translated: " + value);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Letters of {@code s} that belong to a script this locale is not written in. */
    private static String foreignLetters(String locale, String s) {
        Set<Character.UnicodeScript> allowed = NON_LATIN_SCRIPTS.get(locale);
        StringBuilder foreign = new StringBuilder();
        s.codePoints().filter(Character::isLetter).forEach(cp -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            // Technical words (Jenkins, XML, ...) stay Latin in every locale; COMMON/INHERITED cover
            // script-neutral letters such as the Japanese prolonged sound mark.
            boolean ok = script == Character.UnicodeScript.LATIN
                    || script == Character.UnicodeScript.COMMON
                    || script == Character.UnicodeScript.INHERITED
                    || allowed != null && allowed.contains(script);
            if (!ok) {
                foreign.appendCodePoint(cp);
            }
        });
        return foreign.toString();
    }

    private static boolean hasLocaleScript(String locale, String s) {
        Set<Character.UnicodeScript> allowed = NON_LATIN_SCRIPTS.get(locale);
        return s.codePoints().anyMatch(cp -> allowed.contains(Character.UnicodeScript.of(cp)));
    }

    /**
     * Every locale's bundle values are in the locale's own writing system: no letters of a foreign
     * script anywhere (a Hebrew value in the Greek file), and for the non-Latin locales every value
     * that is a sentence in English carries the locale's script (Greek, Hebrew, Cyrillic, CJK, Hangul).
     * For the Latin-script locales, which cannot be told apart by script, the check is that the file
     * is actually translated: English text copied across fails. British English is exempt.
     */
    @Test
    public void everyLocaleIsWrittenInItsOwnLanguageScript() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String locale : SUPPORTED_LOCALES) {
            int sentences = 0;
            int changed = 0;
            for (File base : baseBundles()) {
                File t = sibling(base, locale);
                if (!t.isFile()) {
                    continue;
                }
                Properties en = load(base);
                Properties p = load(t);
                for (String key : new TreeSet<>(p.stringPropertyNames())) {
                    String value = p.getProperty(key);
                    String foreign = foreignLetters(locale, value);
                    if (!foreign.isEmpty()) {
                        problems.add(t.getName() + "[" + key + "] has " + foreign + " from a foreign script: " + value);
                    }
                    boolean englishSentence = LATIN_WORD.matcher(en.getProperty(key, "")).find();
                    if (NON_LATIN_SCRIPTS.containsKey(locale)) {
                        if (englishSentence && !hasLocaleScript(locale, value) && !TECHNICAL_VALUES.contains(value)) {
                            problems.add(t.getName() + "[" + key + "] is not in the " + locale + " script: " + value);
                        }
                    } else if (englishSentence) {
                        sentences++;
                        if (!value.equals(en.getProperty(key))) {
                            changed++;
                        }
                    }
                }
            }
            if (!NON_LATIN_SCRIPTS.containsKey(locale) && !MAY_EQUAL_ENGLISH.contains(locale)) {
                assertTrue(sentences > 0, "no sentence values found for " + locale);
                double share = (double) changed / sentences;
                if (share < MIN_TRANSLATED_SHARE) {
                    problems.add(locale + " bundles are mostly English: only " + changed + " of " + sentences
                            + " values differ from the English text");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Every {n} placeholder of the English value survives, once each kind, in every translation. */
    @Test
    public void translationsKeepTheirMessageFormatPlaceholders() throws Exception {
        Pattern placeholder = Pattern.compile("\\{\\d+\\}");
        List<String> problems = new ArrayList<>();
        for (File base : baseBundles()) {
            Properties en = load(base);
            for (String locale : SUPPORTED_LOCALES) {
                File t = sibling(base, locale);
                if (!t.isFile()) {
                    continue;
                }
                Properties p = load(t);
                for (String key : new TreeSet<>(p.stringPropertyNames())) {
                    if (!placeholders(placeholder, en.getProperty(key, "")).equals(placeholders(placeholder, p.getProperty(key)))) {
                        problems.add(t.getName() + "[" + key + "] placeholders differ from English: " + p.getProperty(key));
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static List<String> placeholders(Pattern placeholder, String value) {
        List<String> found = new ArrayList<>();
        Matcher m = placeholder.matcher(value);
        while (m.find()) {
            found.add(m.group());
        }
        java.util.Collections.sort(found);
        return found;
    }

    /**
     * A count is never glued to an inflected noun: languages have one to six plural forms, so the
     * message is a label plus the number in a {0} slot (e.g. "Bases: {0}"). The old singular/plural
     * key pair, and a hard-coded "0 bases" in the empty message, must not come back.
     */
    @Test
    public void baseChainCountIsAPluralNeutralLabelInEveryLocale() throws Exception {
        File base = new File(TAGLIB_RESOURCES, "versionHistoryBlock.properties");
        List<String> problems = new ArrayList<>();
        List<File> files = new ArrayList<>();
        files.add(base);
        for (String locale : SUPPORTED_LOCALES) {
            files.add(sibling(base, locale));
        }
        for (File f : files) {
            Properties p = load(f);
            if (p.containsKey("baseChain.countSingular") || p.containsKey("baseChain.countPlural")) {
                problems.add(f.getName() + " still has the singular/plural count keys");
            }
            String count = p.getProperty("baseChain.count", "");
            if (!count.contains("{0}") || count.trim().equals("{0}")) {
                problems.add(f.getName() + "[baseChain.count] must be a label with a {0} slot: " + count);
            }
            if (p.getProperty("baseChain.noneDeclared", "").matches(".*\\d.*")) {
                problems.add(f.getName() + "[baseChain.noneDeclared] must not embed a number: "
                        + p.getProperty("baseChain.noneDeclared"));
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * Every bundle value is run through MessageFormat (Stapler's {@code ${%key}} and the generated
     * {@code Messages} accessors both format, with or without arguments), where a single apostrophe
     * is a quote character and silently disappears (and can swallow a {@code {0}} after it). A literal
     * apostrophe is therefore always written twice.
     */
    @Test
    public void everyApostropheInABundleValueIsDoubled() throws Exception {
        Pattern lone = Pattern.compile("(?<!')'(?!')");
        List<String> problems = new ArrayList<>();
        for (File base : baseBundles()) {
            List<File> files = new ArrayList<>();
            files.add(base);
            for (String locale : SUPPORTED_LOCALES) {
                files.add(sibling(base, locale));
            }
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                Properties p = load(f);
                for (String key : new TreeSet<>(p.stringPropertyNames())) {
                    if (lone.matcher(p.getProperty(key)).find()) {
                        problems.add(f.getName() + "[" + key + "]");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "write ' as '' in bundle values: " + problems);
    }

    @Test
    public void noTranslatedValueIsLeftEmpty() throws Exception {
        List<String> empties = new ArrayList<>();
        for (File base : baseBundles()) {
            for (String locale : SUPPORTED_LOCALES) {
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
        assertEquals(List.of(), empties, "a translated key with an empty value renders as blank UI, which is worse "
                + "than falling back to English: " + empties);
    }

    // ---- Help files ------------------------------------------------------------------------------

    /**
     * Every help file has a variant for each supported locale next to it, wrapped in a div as core's
     * help loader expects; the aliased locale's file is a copy of its source's; the Ukrainian one is
     * in Ukrainian.
     */
    @Test
    public void everyHelpFileHasAVariantPerSupportedLocale() throws Exception {
        List<String> problems = new ArrayList<>();
        List<File> bases = baseHelpFiles();
        for (File base : bases) {
            for (String locale : SUPPORTED_LOCALES) {
                File variant = sibling(base, locale);
                if (!variant.isFile()) {
                    problems.add("missing " + variant.getPath());
                    continue;
                }
                String body = text(variant).trim();
                if (!body.startsWith("<div>") || !body.endsWith("</div>")) {
                    problems.add(variant.getName() + " must be wrapped in a div");
                }
                String stripped = body.replaceAll("<[^>]*>", " ").replaceAll("&[#a-zA-Z0-9]+;", " ");
                if ("uk".equals(locale)) {
                    if (!CYRILLIC.matcher(stripped).find() || RUSSIAN_ONLY.matcher(stripped).find()) {
                        problems.add(variant.getPath() + " is not Ukrainian");
                    }
                }
                String foreign = foreignLetters(locale, stripped);
                if (!foreign.isEmpty()) {
                    problems.add(variant.getPath() + " has letters from a foreign script: " + foreign);
                }
                if (NON_LATIN_SCRIPTS.containsKey(locale) && !hasLocaleScript(locale, stripped)) {
                    problems.add(variant.getPath() + " is not written in the " + locale + " script");
                }
            }
            for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
                File source = sibling(base, alias.getValue());
                File copy = sibling(base, alias.getKey());
                if (source.isFile() && copy.isFile() && !text(source).equals(text(copy))) {
                    problems.add(copy.getPath() + " must be identical to " + source.getName());
                }
            }
            if (text(base).contains("<script") || text(base).contains("style=")) {
                problems.add(base.getName() + " must stay CSP-clean");
            }
        }
        assertTrue(bases.size() >= 20, "expected the page help and the step help files: " + bases.size());
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    // ---- No English left outside the bundles ------------------------------------------------------

    /** Technical tokens that are legitimately the same in every language. */
    private static final List<String> JELLY_ALLOWED = List.of("JSON", "XML", "YAML", "database.password", "my-app");

    /**
     * Literal user-visible text in a Jelly view (element text and the title/placeholder/aria-label/alt
     * attributes) must go through {@code ${%key}}; anything left would show in English under every
     * language. Format names and example values are allowed.
     */
    @Test
    public void noJellyViewHoldsLiteralEnglishText() throws Exception {
        List<String> problems = new ArrayList<>();
        List<java.nio.file.Path> views = new ArrayList<>();
        for (File root : List.of(UI_RESOURCES, TAGLIB_RESOURCES, STEP_RESOURCES)) {
            try (var paths = Files.walk(root.toPath())) {
                views.addAll(paths.filter(p -> p.toString().endsWith(".jelly")).collect(Collectors.toList()));
            }
        }
        assertTrue(views.size() >= 8, "expected to find the views: " + views.size());
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        for (java.nio.file.Path view : views) {
            org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(view.toFile());
            collectEnglish(doc.getDocumentElement(), view.toString(), problems);
        }
        assertTrue(problems.isEmpty(), "literal English in views (move it to the bundle with a ${%key}): "
                + String.join(" | ", problems));
    }

    private static String visibleLetters(String text) {
        String t = text.replaceAll("\\$\\{[^}]*\\}", " ");
        for (String allowed : JELLY_ALLOWED) {
            t = t.replace(allowed, " ");
        }
        return t;
    }

    private static void collectEnglish(org.w3c.dom.Node node, String file, List<String> problems) {
        Pattern letters = Pattern.compile("[A-Za-z]{2,}");
        if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                || node.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
            if (letters.matcher(visibleLetters(node.getNodeValue())).find()) {
                problems.add(file + ": text '" + node.getNodeValue().trim() + "'");
            }
            return;
        }
        if (node.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
            return;
        }
        org.w3c.dom.Element el = (org.w3c.dom.Element) node;
        for (String attr : List.of("title", "placeholder", "aria-label", "alt")) {
            if (el.hasAttribute(attr) && letters.matcher(visibleLetters(el.getAttribute(attr))).find()) {
                problems.add(file + ": @" + attr + "='" + el.getAttribute(attr) + "' on <" + el.getTagName() + ">");
            }
        }
        for (org.w3c.dom.Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
            collectEnglish(c, file, problems);
        }
    }

    /**
     * The page scripts build rows, banners and notifications at runtime. Their text arrives from the
     * bundles through data-* attributes; a capitalised multi-word string literal left in a script
     * would be English under every language.
     */
    @Test
    public void noAdjunctScriptHoldsAnEnglishSentence() throws Exception {
        Pattern literal = Pattern.compile("'((?:[^'\\\\\\n]|\\\\.)*)'|\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
        Pattern sentence = Pattern.compile("^[A-Z][a-z]+( [A-Za-z]+)+");
        List<String> problems = new ArrayList<>();
        File[] scripts = ADJUNCTS.listFiles((d, n) -> n.endsWith(".js"));
        assertTrue(scripts != null && scripts.length >= 4);
        for (File script : scripts) {
            int n = 0;
            for (String line : Files.readAllLines(script.toPath(), StandardCharsets.UTF_8)) {
                n++;
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                Matcher m = literal.matcher(line);
                while (m.find()) {
                    String text = m.group(1) != null ? m.group(1) : m.group(2);
                    if (sentence.matcher(text).find()) {
                        problems.add(script.getName() + ":" + n + " '" + text + "'");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "English sentences in page scripts (carry them via data-* from the bundle): "
                + String.join(" | ", problems));
    }

    /**
     * Messages returned to the browser by the admin pages come from {@code Messages} so they follow
     * the request language. The only literal left is the protocol error for a hand-built request that
     * no page of this plugin can produce.
     */
    @Test
    public void adminPagesReturnLocalizedErrors() throws Exception {
        File dir = new File("src/main/java/io/jenkins/plugins/jobconfigchain/ui");
        Pattern literalError =
                Pattern.compile("put\\(\"error\", \"([^\"]*)\"|simpleError\\([^,]+, \"([^\"]*)\"|lifecycleError\\([^,]+, \"([^\"]*)\"");
        List<String> problems = new ArrayList<>();
        File[] sources = dir.listFiles((d, n) -> n.endsWith(".java"));
        assertTrue(sources != null && sources.length > 0);
        for (File f : sources) {
            Matcher m = literalError.matcher(text(f));
            while (m.find()) {
                String literal = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
                if (!literal.startsWith("Malformed request")) {
                    problems.add(f.getName() + ": \"" + literal + "\"");
                }
            }
        }
        assertTrue(problems.isEmpty(), "move these to Messages.properties: " + String.join(" | ", problems));
    }
}
