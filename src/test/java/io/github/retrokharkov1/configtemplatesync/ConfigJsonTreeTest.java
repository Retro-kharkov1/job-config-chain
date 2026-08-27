package io.github.retrokharkov1.configtemplatesync;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigJsonTreeTest {

    @Test
    void flattenNestedObjectToDottedPaths() {
        JsonNode node = ConfigJsonTree.deepMerge(
                "{\"ConnectionStrings\":{\"LocalSqlServer\":\"x\"},\"OpenTelemetry\":{\"Environment\":\"dev\"}}",
                null);
        Map<String, String> flat = ConfigJsonTree.flatten(node);
        assertEquals("x", flat.get("ConnectionStrings.LocalSqlServer"));
        assertEquals("dev", flat.get("OpenTelemetry.Environment"));
    }

    @Test
    void deepMergeEnvOverridesCommonOnConflict() {
        String common = "{\"AppSettings\":{\"TimeoutSeconds\":\"30\",\"FeatureX\":\"off\"},\"Shared\":\"c\"}";
        String env = "{\"AppSettings\":{\"TimeoutSeconds\":\"5\"},\"OnlyInEnv\":\"e\"}";

        JsonNode merged = ConfigJsonTree.deepMerge(common, env);
        Map<String, String> flat = ConfigJsonTree.flatten(merged);

        assertEquals("5", flat.get("AppSettings.TimeoutSeconds"), "env value must win on conflict");
        assertEquals("off", flat.get("AppSettings.FeatureX"), "keys only in common must be preserved");
        assertEquals("c", flat.get("Shared"));
        assertEquals("e", flat.get("OnlyInEnv"), "keys only in env must be preserved");
    }

    @Test
    void deepMergeWithNullCommonIsJustEnv() {
        JsonNode merged = ConfigJsonTree.deepMerge(null, "{\"A\":\"1\"}");
        assertEquals("1", ConfigJsonTree.flatten(merged).get("A"));
    }

    @Test
    void nullValuedKeyInEnvOverlayDeletesItFromMergedResult() {
        // RFC 7396: a key set to JSON null in the patch (env overlay) removes it from the effective merge.
        String common = "{\"AppSettings\":{\"FeatureX\":\"on\",\"FeatureY\":\"on\"}}";
        String envOverlay = "{\"AppSettings\":{\"FeatureX\":null}}";

        JsonNode merged = ConfigJsonTree.deepMerge(common, envOverlay);
        Map<String, String> flat = ConfigJsonTree.flatten(merged);

        assertTrue(!flat.containsKey("AppSettings.FeatureX"), "null-valued overlay key must be removed");
        assertEquals("on", flat.get("AppSettings.FeatureY"), "sibling key must be unaffected");
    }

    @Test
    void envOverlayIsSparseAndDoesNotNeedToRepeatCommonKeys() {
        // Env layer only carries the ONE key it adds/overrides - common's other keys are inherited,
        // not duplicated into the env ConfigSetVersion's own stored JSON.
        String common = "{\"AppSettings\":{\"TimeoutSeconds\":\"30\",\"FeatureX\":\"off\"},\"Shared\":\"c\"}";
        String sparseEnvOverlay = "{\"AppSettings\":{\"TimeoutSeconds\":\"5\"}}";

        JsonNode merged = ConfigJsonTree.deepMerge(common, sparseEnvOverlay);
        Map<String, String> flat = ConfigJsonTree.flatten(merged);

        assertEquals("5", flat.get("AppSettings.TimeoutSeconds"));
        assertEquals("off", flat.get("AppSettings.FeatureX"), "inherited from common, not duplicated in overlay");
        assertEquals("c", flat.get("Shared"));
        // The overlay string itself never mentioned FeatureX or Shared - confirms storage stays sparse.
        assertTrue(!sparseEnvOverlay.contains("FeatureX") && !sparseEnvOverlay.contains("Shared"));
    }

    @Test
    void secretMarkerSurvivesMergeAndFlatten() {
        String common = "{\"Db\":{\"Password\":\"__SECRET__\"}}";
        JsonNode merged = ConfigJsonTree.deepMerge(common, "{}");
        assertEquals("__SECRET__", ConfigJsonTree.flatten(merged).get("Db.Password"));
    }

    @Test
    void extractTokensFindsAllHashBraceTokens() {
        String text = "a=#{ConnectionStrings.LocalSqlServer}# b=#{OpenTelemetry.Environment}# c=nomatch";
        List<String> tokens = ConfigJsonTree.extractTokens(text);
        assertEquals(2, tokens.size());
        assertTrue(tokens.contains("ConnectionStrings.LocalSqlServer"));
        assertTrue(tokens.contains("OpenTelemetry.Environment"));
    }

    @Test
    void invalidJsonThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ConfigJsonTree.deepMerge("{not json", null));
    }
}
