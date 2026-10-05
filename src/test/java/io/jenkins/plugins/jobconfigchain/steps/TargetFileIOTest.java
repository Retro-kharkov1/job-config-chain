package io.jenkins.plugins.jobconfigchain.steps;

import hudson.AbortException;
import hudson.FilePath;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Plain-JUnit tests for the P2 file I/O helper and the single-pass token replacement. */
public class TargetFileIOTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private final StreamTaskListener listener = new StreamTaskListener(logBytes, StandardCharsets.UTF_8);

    private String log() {
        listener.getLogger().flush();
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    private FilePath file(String name, byte[] bytes) throws Exception {
        Path p = tmp.resolve(name);
        Files.write(p, bytes);
        return new FilePath(p.toFile());
    }

    // ---- read: strict UTF-8, fallback, explicit encoding ----------------------------------------

    @Test
    public void validUtf8_isDecodedAsUtf8_withoutWarning() throws Exception {
        byte[] bytes = "café 日本".getBytes(StandardCharsets.UTF_8);

        TargetFileIO.Content c = TargetFileIO.read(file("a.txt", bytes), null, listener, "a.txt");

        assertEquals("café 日本", c.text);
        assertEquals(StandardCharsets.UTF_8, c.charset);
        assertFalse(log().contains("WARN"));
    }

    @Test
    public void utf8Bom_isKeptAsU_FEFF_andWrittenBackUnchanged() throws Exception {
        byte[] bytes = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'k', '=', 'v'};
        FilePath f = file("bom.txt", bytes);

        TargetFileIO.Content c = TargetFileIO.read(f, null, listener, "bom.txt");
        assertEquals("﻿k=v", c.text);
        TargetFileIO.write(f, c.text, c.charset);

        assertArrayEquals(bytes, Files.readAllBytes(tmp.resolve("bom.txt")));
    }

    @Test
    public void noBomIsAddedToPlainUtf8() throws Exception {
        FilePath f = file("nobom.txt", "k=v".getBytes(StandardCharsets.UTF_8));

        TargetFileIO.Content c = TargetFileIO.read(f, null, listener, "nobom.txt");
        TargetFileIO.write(f, c.text, c.charset);

        assertArrayEquals("k=v".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(tmp.resolve("nobom.txt")));
    }

    @Test
    public void invalidUtf8_fallsBackToTheAgentDefaultCharset_orFailsExactlyLikeToday() throws Exception {
        byte[] bytes = new byte[] {'c', 'a', 'f', (byte) 0xE9}; // ISO-8859-1 "cafe-acute", invalid UTF-8
        FilePath f = file("latin.txt", bytes);
        Charset dflt = Charset.defaultCharset();
        boolean defaultCanDecode = canDecode(dflt, bytes);

        if (defaultCanDecode) {
            TargetFileIO.Content c = TargetFileIO.read(f, null, listener, "latin.txt");
            assertEquals(dflt, c.charset);
            assertEquals(new String(bytes, dflt), c.text);
            assertTrue(log().contains("[configTemplateSync][WARN] Target file 'latin.txt' is not valid UTF-8"),
                    log());
            // lossless round trip with the charset actually used
            TargetFileIO.write(f, c.text, c.charset);
            assertArrayEquals(bytes, Files.readAllBytes(tmp.resolve("latin.txt")));
        } else {
            // Same outcome as FilePath#readToString() on this default charset: the read fails.
            assertThrows(AbortException.class, () -> TargetFileIO.read(f, null, listener, "latin.txt"));
        }
    }

    @Test
    public void explicitEncoding_isUsedExactly_noFallbackNoWarning() throws Exception {
        byte[] bytes = new byte[] {'c', 'a', 'f', (byte) 0xE9};
        FilePath f = file("latin1.txt", bytes);

        TargetFileIO.Content c = TargetFileIO.read(f, "ISO-8859-1", listener, "latin1.txt");

        assertEquals("café", c.text);
        assertEquals(StandardCharsets.ISO_8859_1, c.charset);
        assertFalse(log().contains("WARN"));
        TargetFileIO.write(f, c.text, c.charset);
        assertArrayEquals(bytes, Files.readAllBytes(tmp.resolve("latin1.txt")));
    }

    @Test
    public void explicitUtf8_onInvalidBytes_failsWithoutFallback() throws Exception {
        FilePath f = file("bad.txt", new byte[] {'c', (byte) 0xE9});

        AbortException e = assertThrows(AbortException.class, () -> TargetFileIO.read(f, "UTF-8", listener, "bad.txt"));

        assertTrue(e.getMessage().contains("[configTemplateSync] Target file 'bad.txt' cannot be decoded as UTF-8"),
                e.getMessage());
        assertFalse(log().contains("agent default"));
    }

    @Test
    public void unknownEncoding_failsWithAClearMessage() throws Exception {
        FilePath f = file("x.txt", new byte[] {'a'});

        AbortException e = assertThrows(AbortException.class, () -> TargetFileIO.read(f, "no-such-charset", listener, "x.txt"));
        assertEquals("[configTemplateSync] Unknown or unsupported 'encoding': 'no-such-charset'", e.getMessage());

        AbortException illegal = assertThrows(AbortException.class, () -> TargetFileIO.read(f, "bad name!", listener, "x.txt"));
        assertTrue(illegal.getMessage().contains("Unknown or unsupported 'encoding'"));
    }

    @Test
    public void blankEncoding_meansDefault() throws Exception {
        FilePath f = file("blank.txt", "é".getBytes(StandardCharsets.UTF_8));

        TargetFileIO.Content c = TargetFileIO.read(f, "  ", listener, "blank.txt");

        assertEquals("é", c.text);
        assertEquals(StandardCharsets.UTF_8, c.charset);
    }

    private static boolean canDecode(Charset cs, byte[] bytes) {
        try {
            cs.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    // ---- workspace containment ------------------------------------------------------------------

    @Test
    public void containment_relativeInside_isOk_dotDotEscape_isOutside() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(ws.resolve("sub"));
        Files.writeString(ws.resolve("sub").resolve("a.cfg"), "x");
        Files.writeString(tmp.resolve("outside.cfg"), "x");
        FilePath workspace = new FilePath(ws.toFile());

        assertTrue(TargetFileIO.isInsideWorkspace(workspace, "sub/a.cfg"));
        assertTrue(TargetFileIO.isInsideWorkspace(workspace, "sub/../sub/a.cfg"));
        assertFalse(TargetFileIO.isInsideWorkspace(workspace, "../outside.cfg"));
        assertFalse(TargetFileIO.isInsideWorkspace(workspace, "sub/../../outside.cfg"));
    }

    @Test
    public void containment_absolutePaths() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path inside = Files.writeString(ws.resolve("a.cfg"), "x");
        Path outside = Files.writeString(tmp.resolve("outside.cfg"), "x");
        FilePath workspace = new FilePath(ws.toFile());

        assertTrue(TargetFileIO.isInsideWorkspace(workspace, inside.toAbsolutePath().toString()));
        assertFalse(TargetFileIO.isInsideWorkspace(workspace, outside.toAbsolutePath().toString()));
    }

    @Test
    public void containment_symlinkPointingOutside_isOutside() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path outsideDir = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(outsideDir.resolve("secret.cfg"), "x");
        try {
            Files.createSymbolicLink(ws.resolve("link"), outsideDir);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            assumeTrue(false, "symbolic links not available: " + e);
        }
        FilePath workspace = new FilePath(ws.toFile());

        assertFalse(TargetFileIO.isInsideWorkspace(workspace, "link/secret.cfg"));
    }

    @Test
    public void warnIfOutsideWorkspace_warnsOnly_andStaysQuietInside() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.cfg"), "x");
        FilePath workspace = new FilePath(ws.toFile());

        TargetFileIO.warnIfOutsideWorkspace(workspace, "a.cfg", listener);
        assertEquals("", log());

        TargetFileIO.warnIfOutsideWorkspace(workspace, "../a.cfg", listener);
        assertTrue(log().startsWith("[configTemplateSync][WARN] Target file '../a.cfg' resolves outside the workspace"),
                log());
    }

    // ---- single-pass replacement ----------------------------------------------------------------

    private static Map<String, String> values(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void replaceTokens_isLiteral_noRegexReplacementSyntax() {
        String out = ConfigTemplateSubstituteStep.Execution.replaceTokens("a=#{A}# b=#{B}#",
                values("A", "$1\\${x}$&", "B", "\\"));

        assertEquals("a=$1\\${x}$& b=\\", out);
    }

    @Test
    public void replaceTokens_neverRescansSubstitutedText() {
        // A's value looks like B's token; B is known too, but must NOT be expanded inside A's value.
        String out = ConfigTemplateSubstituteStep.Execution.replaceTokens("#{A}# #{B}#",
                values("A", "x#{B}#y", "B", "bee"));

        assertEquals("x#{B}#y bee", out);
    }

    @Test
    public void replaceTokens_orderIndependent() {
        String content = "#{A}#|#{B}#";
        assertEquals(ConfigTemplateSubstituteStep.Execution.replaceTokens(content, values("A", "#{B}#", "B", "1")),
                ConfigTemplateSubstituteStep.Execution.replaceTokens(content, values("B", "1", "A", "#{B}#")));
    }

    @Test
    public void replaceTokens_leavesUnknownTokensAndLookalikesAlone() {
        String out = ConfigTemplateSubstituteStep.Execution.replaceTokens(
                "#{Known}# #{Unknown}# ${Known} #{Known", values("Known", "k"));

        assertEquals("k #{Unknown}# ${Known} #{Known", out);
    }

    @Test
    public void replaceTokens_adjacentAndRepeated() {
        assertEquals("11112222", ConfigTemplateSubstituteStep.Execution.replaceTokens("#{A}##{A}##{B}##{B}#",
                values("A", "11", "B", "22")));
    }

    @Test
    public void replaceTokens_emptyMap_returnsContent() {
        assertEquals("x #{A}#", ConfigTemplateSubstituteStep.Execution.replaceTokens("x #{A}#", values()));
    }

    // ---- serialization compatibility of the step executions -------------------------------------

    @Test
    public void executions_keepSerialVersionUid_andTheNewEncodingFieldIsNullTolerant() throws Exception {
        for (Class<?> c : new Class<?>[] {ConfigTemplateSubstituteStep.Execution.class,
                ConfigTemplateValidateStep.Execution.class}) {
            assertEquals(1L, ObjectStreamClass.lookup(c).getSerialVersionUID(), c.getName());
            Field encoding = c.getDeclaredField("encoding");
            assertFalse(Modifier.isFinal(encoding.getModifiers()), c.getName());
            assertFalse(Modifier.isTransient(encoding.getModifiers()), c.getName());
            assertEquals(String.class, encoding.getType());
            assertNotNull(ObjectStreamClass.lookup(c).getField("encoding"));
            // The legacy-shaped constructor (what old callers and old state correspond to) leaves it null.
            Object legacy = c == ConfigTemplateSubstituteStep.Execution.class
                    ? new ConfigTemplateSubstituteStep.Execution(null, "f", null, null, null, null)
                    : new ConfigTemplateValidateStep.Execution(null, "f", null, null, null);
            encoding.setAccessible(true);
            assertEquals(null, encoding.get(legacy));
        }
    }

}
