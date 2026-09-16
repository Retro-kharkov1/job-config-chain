package io.jenkins.plugins.jobconfigchain.merge.tree;

import io.jenkins.plugins.jobconfigchain.model.ContentType;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class XmlTreeAdapterTest {

    private static final TreeFormat XML = TreeFormats.forType(ContentType.XML);

    @Test
    public void objectRecurse() {
        TreeNode root = XML.parse("<root><database><host>db.local</host></database></root>");
        assertTrue(root.isObject());
        TreeNode database = root.getChild("database");
        assertTrue(database.isObject());
        assertEquals("db.local", database.getChild("host").leafAsString());
    }

    @Test
    public void attributeAddressedViaAtPrefix() {
        TreeNode root = XML.parse("<root><database enabled=\"true\"><host>db.local</host></database></root>");
        TreeNode database = root.getChild("database");
        Map<String, TreeNode> children = database.childrenIfObject();
        assertTrue(children.containsKey("@enabled"));
        assertEquals("true", children.get("@enabled").leafAsString());
        assertFalse(children.get("@enabled").isObject());
    }

    @Test
    public void repeatedSiblingElementsCollapseToOneWholeValueLeaf() {
        TreeNode root = XML.parse("<root><server>a</server><server>b</server></root>");
        TreeNode server = root.getChild("server");
        assertFalse(server.isObject());
        String rendered = server.leafAsString();
        assertTrue(rendered.contains("<server>a</server>"));
        assertTrue(rendered.contains("<server>b</server>"));
    }

    @Test
    public void xsiNilMarksNodeAsNull() {
        TreeNode root = XML.parse("<root xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<password xsi:nil=\"true\"/></root>");
        TreeNode password = root.getChild("password");
        assertTrue(password.isNull());
    }

    @Test
    public void malformedXmlRejected() {
        try {
            XML.parse("<root><unclosed></root>");
            fail("expected XmlSyntaxException-shaped RuntimeException");
        } catch (RuntimeException expected) {
            // any unchecked RuntimeException satisfies TreeFormat#parse's contract
        }
    }

    @Test
    public void nullRemovesKeyViaMerge() {
        TreeNode target = XML.parse("<root><a>1</a><b>2</b></root>");
        TreeNode patch = XML.parse("<root xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<b xsi:nil=\"true\"/></root>");
        TreeNode result = TreeMergePatch.apply(target, patch);
        assertFalse(result.hasChild("b"));
        assertTrue(result.hasChild("a"));
    }

    @Test
    public void nestedObjectRecursesKeyByKeyViaMerge() {
        TreeNode target = XML.parse("<root><database><host>old</host><port>5432</port></database></root>");
        TreeNode patch = XML.parse("<root><database><host>new</host></database></root>");
        TreeNode result = TreeMergePatch.apply(target, patch);
        TreeNode database = result.getChild("database");
        assertEquals("new", database.getChild("host").leafAsString());
        assertEquals("5432", database.getChild("port").leafAsString());
    }

    /**
     * Regression test for the "Format document" bug: formatting ALREADY-indented XML (as a user
     * would have after a prior format pass) must not accumulate extra blank lines. Round-tripping
     * an already-indented document through parse->serialize must be idempotent — a second
     * parse->serialize pass over the first pass's output must produce byte-identical text.
     */
    @Test
    public void repeatedFormatOfAlreadyIndentedXmlIsIdempotent() {
        String alreadyIndented =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<root>\n"
                        + "  <database>\n"
                        + "    <host>db.local</host>\n"
                        + "    <port>5432</port>\n"
                        + "  </database>\n"
                        + "  <server>\n"
                        + "    <name>primary</name>\n"
                        + "  </server>\n"
                        + "</root>\n";

        String firstPass = XML.serialize(XML.parse(alreadyIndented));
        String secondPass = XML.serialize(XML.parse(firstPass));

        assertEquals("second format pass must be a no-op", firstPass, secondPass);

        String[] lines = firstPass.split("\n", -1);
        // The very last split segment may be an empty string from a trailing newline — that's not
        // a "blank line between elements", so it's excluded from the check.
        for (int i = 0; i < lines.length - 1; i++) {
            assertFalse("no blank/whitespace-only line should appear between elements: " + firstPass,
                    lines[i].trim().isEmpty());
        }
    }

    /**
     * Guards against the fix overreaching: a leaf element's whitespace-only text content is real
     * data (e.g. an intentionally blank/space value) and must survive parse->serialize unchanged.
     */
    @Test
    public void leafWithWhitespaceOnlyTextContentIsPreserved() {
        TreeNode root = XML.parse("<root><value> </value></root>");
        TreeNode value = root.getChild("value");
        assertFalse(value.isObject());
        assertEquals(" ", value.leafAsString());
    }
}
