package io.jenkins.plugins.jobconfigchain.ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Narrow, standalone proof that the maven-localizer-plugin-generated {@link Messages} class (from
 * {@code Messages.properties}/{@code Messages_uk.properties} in this package) actually resolves
 * distinct English vs. Ukrainian text via the JVM default {@link Locale} — the exact mechanism
 * Jenkins' own request-scoped locale switch relies on (Jelly's {@code %key} substitution and
 * {@link Messages}'s generated accessors both read through the same {@code ResourceBundleHolder}
 * that is locale-sensitive on every call, not cached per JVM start).
 *
 * <p>Deliberately narrow (per owner instruction for this pass): this does not run the full
 * {@code mvn clean verify} suite. It only exercises the resource-bundle lookup for a couple of the
 * newly-externalized keys, proving both {@code Messages.properties} (default/English) and
 * {@code Messages_uk.properties} (Ukrainian) are on the classpath, correctly named for
 * {@link java.util.ResourceBundle} locale variant resolution, and produce different rendered text
 * for the same key when the default locale differs.</p>
 */
public class MessagesI18nTest {

    private Locale originalLocale;

    @Before
    public void saveLocale() {
        originalLocale = Locale.getDefault();
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    public void saveBlockedMessage_isEnglishByDefault() {
        Locale.setDefault(Locale.ENGLISH);
        String message = Messages.ConfigSetPage_SaveBlocked("bad input");
        assertEquals("Save blocked: bad input", message);
    }

    @Test
    public void saveBlockedMessage_isUkrainianUnderUkLocale() {
        Locale.setDefault(new Locale("uk"));
        String message = Messages.ConfigSetPage_SaveBlocked("bad input");
        assertEquals("Збереження заблоковано: bad input", message);
    }

    @Test
    public void saveBlockedMessage_englishAndUkrainianAreDistinctText() {
        Locale.setDefault(Locale.ENGLISH);
        String en = Messages.ConfigSetPage_SaveBlocked("x");
        Locale.setDefault(new Locale("uk"));
        String uk = Messages.ConfigSetPage_SaveBlocked("x");
        assertNotEquals("English and Ukrainian resource bundle output must differ", en, uk);
    }

    @Test
    public void mismatchedTypesMessage_resolvesBothLocalesFromTheSameGeneratedAccessor() {
        Locale.setDefault(Locale.ENGLISH);
        String en = Messages.ConfigSetPage_SaveBlockedMismatchedTypes("proj (JSON), proj2 (XML)");
        assertEquals(
                "Save blocked: mismatched content types in base chain — proj (JSON), proj2 (XML) must all share one content type.",
                en);

        Locale.setDefault(new Locale("uk"));
        String uk = Messages.ConfigSetPage_SaveBlockedMismatchedTypes("proj (JSON), proj2 (XML)");
        assertEquals(
                "Збереження заблоковано: невідповідні типи вмісту в ланцюжку базових наборів — proj (JSON), proj2 (XML) мають бути одного типу вмісту.",
                uk);
    }

    /**
     * Independent, generator-agnostic check reading the raw {@code Messages.properties}/
     * {@code Messages_uk.properties} pair directly via {@link ResourceBundle} — a second, more
     * primitive proof of the same mechanism the generated {@link Messages} class relies on, in case
     * the generated class' own caching/holder behavior ever masks a locale-resolution regression.
     */
    @Test
    public void rawResourceBundle_alsoResolvesDistinctLocaleVariants() {
        ResourceBundle en = ResourceBundle.getBundle(
                "io.jenkins.plugins.jobconfigchain.ui.Messages", Locale.ENGLISH);
        ResourceBundle uk = ResourceBundle.getBundle(
                "io.jenkins.plugins.jobconfigchain.ui.Messages", new Locale("uk"));

        assertEquals("Save blocked: {0}", en.getString("ConfigSetPage.SaveBlocked"));
        assertEquals("Збереження заблоковано: {0}", uk.getString("ConfigSetPage.SaveBlocked"));
        assertNotEquals(en.getString("ConfigSetPage.SaveBlocked"), uk.getString("ConfigSetPage.SaveBlocked"));
    }
}
