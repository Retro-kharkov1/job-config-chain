package io.jenkins.plugins.jobconfigchain.ui;

import hudson.model.Failure;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parameterized coverage of {@link ConfigSetPage#validateSyntaxOrFail(String, ContentType)} — the
 * single consolidated syntax-validation call site (FR-65) — across valid/invalid/wrong-root-shape
 * content for all 3 formats.
 */
public class ConfigSetPageValidateSyntaxTest {

    static Collection<Object[]> cases() {
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    public void validatesOrFails(ContentType type, String content, boolean expectedValid) {
        if (expectedValid) {
            ConfigSetPage.validateSyntaxOrFail(content, type); // must not throw
        } else {
            assertThrows(Failure.class, () -> ConfigSetPage.validateSyntaxOrFail(content, type));
        }
    }
}
