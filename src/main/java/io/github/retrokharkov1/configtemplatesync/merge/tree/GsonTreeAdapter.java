package io.github.retrokharkov1.configtemplatesync.merge.tree;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.retrokharkov1.configtemplatesync.model.ContentType;

import java.util.LinkedHashMap;
import java.util.Map;

/** Behavior-neutral wrap of the plugin's pre-existing Gson-typed JSON logic. */
final class GsonTreeAdapter implements TreeFormat {

    @Override
    public ContentType contentType() {
        return ContentType.JSON;
    }

    @Override
    public TreeNode parse(String content) {
        // JsonParser.parseString throws JsonSyntaxException (unchecked) on malformed JSON — matches
        // TreeFormat#parse's documented unchecked-exception contract with zero wrapping needed.
        JsonElement el = JsonParser.parseString(content == null ? "" : content);
        return new Node(el);
    }

    @Override
    public TreeNode emptyObject() {
        return new Node(new JsonObject());
    }

    @Override
    public TreeNode leaf(String value) {
        return new Node(new JsonPrimitive(value));
    }

    @Override
    public String serialize(TreeNode root) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(((Node) root).element);
    }

    /** Package-private so JsonMergePatch/TreePaths (same package) can unwrap for the rare case they need it. */
    static final class Node implements TreeNode {
        final JsonElement element;

        Node(JsonElement element) {
            this.element = element;
        }

        @Override
        public boolean isObject() {
            return element.isJsonObject();
        }

        @Override
        public boolean isNull() {
            return element == null || element.isJsonNull();
        }

        @Override
        public Map<String, TreeNode> childrenIfObject() {
            Map<String, TreeNode> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                out.put(e.getKey(), new Node(e.getValue()));
            }
            return out;
        }

        @Override
        public boolean hasChild(String key) {
            return element.getAsJsonObject().has(key);
        }

        @Override
        public TreeNode getChild(String key) {
            JsonObject obj = element.getAsJsonObject();
            return obj.has(key) ? new Node(obj.get(key)) : null;
        }

        @Override
        public void putChild(String key, TreeNode value) {
            element.getAsJsonObject().add(key, value == null ? JsonNull.INSTANCE : ((Node) value).element);
        }

        @Override
        public void removeChild(String key) {
            element.getAsJsonObject().remove(key);
        }

        @Override
        public TreeNode deepCopy() {
            return new Node(element.deepCopy());
        }

        @Override
        public String leafAsString() {
            // Arrays are whole-value leaves (OQ-8) — unchanged: render via JsonElement#toString exactly
            // as JsonPaths.leafAsString did today.
            if (element == null || element.isJsonNull()) {
                return "";
            }
            if (element instanceof JsonPrimitive) {
                JsonPrimitive p = (JsonPrimitive) element;
                return p.isString() ? p.getAsString() : p.toString();
            }
            return element.toString();
        }
    }
}
