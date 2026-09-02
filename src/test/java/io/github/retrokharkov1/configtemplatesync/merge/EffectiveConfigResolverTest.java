package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

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

    @Test
    public void emptyChainFoldsToPatchOverEmptyObject() {
        JsonObject effective = EffectiveConfigResolver.resolveChain(Collections.emptyList(), "{\"a\":1}");
        assertEquals(JsonParser.parseString("{\"a\":1}"), effective);
    }

    @Test
    public void singleBaseChainIsByteIdenticalToResolve() {
        String base = "{\"Logging\":{\"Level\":\"Info\"},\"Feature\":{\"Enabled\":false}}";
        String envPatch = "{\"Logging\":{\"Level\":\"Debug\"}}";

        JsonObject viaResolve = EffectiveConfigResolver.resolve(base, envPatch);
        JsonObject viaChain = EffectiveConfigResolver.resolveChain(Collections.singletonList(base), envPatch);

        assertEquals("resolve() and resolveChain() for the same single-base inputs must be byte-identical",
                viaResolve.toString(), viaChain.toString());
    }

    @Test
    public void multiBaseChainFoldsLaterBaseWinsThenEnvPatchAppliedLast() {
        String baseA = "{\"Logging\":{\"Level\":\"Info\"},\"FeatureA\":true,\"Shared\":\"fromA\"}";
        String baseB = "{\"FeatureB\":true,\"Shared\":\"fromB\"}";
        String baseC = "{\"FeatureC\":true}";
        // env patch overrides Shared again and nulls out FeatureA, which came from baseA.
        String envPatch = "{\"Shared\":\"fromEnv\",\"FeatureA\":null}";

        JsonObject effective = EffectiveConfigResolver.resolveChain(Arrays.asList(baseA, baseB, baseC), envPatch);

        assertEquals(JsonParser.parseString(
                        "{\"Logging\":{\"Level\":\"Info\"},\"FeatureB\":true,\"FeatureC\":true,"
                                + "\"Shared\":\"fromEnv\"}"),
                effective);
    }
}
