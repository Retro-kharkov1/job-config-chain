package io.jenkins.plugins.jobconfigchain.merge.tree;

import io.jenkins.plugins.jobconfigchain.model.ContentType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * RFC 7396 semantics over the JSON adapter — a behavior-neutral refactor check against the exact
 * same fixtures the pre-multi-format {@code JsonMergePatchTest} used, verifying {@link
 * TreeMergePatch} + {@link GsonTreeAdapter} reproduce identical JSON behavior before XML/YAML are
 * layered on top (tech-lead-mandated sequencing: adapters + rewritten algorithm first, verified
 * against JSON only, THEN the other formats — see {@link XmlTreeAdapterTest}/{@link
 * YamlTreeAdapterTest} for format-specific merge coverage).
 */
public class TreeMergePatchTest {

    private static final TreeFormat JSON = TreeFormats.forType(ContentType.JSON);

    private String apply(String targetJson, String patchJson) {
        TreeNode result = TreeMergePatch.apply(JSON.parse(targetJson), JSON.parse(patchJson));
        return canonical(JSON.serialize(result));
    }

    private String canonical(String json) {
        return JSON.serialize(JSON.parse(json));
    }

    @Test
    public void nullValueRemovesKey() {
        assertEquals(canonical("{\"a\":1}"), apply("{\"a\":1,\"b\":2}", "{\"b\":null}"));
    }

    @Test
    public void absentPathInheritsUnchanged() {
        assertEquals(canonical("{\"a\":1,\"b\":{\"c\":99,\"d\":3}}"),
                apply("{\"a\":1,\"b\":{\"c\":2,\"d\":3}}", "{\"b\":{\"c\":99}}"));
    }

    @Test
    public void nonObjectNonNullReplacesOutright() {
        assertEquals(canonical("{\"a\":\"replaced\"}"), apply("{\"a\":{\"x\":1}}", "{\"a\":\"replaced\"}"));
    }

    @Test
    public void arrayReplacesWhollyNeverMergedElementByElement() {
        // OQ-8: RFC 7396 known limitation — arrays are always replaced wholesale.
        assertEquals(canonical("{\"list\":[9]}"), apply("{\"list\":[1,2,3]}", "{\"list\":[9]}"));
    }

    @Test
    public void nestedObjectRecursesKeyByKey() {
        assertEquals(canonical("{\"a\":{\"b\":{\"d\":2,\"e\":5}}}"),
                apply("{\"a\":{\"b\":{\"c\":1,\"d\":2}}}", "{\"a\":{\"b\":{\"c\":null,\"e\":5}}}"));
    }

    @Test
    public void addsNewKeyAbsentFromTarget() {
        assertEquals(canonical("{\"a\":1,\"b\":2}"), apply("{\"a\":1}", "{\"b\":2}"));
    }

    @Test
    public void emptyPatchLeavesTargetUnchanged() {
        assertEquals(canonical("{\"a\":1,\"b\":{\"c\":2}}"), apply("{\"a\":1,\"b\":{\"c\":2}}", "{}"));
    }
}
