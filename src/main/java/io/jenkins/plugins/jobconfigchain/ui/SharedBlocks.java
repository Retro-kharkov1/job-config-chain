package io.jenkins.plugins.jobconfigchain.ui;

/**
 * Anchor class for the shared Jelly fragments under {@code ui/SharedBlocks/} (versionHistoryBlock,
 * secretsManifestBlock, baseChainBlock, editorBlock), rendered by {@link ConfigTemplatesJobAction},
 * the sole remaining host as of 2026-09-14 (the earlier verbatim-shared host, {@code EnvConfigSetPage},
 * was removed in full — see {@code removal-candidates.md}'s "FULL ENTITY REMOVAL" section).
 *
 * <p>This class holds no members. It exists purely so {@code &lt;st:include class="...SharedBlocks"
 * page="..."/&gt;} has a Java class to resolve each fragment's resource path against — Stapler's
 * {@code st:include} resolves {@code page} relative to the resource folder of the class named by
 * {@code class} (falling back to {@code from}, then {@code it}, when {@code class} is absent).
 * Without an explicit {@code class}/{@code from}, {@code page} resolves relative to the
 * <em>host</em> page's own class instead of this shared folder, which is exactly the bug this
 * class fixes.</p>
 */
public final class SharedBlocks {

    /**
     * Pre-resolved {@link Class} object for use with {@code &lt;st:include class="${it.sharedBlocksClass}"&gt;}
     * instead of a literal string {@code class} attribute — see {@link ConfigTemplatesJobAction#getSharedBlocksClass()}
     * for why (Commons BeanUtils String→Class conversion resolves against the core WebAppClassLoader,
     * not this plugin's own PluginClassLoader).
     */
    public static final Class<SharedBlocks> CLASS = SharedBlocks.class;

    private SharedBlocks() {
    }
}
