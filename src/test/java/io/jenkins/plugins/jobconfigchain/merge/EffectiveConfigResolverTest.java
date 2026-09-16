package io.jenkins.plugins.jobconfigchain.merge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeFormats;
import io.jenkins.plugins.jobconfigchain.merge.tree.TreeNode;
import io.jenkins.plugins.jobconfigchain.model.ContentType;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class EffectiveConfigResolverTest {

    private static TreeNode json(String content) {
        return TreeFormats.forType(ContentType.JSON).parse(content);
    }

    // Structural (order-independent) equality, matching Gson's own JsonElement#equals semantics —
    // key ORDER is deliberately not part of this plugin's merge contract (only key/value content
    // is), so assertions here must compare parsed structure, not textual/positional serialization.
    private static JsonElement canonical(String json) {
        return JsonParser.parseString(json);
    }

    private static JsonElement render(TreeNode node) {
        return JsonParser.parseString(TreeFormats.forType(ContentType.JSON).serialize(node));
    }

    @Test
    public void mergesEnvPatchOverCommon() {
        String common = "{\"Logging\":{\"Level\":\"Info\"},\"Feature\":{\"Enabled\":false}}";
        String envPatch = "{\"Logging\":{\"Level\":\"Debug\"}}";

        TreeNode effective = EffectiveConfigResolver.resolve(ContentType.JSON, common, envPatch);

        assertEquals(canonical("{\"Logging\":{\"Level\":\"Debug\"},\"Feature\":{\"Enabled\":false}}"),
                render(effective));
    }

    @Test
    public void nullEnvPatchTreatedAsEmptyOverlay() {
        String common = "{\"a\":1}";
        TreeNode effective = EffectiveConfigResolver.resolve(ContentType.JSON, common, null);
        assertEquals(canonical("{\"a\":1}"), render(effective));
    }

    @Test
    public void blankEnvPatchTreatedAsEmptyOverlay() {
        String common = "{\"a\":1}";
        TreeNode effective = EffectiveConfigResolver.resolve(ContentType.JSON, common, "   ");
        assertEquals(canonical("{\"a\":1}"), render(effective));
    }

    @Test
    public void emptyChainFoldsToPatchOverEmptyObject() {
        TreeNode effective = EffectiveConfigResolver.resolveChain(ContentType.JSON, Collections.emptyList(), "{\"a\":1}");
        assertEquals(canonical("{\"a\":1}"), render(effective));
    }

    @Test
    public void singleBaseChainIsByteIdenticalToResolve() {
        String base = "{\"Logging\":{\"Level\":\"Info\"},\"Feature\":{\"Enabled\":false}}";
        String envPatch = "{\"Logging\":{\"Level\":\"Debug\"}}";

        TreeNode viaResolve = EffectiveConfigResolver.resolve(ContentType.JSON, base, envPatch);
        TreeNode viaChain = EffectiveConfigResolver.resolveChain(ContentType.JSON,
                Collections.singletonList(json(base)), envPatch);

        assertEquals("resolve() and resolveChain() for the same single-base inputs must be byte-identical",
                render(viaResolve), render(viaChain));
    }

    @Test
    public void multiBaseChainFoldsLaterBaseWinsThenEnvPatchAppliedLast() {
        String baseA = "{\"Logging\":{\"Level\":\"Info\"},\"FeatureA\":true,\"Shared\":\"fromA\"}";
        String baseB = "{\"FeatureB\":true,\"Shared\":\"fromB\"}";
        String baseC = "{\"FeatureC\":true}";
        // env patch overrides Shared again and nulls out FeatureA, which came from baseA.
        String envPatch = "{\"Shared\":\"fromEnv\",\"FeatureA\":null}";

        List<TreeNode> bases = Arrays.asList(baseA, baseB, baseC).stream()
                .map(EffectiveConfigResolverTest::json).collect(Collectors.toList());
        TreeNode effective = EffectiveConfigResolver.resolveChain(ContentType.JSON, bases, envPatch);

        assertEquals(canonical("{\"Logging\":{\"Level\":\"Info\"},\"FeatureB\":true,\"FeatureC\":true,"
                        + "\"Shared\":\"fromEnv\"}"),
                render(effective));
    }
}
