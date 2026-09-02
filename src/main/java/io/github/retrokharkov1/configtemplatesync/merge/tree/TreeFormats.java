package io.github.retrokharkov1.configtemplatesync.merge.tree;

import io.github.retrokharkov1.configtemplatesync.model.ContentType;

import java.util.EnumMap;
import java.util.Map;

/** Static registry (FR-66's single dispatch point) — no reflection, no service-loader, a plain enum switch. */
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
            // without a matching adapter — fail loud rather than NPE deeper in the call chain (NFR-7).
            throw new IllegalStateException("No TreeFormat registered for " + type);
        }
        return format;
    }
}
