package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JsonMergePatchTest {

    private JsonElement apply(String targetJson, String patchJson) {
        return JsonMergePatch.apply(JsonParser.parseString(targetJson), JsonParser.parseString(patchJson));
    }

    @Test
    public void nullValueRemovesKey() {
        JsonElement result = apply(
                "{\"a\":1,\"b\":2}",
                "{\"b\":null}");
        assertEquals(JsonParser.parseString("{\"a\":1}"), result);
    }

    @Test
    public void absentPathInheritsUnchanged() {
        JsonElement result = apply(
                "{\"a\":1,\"b\":{\"c\":2,\"d\":3}}",
                "{\"b\":{\"c\":99}}");
        assertEquals(JsonParser.parseString("{\"a\":1,\"b\":{\"c\":99,\"d\":3}}"), result);
    }

    @Test
    public void nonObjectNonNullReplacesOutright() {
        JsonElement result = apply(
                "{\"a\":{\"x\":1}}",
                "{\"a\":\"replaced\"}");
        assertEquals(JsonParser.parseString("{\"a\":\"replaced\"}"), result);
    }

    @Test
    public void arrayReplacesWhollyNeverMergedElementByElement() {
        // OQ-8: RFC 7396 known limitation — arrays are always replaced wholesale.
        JsonElement result = apply(
                "{\"list\":[1,2,3]}",
                "{\"list\":[9]}");
        assertEquals(JsonParser.parseString("{\"list\":[9]}"), result);
    }

    @Test
    public void nestedObjectRecursesKeyByKey() {
        JsonElement result = apply(
                "{\"a\":{\"b\":{\"c\":1,\"d\":2}}}",
                "{\"a\":{\"b\":{\"c\":null,\"e\":5}}}");
        assertEquals(JsonParser.parseString("{\"a\":{\"b\":{\"d\":2,\"e\":5}}}"), result);
    }

    @Test
    public void addsNewKeyAbsentFromTarget() {
        JsonElement result = apply(
                "{\"a\":1}",
                "{\"b\":2}");
        assertEquals(JsonParser.parseString("{\"a\":1,\"b\":2}"), result);
    }

    @Test
    public void emptyPatchLeavesTargetUnchanged() {
        JsonElement result = apply("{\"a\":1,\"b\":{\"c\":2}}", "{}");
        assertEquals(JsonParser.parseString("{\"a\":1,\"b\":{\"c\":2}}"), result);
    }
}
