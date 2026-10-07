package io.jenkins.plugins.jobconfigchain.steps;

import net.sf.json.JSONObject;

import java.util.Map;

/**
 * Snippet Generator form handling shared by the three new-named steps. UI only: it is reached solely through
 * {@code Descriptor.newInstance(StaplerRequest2, JSONObject)}, which Jenkins calls for a submitted form, and never
 * for a Jenkinsfile call (that goes through {@code DescribableModel}), so no step behavior changes.
 *
 * <p>An untouched text or number field is submitted as an empty string. Bound as is, it would become
 * {@code configKey: ''} or {@code version: 0} in the generated snippet; dropping blank entries makes an untouched
 * field mean "not supplied", as the form's defaults promise.</p>
 */
final class SnippetFormSupport {

    private SnippetFormSupport() {
    }

    private static boolean isTrue(Object v) {
        return Boolean.TRUE.equals(v) || (v instanceof String s && Boolean.parseBoolean(s.trim()));
    }

    @SuppressWarnings("unchecked")
    static JSONObject withoutBlankValues(JSONObject formData) {
        JSONObject kept = new JSONObject();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) formData).entrySet()) {
            if (!(e.getValue() instanceof String s && s.trim().isEmpty())) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        // configKey sits in an inline f:optionalBlock bound to useBase. An inline block is not a JSON group, so a
        // key typed before the box was unchecked is still submitted; it is only meaningful with useBase: true
        // (pipeline-steps.md, matrix row 3), so it is dropped here, as the collapsed block promises.
        if (!isTrue(kept.opt("useBase"))) {
            kept.remove("configKey");
        }
        return kept;
    }
}
