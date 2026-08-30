package io.github.retrokharkov1.configtemplatesync.merge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DriftCheckerTest {

    @Test
    public void detectsMissingTokenNotInEffectiveConfig() {
        JsonObject effective = JsonParser.parseString("{\"a\":1}").getAsJsonObject();
        String fileContent = "value=#{a}# other=#{b}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertTrue(result.hasMissingKeys());
        assertEquals(1, result.getMissingKeys().size());
        assertTrue(result.getMissingKeys().contains("b"));
    }

    @Test
    public void detectsOrphanedKeyNotInFile() {
        JsonObject effective = JsonParser.parseString("{\"a\":1,\"b\":2}").getAsJsonObject();
        String fileContent = "value=#{a}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertFalse(result.hasMissingKeys());
        assertTrue(result.hasOrphanedKeys());
        assertTrue(result.getOrphanedKeys().contains("b"));
    }

    @Test
    public void noDriftWhenSetsMatchExactly() {
        JsonObject effective = JsonParser.parseString("{\"a\":1,\"b\":2}").getAsJsonObject();
        String fileContent = "#{a}# #{b}#";

        DriftResult result = DriftChecker.diff(effective, fileContent);

        assertFalse(result.hasMissingKeys());
        assertFalse(result.hasOrphanedKeys());
    }
}
