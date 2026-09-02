package io.github.retrokharkov1.configtemplatesync.ui;

import hudson.model.Failure;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertThrows;

/**
 * Parameterized coverage of {@link ConfigSetPage#validateSyntaxOrFail(String, ContentType)} — the
 * single consolidated syntax-validation call site (FR-65) — across valid/invalid/wrong-root-shape
 * content for all 3 formats.
 */
@RunWith(Parameterized.class)
public class ConfigSetPageValidateSyntaxTest {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(
                new Object[]{ContentType.JSON, "{\"a\":1}", true},
                new Object[]{ContentType.JSON, "{\"a\":", false},
                new Object[]{ContentType.JSON, "[1,2,3]", false}, // wrong root shape: array, not object
                new Object[]{ContentType.XML, "<root><a>1</a></root>", true},
                new Object[]{ContentType.XML, "<root><a></root>", false},
                new Object[]{ContentType.XML, "<root>just text, no child elements</root>", false}, // wrong root shape
                new Object[]{ContentType.YAML, "a: 1\n", true},
                new Object[]{ContentType.YAML, "a: [unbalanced\n", false},
                new Object[]{ContentType.YAML, "just a scalar\n", false} // wrong root shape: scalar, not mapping
        );
    }

    private final ContentType type;
    private final String content;
    private final boolean expectedValid;

    public ConfigSetPageValidateSyntaxTest(ContentType type, String content, boolean expectedValid) {
        this.type = type;
        this.content = content;
        this.expectedValid = expectedValid;
    }

    @Test
    public void validatesOrFails() {
        if (expectedValid) {
            ConfigSetPage.validateSyntaxOrFail(content, type); // must not throw
        } else {
            assertThrows(Failure.class, () -> ConfigSetPage.validateSyntaxOrFail(content, type));
        }
    }
}
