package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EffectiveConfigResolverTest {

    @Test
    public void mergesEnvPatchOverCommon() {
        String common = "{\"Logging\":{\"Level\":\"Info\"},\"Feature\":{\"Enabled\":false}}";
        String envPatch = "{\"Logging\":{\"Level\":\"Debug\"}}";

        JsonObject effective = EffectiveConfigResolver.resolve(common, envPatch);

        assertEquals(JsonParser.parseString(
                        "{\"Logging\":{\"Level\":\"Debug\"},\"Feature\":{\"Enabled\":false}}"),
                effective);
    }

    @Test
    public void nullEnvPatchTreatedAsEmptyOverlay() {
        String common = "{\"a\":1}";
        JsonObject effective = EffectiveConfigResolver.resolve(common, null);
        assertEquals(JsonParser.parseString("{\"a\":1}"), effective);
    }

    @Test
    public void blankEnvPatchTreatedAsEmptyOverlay() {
        String common = "{\"a\":1}";
        JsonObject effective = EffectiveConfigResolver.resolve(common, "   ");
        assertEquals(JsonParser.parseString("{\"a\":1}"), effective);
    }
}
