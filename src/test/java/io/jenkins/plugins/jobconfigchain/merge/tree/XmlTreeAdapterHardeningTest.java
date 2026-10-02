package io.jenkins.plugins.jobconfigchain.merge.tree;

import io.jenkins.plugins.jobconfigchain.model.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 / Jenkins Security Scan findings S1-S2 (unsafe-classes: {@code TransformerFactory}): the
 * serializer is hardened, its output is byte-identical to what the plain factory produced, the parser
 * still rejects any DOCTYPE, and an external DTD cannot be reached through the hardened factory.
 */
public class XmlTreeAdapterHardeningTest {

    private static final TreeFormat XML = TreeFormats.forType(ContentType.XML);

    /** What {@code serialize} produced before the hardening: the plain default factory. */
    private static String legacySerialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc.getDocumentElement()), new StreamResult(sw));
        return sw.toString();
    }

    @Test
    public void serialize_isByteIdenticalToTheUnhardenedFactoryOutput() throws Exception {
        String xml = "<root a=\"1\"><db><host>café Привіт &amp; co</host></db>"
                + "<s>x</s><s>y</s></root>";
        TreeNode root = XML.parse(xml);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new StringReader(xml)));

        assertEquals(legacySerialize(doc), XML.serialize(root));
    }

    @Test
    public void repeatedSiblingFragment_isSerializedUnchanged() throws Exception {
        TreeNode root = XML.parse("<root><s a=\"1\">x</s><s>y &lt; z</s></root>");
        String fragment = root.getChild("s").leafAsString();

        assertEquals("<s a=\"1\">x</s><s>y &lt; z</s>", fragment);
    }

    @Test
    public void parser_stillRejectsAnyDoctype() {
        assertThrows(RuntimeException.class, () -> XML.parse("<!DOCTYPE root><root><a>1</a></root>"));
        assertThrows(RuntimeException.class, () -> XML.parse(
                "<!DOCTYPE root [<!ENTITY x \"boom\">]><root><a>&x;</a></root>"));
    }

    @Test
    public void hardenedFactory_hasSecureProcessingAndNoExternalAccess() throws Exception {
        TransformerFactory f = XmlTreeAdapter.newHardenedTransformerFactory();

        assertTrue(f.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING));
        assertEquals("", f.getAttribute(XMLConstants.ACCESS_EXTERNAL_DTD));
        assertEquals("", f.getAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET));
    }

    @Test
    public void hardenedFactory_refusesToLoadAnExternalDtd(@TempDir Path dir) throws Exception {
        Path dtd = dir.resolve("ext.dtd");
        Files.writeString(dtd, "<!ENTITY secret \"leaked\">", StandardCharsets.UTF_8);
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE r SYSTEM \"" + dtd.toUri() + "\"><r>&secret;</r>";

        // Control: the plain factory reads the external DTD and expands the entity.
        StringWriter plain = new StringWriter();
        TransformerFactory.newInstance().newTransformer()
                .transform(new StreamSource(new StringReader(xml)), new StreamResult(plain));
        assertTrue(plain.toString().contains("leaked"), "control must prove the DTD is reachable: " + plain);

        Transformer hardened = XmlTreeAdapter.newHardenedTransformerFactory().newTransformer();
        assertThrows(TransformerException.class, () -> hardened.transform(
                new StreamSource(new StringReader(xml)), new StreamResult(new StringWriter())));
    }

    @Test
    public void hardenedFactory_refusesToLoadAnExternalStylesheetImport(@TempDir Path dir) throws Exception {
        Path imported = dir.resolve("imp.xsl");
        Files.writeString(imported, "<xsl:stylesheet version=\"1.0\" "
                + "xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\"/>", StandardCharsets.UTF_8);
        String xsl = "<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">"
                + "<xsl:import href=\"" + imported.toUri() + "\"/></xsl:stylesheet>";

        assertThrows(TransformerConfigurationException.class,
                () -> XmlTreeAdapter.newHardenedTransformerFactory().newTransformer(
                        new StreamSource(new StringReader(xsl))));
    }

    @Test
    public void harden_doesNotThrowWhenTheFactoryRejectsTheAttributes() throws Exception {
        RestrictiveFactory restrictive = new RestrictiveFactory(TransformerFactory.newInstance());

        TransformerFactory result = XmlTreeAdapter.harden(restrictive);

        assertNotNull(result);
        assertTrue(restrictive.secureProcessingSet, "secure processing must still be applied");
        assertEquals(2, restrictive.rejectedAttributes, "both attributes were attempted and tolerated");
        assertFalse(restrictive.attributeAccepted);
    }

    /** A factory that does not know the {@code ACCESS_EXTERNAL_*} attributes (throws like many do). */
    private static final class RestrictiveFactory extends TransformerFactory {
        private final TransformerFactory delegate;
        boolean secureProcessingSet;
        int rejectedAttributes;
        boolean attributeAccepted;

        RestrictiveFactory(TransformerFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public void setFeature(String name, boolean value) throws TransformerConfigurationException {
            if (XMLConstants.FEATURE_SECURE_PROCESSING.equals(name) && value) {
                secureProcessingSet = true;
            }
            delegate.setFeature(name, value);
        }

        @Override
        public void setAttribute(String name, Object value) {
            rejectedAttributes++;
            throw new IllegalArgumentException("unsupported: " + name);
        }

        @Override
        public Transformer newTransformer(Source source) throws TransformerConfigurationException {
            return delegate.newTransformer(source);
        }

        @Override
        public Transformer newTransformer() throws TransformerConfigurationException {
            return delegate.newTransformer();
        }

        @Override
        public Templates newTemplates(Source source) throws TransformerConfigurationException {
            return delegate.newTemplates(source);
        }

        @Override
        public Source getAssociatedStylesheet(Source source, String media, String title, String charset)
                throws TransformerConfigurationException {
            return delegate.getAssociatedStylesheet(source, media, title, charset);
        }

        @Override
        public void setURIResolver(URIResolver resolver) {
            delegate.setURIResolver(resolver);
        }

        @Override
        public URIResolver getURIResolver() {
            return delegate.getURIResolver();
        }

        @Override
        public boolean getFeature(String name) {
            return delegate.getFeature(name);
        }

        @Override
        public Object getAttribute(String name) {
            return delegate.getAttribute(name);
        }

        @Override
        public void setErrorListener(javax.xml.transform.ErrorListener listener) {
            delegate.setErrorListener(listener);
        }

        @Override
        public javax.xml.transform.ErrorListener getErrorListener() {
            return delegate.getErrorListener();
        }
    }
}
