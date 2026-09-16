package io.jenkins.plugins.jobconfigchain.merge.tree;

import io.jenkins.plugins.jobconfigchain.model.ContentType;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XML support (see nfr.md's "Minimize new dependency footprint for multi-format content" rule):
 * JDK-builtin {@code javax.xml.parsers}/DOM only, no new Maven dependency.
 *
 * <p><b>Convention 1 — repeated sibling elements are one whole-value leaf</b>: if a parent element
 * has &gt;1 direct child with the same tag name, that tag becomes a single leaf whose {@link
 * TreeNode#leafAsString()} is the raw re-serialized XML fragment of all repeated children
 * concatenated in document order — mirroring the "arrays are whole-value leaves" rule already
 * established for JSON. A caller that needs to REPLACE that leaf (RFC-7396-equivalent patch)
 * supplies a raw XML fragment string as the patch value; {@code putChild} then re-parses that
 * fragment and splices in the resulting element(s) wholesale, replacing every prior same-tag
 * sibling.</p>
 *
 * <p><b>Convention 2 — attribute addressing.</b> An element's attribute {@code attrName} is addressed
 * as a synthetic child key {@code "@attrName"} (dotted-path segment {@code parent.@attrName}) —
 * disambiguated from a same-named child ELEMENT by the leading {@code @}, which is not a legal XML
 * element-name first character, so there is no collision risk with any real tag name.</p>
 *
 * <p><b>Convention 3 — null/removal sentinel.</b> RFC 7396 has no native XML null. An explicit
 * {@code xsi:nil="true"} attribute on the PATCH element is the removal sentinel this adapter checks
 * for — {@code isNull()} returns true iff the element carries {@code xmlns:xsi="http://www.w3.org/2001/
 * XMLSchema-instance"}-bound {@code xsi:nil="true"}. This is a NEW convention with no prior precedent
 * in this codebase — documented in the editor help text, not just here.</p>
 */
final class XmlTreeAdapter implements TreeFormat {

    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    @Override
    public ContentType contentType() {
        return ContentType.XML;
    }

    @Override
    public TreeNode parse(String content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Hardening per standard JDK-DOM XXE guidance — this plugin parses operator-authored
            // content, not untrusted external input, but the hardening is zero-cost and removes an
            // otherwise-open XXE class from the attack surface.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(content == null ? "<root/>" : content)));
            stripIndentWhitespace(doc.getDocumentElement());
            return new ElementNode(doc.getDocumentElement());
        } catch (Exception e) {
            // ParserConfigurationException / SAXException / IOException — all wrapped unchecked to
            // satisfy TreeFormat#parse's documented contract (matches JsonSyntaxException's unchecked
            // shape for the JSON adapter, so validateSyntaxOrFail's single catch(RuntimeException) works
            // uniformly across all three formats).
            throw new XmlSyntaxException("Malformed XML: " + e.getMessage(), e);
        }
    }

    @Override
    public TreeNode emptyObject() {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = doc.createElement("root");
            doc.appendChild(root);
            return new ElementNode(root);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("DocumentBuilderFactory unavailable", e);
        }
    }

    @Override
    public TreeNode leaf(String value) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element el = doc.createElement("value");
            el.setTextContent(value == null ? "" : value);
            doc.appendChild(el);
            return new ElementNode(el);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("DocumentBuilderFactory unavailable", e);
        }
    }

    /**
     * Recursively strips whitespace-only {@code Text} node children from any element that has at
     * least one child ELEMENT node ("object" nodes per {@link ElementNode#isObject()} /
     * {@link ElementNode#childrenIfObject()} semantics, which already ignore text nodes entirely
     * for such elements). Without this, a plain non-validating parser treats pre-existing
     * indentation whitespace between tags as ordinary text content; the indenting {@link
     * Transformer} used by {@link #serialize} then layers its OWN indentation on top of that
     * leftover whitespace on every format pass, producing an extra blank line between elements
     * that compounds on every re-format. Leaf elements (no child elements) are left untouched —
     * their whitespace-only text content could be genuine user data (e.g. a value that's
     * intentionally just a space) and {@link ElementNode#leafAsString()} reads it verbatim via
     * {@code getTextContent()}.
     */
    private static void stripIndentWhitespace(Element element) {
        boolean hasChildElement = false;
        NodeList kids = element.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                hasChildElement = true;
                break;
            }
        }
        if (hasChildElement) {
            for (int i = kids.getLength() - 1; i >= 0; i--) {
                org.w3c.dom.Node n = kids.item(i);
                if (n.getNodeType() == org.w3c.dom.Node.TEXT_NODE && n.getTextContent().trim().isEmpty()) {
                    element.removeChild(n);
                }
            }
        }
        kids = element.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                stripIndentWhitespace((Element) kids.item(i));
            }
        }
    }

    @Override
    public String serialize(TreeNode root) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(((ElementNode) root).element), new StreamResult(sw));
            return sw.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException("Failed to serialize XML", e);
        }
    }

    static final class ElementNode implements TreeNode {
        final Element element;

        ElementNode(Element element) {
            this.element = element;
        }

        @Override
        public boolean isObject() {
            // An element with only text content (no child elements) is a leaf, not an object.
            return hasAnyChildElement();
        }

        @Override
        public boolean isNull() {
            return "true".equals(element.getAttributeNS(XSI_NS, "nil"));
        }

        @Override
        public Map<String, TreeNode> childrenIfObject() {
            Map<String, TreeNode> out = new LinkedHashMap<>();
            NodeList kids = element.getChildNodes();
            Map<String, List<Element>> byTag = new LinkedHashMap<>();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    Element child = (Element) kids.item(i);
                    byTag.computeIfAbsent(child.getTagName(), k -> new ArrayList<>()).add(child);
                }
            }
            for (Map.Entry<String, List<Element>> e : byTag.entrySet()) {
                if (e.getValue().size() == 1) {
                    out.put(e.getKey(), new ElementNode(e.getValue().get(0)));
                } else {
                    // Convention 1: repeated siblings collapse to one whole-value leaf.
                    out.put(e.getKey(), new RepeatedSiblingLeaf(e.getValue()));
                }
            }
            NamedNodeMap attrs = element.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                if (XSI_NS.equals(a.getNamespaceURI()) && "nil".equals(a.getLocalName())) {
                    continue;
                }
                out.put("@" + a.getName(), new AttrLeaf(a.getValue()));
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
            if (key.startsWith("@")) {
                element.setAttribute(key.substring(1), value.leafAsString());
                return;
            }
            // Replace every prior same-tag child (Convention 1) before inserting the new value.
            removeChild(key);
            if (value instanceof RepeatedSiblingLeaf) {
                for (Element frag : ((RepeatedSiblingLeaf) value).elements) {
                    element.appendChild(element.getOwnerDocument().importNode(frag, true));
                }
            } else if (value instanceof ElementNode) {
                Element imported = (Element) element.getOwnerDocument()
                        .importNode(((ElementNode) value).element, true);
                // The imported element carries its own tag name (e.g. "value" from TreeFormat#leaf) —
                // rename it to the requested key so the parent's child map reflects the intended tag.
                if (!imported.getTagName().equals(key)) {
                    Element renamed = element.getOwnerDocument().createElement(key);
                    NodeList grandKids = imported.getChildNodes();
                    while (grandKids.getLength() > 0) {
                        renamed.appendChild(grandKids.item(0));
                    }
                    NamedNodeMap attrs = imported.getAttributes();
                    for (int i = 0; i < attrs.getLength(); i++) {
                        Attr a = (Attr) attrs.item(i);
                        renamed.setAttribute(a.getName(), a.getValue());
                    }
                    imported = renamed;
                }
                element.appendChild(imported);
            } else {
                // AttrLeaf or any other plain leaf being inserted as a child element: create a fresh
                // element named `key` holding the leaf's string value as text content.
                Element created = element.getOwnerDocument().createElement(key);
                created.setTextContent(value.leafAsString());
                element.appendChild(created);
            }
        }

        @Override
        public void removeChild(String key) {
            if (key.startsWith("@")) {
                element.removeAttribute(key.substring(1));
                return;
            }
            NodeList kids = element.getChildNodes();
            for (int i = kids.getLength() - 1; i >= 0; i--) {
                if (kids.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                        && ((Element) kids.item(i)).getTagName().equals(key)) {
                    element.removeChild(kids.item(i));
                }
            }
        }

        @Override
        public TreeNode deepCopy() {
            return new ElementNode((Element) element.cloneNode(true));
        }

        @Override
        public String leafAsString() {
            return element.getTextContent();
        }

        private boolean hasAnyChildElement() {
            NodeList kids = element.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Attribute leaf (Convention 2) — isObject()==false, isNull()==false. */
    static final class AttrLeaf implements TreeNode {
        private final String value;

        AttrLeaf(String value) {
            this.value = value;
        }

        @Override
        public boolean isObject() {
            return false;
        }

        @Override
        public boolean isNull() {
            return false;
        }

        @Override
        public Map<String, TreeNode> childrenIfObject() {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public boolean hasChild(String key) {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public TreeNode getChild(String key) {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public void putChild(String key, TreeNode value) {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public void removeChild(String key) {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public TreeNode deepCopy() {
            return new AttrLeaf(value);
        }

        @Override
        public String leafAsString() {
            return value;
        }
    }

    /** Repeated-sibling-elements whole-value leaf (Convention 1). */
    static final class RepeatedSiblingLeaf implements TreeNode {
        final List<Element> elements;

        RepeatedSiblingLeaf(List<Element> elements) {
            this.elements = elements;
        }

        @Override
        public boolean isObject() {
            return false;
        }

        @Override
        public boolean isNull() {
            return false;
        }

        @Override
        public Map<String, TreeNode> childrenIfObject() {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public boolean hasChild(String key) {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public TreeNode getChild(String key) {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public void putChild(String key, TreeNode value) {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public void removeChild(String key) {
            throw new IllegalStateException("Not an object node");
        }

        @Override
        public TreeNode deepCopy() {
            List<Element> copies = new ArrayList<>();
            for (Element e : elements) {
                copies.add((Element) e.cloneNode(true));
            }
            return new RepeatedSiblingLeaf(copies);
        }

        @Override
        public String leafAsString() {
            StringBuilder sb = new StringBuilder();
            for (Element e : elements) {
                try {
                    Transformer t = TransformerFactory.newInstance().newTransformer();
                    t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                    StringWriter sw = new StringWriter();
                    t.transform(new DOMSource(e), new StreamResult(sw));
                    sb.append(sw);
                } catch (TransformerException ex) {
                    throw new IllegalStateException("Failed to serialize repeated-sibling fragment", ex);
                }
            }
            return sb.toString();
        }
    }
}
