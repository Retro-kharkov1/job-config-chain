package io.jenkins.plugins.jobconfigchain.merge.tree;

import io.jenkins.plugins.jobconfigchain.model.ContentType;

import java.util.EnumMap;
import java.util.Map;

/** Static registry (the single content-type dispatch point every reader must go through) — no reflection, no service-loader, a plain enum switch. */
public final class TreeFormats {

    private static final Map<ContentType, TreeFormat> REGISTRY = new EnumMap<>(ContentType.class);

    static {
        REGISTRY.put(ContentType.JSON, new GsonTreeAdapter());
        REGISTRY.put(ContentType.XML, new XmlTreeAdapter());
        REGISTRY.put(ContentType.YAML, new YamlTreeAdapter());
    }

    private TreeFormats() {
    }

    public static TreeFormat forType(ContentType type) {
        TreeFormat format = REGISTRY.get(type);
        if (format == null) {
            // structurally unreachable once ContentType is exhaustively registered above; a hard
            // IllegalStateException here would only ever fire if a 4th enum constant were added
            // without a matching adapter — fail loud rather than NPE deeper in the call chain (see
            // nfr.md's "Fail loud, never fail silent" rule).
            throw new IllegalStateException("No TreeFormat registered for " + type);
        }
        return format;
    }
}
