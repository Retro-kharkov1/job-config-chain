package io.github.retrokharkov1.configtemplatesync.merge.tree;

import io.github.retrokharkov1.configtemplatesync.model.ContentType;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML support (NFR-9), backed by SnakeYAML's node-graph-preserving {@code compose}/{@code
 * serialize} round-trip API — NOT {@code Yaml.load()}, which deserializes into plain Java POJOs and
 * would force this adapter to re-invent a mutable tree representation on top of {@code Map}/{@code
 * List}, duplicating exactly the structure {@code Node}/{@code MappingNode}/{@code ScalarNode}
 * already provide.
 */
final class YamlTreeAdapter implements TreeFormat {

    private final Yaml yaml = new Yaml();

    @Override
    public ContentType contentType() {
        return ContentType.YAML;
    }

    @Override
    public TreeNode parse(String content) {
        try {
            Node root = yaml.compose(new StringReader(content == null ? "{}" : content));
            if (root == null) {
                // compose() returns null for an empty/all-comments document — treat as an empty
                // mapping, same "nothing there yet" convention used elsewhere for an empty fold.
                return new NodeWrapper(emptyMappingNode());
            }
            return new NodeWrapper(root);
        } catch (YAMLException e) {
            // YAMLException is already an unchecked RuntimeException — no wrapping needed, matches
            // TreeFormat#parse's documented contract directly.
            throw e;
        }
    }

    @Override
    public TreeNode emptyObject() {
        return new NodeWrapper(emptyMappingNode());
    }

    @Override
    public TreeNode leaf(String value) {
        return new NodeWrapper(new ScalarNode(Tag.STR, value == null ? "" : value, null, null,
                DumperOptions.ScalarStyle.PLAIN));
    }

    @Override
    public String serialize(TreeNode root) {
        StringWriter sw = new StringWriter();
        yaml.serialize(((NodeWrapper) root).node, sw);
        return sw.toString();
    }

    private static MappingNode emptyMappingNode() {
        return new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
    }

    static final class NodeWrapper implements TreeNode {
        final Node node;

        NodeWrapper(Node node) {
            this.node = node;
        }

        @Override
        public boolean isObject() {
            return node instanceof MappingNode;
        }

        @Override
        public boolean isNull() {
            return node.getTag() == Tag.NULL;
        }

        @Override
        public Map<String, TreeNode> childrenIfObject() {
            Map<String, TreeNode> out = new LinkedHashMap<>();
            for (NodeTuple t : ((MappingNode) node).getValue()) {
                out.put(scalarKey(t.getKeyNode()), new NodeWrapper(t.getValueNode()));
            }
            return out;
        }

        @Override
        public boolean hasChild(String key) {
            return childrenIfObject().containsKey(key);
        }

        @Override
        public TreeNode getChild(String key) {
            return childrenIfObject().get(key);
        }

        @Override
        public void putChild(String key, TreeNode value) {
            MappingNode m = (MappingNode) node;
            m.getValue().removeIf(t -> scalarKey(t.getKeyNode()).equals(key));
            m.getValue().add(new NodeTuple(new ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN),
                    ((NodeWrapper) value).node));
        }

        @Override
        public void removeChild(String key) {
            ((MappingNode) node).getValue().removeIf(t -> scalarKey(t.getKeyNode()).equals(key));
        }

        @Override
        public TreeNode deepCopy() {
            // SnakeYAML's Node tree has no built-in deep-clone; round-trip through serialize/compose is
            // the simplest correct deep copy and is not a hot path (called once per merge-fold step).
            StringWriter sw = new StringWriter();
            new Yaml().serialize(node, sw);
            return new NodeWrapper(new Yaml().compose(new StringReader(sw.toString())));
        }

        @Override
        public String leafAsString() {
            // Sequences are whole-value leaves too (uniform rule across all 3 formats) — YAML
            // sequences technically COULD merge element-by-element, but deviating per-format breaks
            // the one-mental-model goal.
            if (node instanceof ScalarNode) {
                return ((ScalarNode) node).getValue();
            }
            StringWriter sw = new StringWriter();
            new Yaml().serialize(node, sw);
            return sw.toString().trim();
        }

        private static String scalarKey(Node keyNode) {
            return ((ScalarNode) keyNode).getValue();
        }
    }
}
