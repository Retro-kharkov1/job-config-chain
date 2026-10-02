package io.jenkins.plugins.jobconfigchain.steps;

import hudson.AbortException;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads and writes the pipeline steps' target file with an explicit charset, and checks that the
 * target stays inside the workspace.
 *
 * <p>Read: strict UTF-8 by default. If the bytes are not valid UTF-8 the step logs a warning and
 * falls back to the AGENT's default charset (what {@code FilePath#readToString()} always used), so a
 * file that worked before keeps working. An explicit {@code encoding} is used exactly, with no
 * fallback. The charset actually used is returned so the write puts the bytes back in the same
 * encoding. A byte order mark is neither stripped nor added by this class (a UTF-8 BOM is decoded as
 * U+FEFF and encoded back unchanged).</p>
 *
 * <p>Decoding runs on the agent through {@link FilePath#act}, so "the agent default charset" is the
 * agent JVM's, exactly as before.</p>
 */
final class TargetFileIO {

    private static final Logger LOGGER = Logger.getLogger(TargetFileIO.class.getName());

    private TargetFileIO() {
    }

    /** Decoded content plus the charset that produced it (to be reused for the write). */
    static final class Content {
        final String text;
        final Charset charset;

        Content(String text, Charset charset) {
            this.text = text;
            this.charset = charset;
        }
    }

    /** Wire format of the agent-side read. */
    static final class ReadResult implements Serializable {
        private static final long serialVersionUID = 1L;
        final String text;
        final String charsetName;
        final boolean fellBack;
        /** Non-null when the file could not be decoded at all; describes what was tried. */
        final String failure;

        ReadResult(String text, String charsetName, boolean fellBack, String failure) {
            this.text = text;
            this.charsetName = charsetName;
            this.fellBack = fellBack;
            this.failure = failure;
        }
    }

    static Content read(FilePath target, String encoding, TaskListener listener, String fileLabel)
            throws IOException, InterruptedException {
        String explicit = encoding == null || encoding.trim().isEmpty() ? null : encoding.trim();
        if (explicit != null) {
            requireKnownCharset(explicit);
        }
        ReadResult result = target.act(new Read(explicit));
        if (result.failure != null) {
            throw new AbortException("[configTemplateSync] Target file '" + fileLabel
                    + "' cannot be decoded as " + result.failure);
        }
        Charset used = Charset.forName(result.charsetName);
        if (result.fellBack) {
            listener.getLogger().println("[configTemplateSync][WARN] Target file '" + fileLabel
                    + "' is not valid UTF-8; reading and writing it with the agent default charset "
                    + used.name() + " instead (set the step's 'encoding' parameter to choose explicitly).");
        }
        return new Content(result.text, used);
    }

    static void write(FilePath target, String text, Charset charset) throws IOException, InterruptedException {
        target.write(text, charset.name());
    }

    static Charset requireKnownCharset(String encoding) throws AbortException {
        try {
            return Charset.forName(encoding);
        } catch (IllegalArgumentException e) { // IllegalCharsetName/UnsupportedCharset
            throw new AbortException("[configTemplateSync] Unknown or unsupported 'encoding': '" + encoding + "'");
        }
    }

    /** Warning only (never a failure): logs when the resolved target is not inside the workspace. */
    static void warnIfOutsideWorkspace(FilePath workspace, String file, TaskListener listener) {
        boolean inside;
        try {
            inside = isInsideWorkspace(workspace, file);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not verify workspace containment of " + file, e);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!inside) {
            listener.getLogger().println("[configTemplateSync][WARN] Target file '" + file
                    + "' resolves outside the workspace; processing it anyway. Use a path relative to the "
                    + "workspace to keep deployments contained.");
        }
    }

    static boolean isInsideWorkspace(FilePath workspace, String file) throws IOException, InterruptedException {
        String relative = file;
        if (new File(file).isAbsolute()) {
            String root = workspace.getRemote();
            String sep = root.contains("\\") && !root.contains("/") ? "\\" : "/";
            String prefix = root.endsWith(sep) ? root : root + sep;
            if (!file.startsWith(prefix)) {
                return false;
            }
            relative = file.substring(prefix.length());
            if (relative.isEmpty()) {
                return true;
            }
        }
        return workspace.isDescendant(relative);
    }

    private static final class Read extends MasterToSlaveFileCallable<ReadResult> {
        private static final long serialVersionUID = 1L;
        private final String explicitEncoding;

        Read(String explicitEncoding) {
            this.explicitEncoding = explicitEncoding;
        }

        @Override
        public ReadResult invoke(File f, VirtualChannel channel) throws IOException {
            byte[] bytes = Files.readAllBytes(f.toPath());
            if (explicitEncoding != null) {
                Charset cs = Charset.forName(explicitEncoding);
                try {
                    return new ReadResult(decodeStrict(cs, bytes), cs.name(), false, null);
                } catch (CharacterCodingException e) {
                    return new ReadResult(null, cs.name(), false, cs.name() + " (the configured 'encoding')");
                }
            }
            try {
                return new ReadResult(decodeStrict(StandardCharsets.UTF_8, bytes),
                        StandardCharsets.UTF_8.name(), false, null);
            } catch (CharacterCodingException notUtf8) {
                Charset agentDefault = Charset.defaultCharset();
                try {
                    return new ReadResult(decodeStrict(agentDefault, bytes), agentDefault.name(), true, null);
                } catch (CharacterCodingException e) {
                    return new ReadResult(null, agentDefault.name(), false,
                            "UTF-8 nor in the agent default charset " + agentDefault.name());
                }
            }
        }

        private static String decodeStrict(Charset cs, byte[] bytes) throws CharacterCodingException {
            CharsetDecoder decoder = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
}
