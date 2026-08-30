package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JsonPathsTest {

    @Test
    public void flattenProducesDottedPaths() {
        JsonElement root = JsonParser.parseString("{\"a\":{\"b\":1,\"c\":{\"d\":2}},\"e\":3}");
        Map<String, JsonElement> flattened = JsonPaths.flatten(root);

        assertEquals(3, flattened.size());
        assertTrue(flattened.containsKey("a.b"));
        assertTrue(flattened.containsKey("a.c.d"));
        assertTrue(flattened.containsKey("e"));
    }

    @Test
    public void getReturnsNullForMissingPath() {
        JsonElement root = JsonParser.parseString("{\"a\":{\"b\":1}}");
        assertNull(JsonPaths.get(root.getAsJsonObject(), "a.x"));
        assertNull(JsonPaths.get(root.getAsJsonObject(), "z"));
    }

    @Test
    public void getResolvesExistingPath() {
        JsonElement root = JsonParser.parseString("{\"a\":{\"b\":1}}");
        JsonElement value = JsonPaths.get(root.getAsJsonObject(), "a.b");
        assertEquals(1, value.getAsInt());
    }
}
