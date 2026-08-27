package io.github.retrokharkov1.configtemplatesync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the "merge general(common)+env, env overrides on conflict -&gt; THEN apply secrets by
 * key" two-tier resolution as a first-class case, including secrets-manifest override precedence.
 */
class EffectiveConfigResolverTest {

    @Test
    void envLayerWinsOnConflictOverCommonLayer(@TempDir File tempDir) {
        ConfigSetRepository repo = new ConfigSetRepository(tempDir);
        repo.save("proj-common",
                "{\"AppSettings\":{\"TimeoutSeconds\":\"30\",\"FeatureX\":\"off\"},\"Db\":{\"Password\":\"__SECRET__\"}}",
                Collections.singletonMap("Db.Password", "proj-common-secrets"),
                "seed common", "alice", true);
        repo.save("proj-dev",
                "{\"AppSettings\":{\"TimeoutSeconds\":\"5\"}}",
                null, "seed dev override", "alice", true);

        EffectiveConfigResolver.Effective effective =
                EffectiveConfigResolver.resolve(repo, "proj-common", "proj-dev");
        Map<String, String> flat = ConfigJsonTree.flatten(effective.mergedJson);

        assertEquals("5", flat.get("AppSettings.TimeoutSeconds"), "env value must win");
        assertEquals("off", flat.get("AppSettings.FeatureX"), "common-only key must survive merge");
        assertEquals("__SECRET__", flat.get("Db.Password"));
        assertEquals("proj-common-secrets", effective.mergedSecretsManifest.get("Db.Password"),
                "secrets manifest entry only present in common must carry through");
    }

    @Test
    void envSecretsManifestEntryWinsOverCommonOnSameKey(@TempDir File tempDir) {
        ConfigSetRepository repo = new ConfigSetRepository(tempDir);
        Map<String, String> commonManifest = new LinkedHashMap<>();
        commonManifest.put("Db.Password", "old-common-cred-id");
        repo.save("proj-common", "{\"Db\":{\"Password\":\"__SECRET__\"}}", commonManifest, null, "alice", true);

        Map<String, String> envManifest = new LinkedHashMap<>();
        envManifest.put("Db.Password", "env-specific-cred-id");
        repo.save("proj-dev", "{}", envManifest, null, "alice", true);

        EffectiveConfigResolver.Effective effective =
                EffectiveConfigResolver.resolve(repo, "proj-common", "proj-dev");
        assertEquals("env-specific-cred-id", effective.mergedSecretsManifest.get("Db.Password"),
                "env-layer manifest entry must win under the same override rule as JSON keys");
    }

    @Test
    void commonConfigSetOptionalWhenBlank(@TempDir File tempDir) {
        ConfigSetRepository repo = new ConfigSetRepository(tempDir);
        repo.save("proj-dev", "{\"A\":\"1\"}", null, null, "alice", true);

        EffectiveConfigResolver.Effective effective = EffectiveConfigResolver.resolve(repo, null, "proj-dev");
        assertEquals("1", ConfigJsonTree.flatten(effective.mergedJson).get("A"));
    }

    @Test
    void resolveAtVersionsUsesExplicitHistoricalVersionsNotActive(@TempDir File tempDir) {
        ConfigSetRepository repo = new ConfigSetRepository(tempDir);
        int commonV1 = repo.save("proj-common", "{\"A\":\"c1\"}", null, "v1", "alice", true);
        repo.save("proj-common", "{\"A\":\"c2\"}", null, "v2", "bob", true); // now-active, but not what we want

        int devV1 = repo.save("proj-dev", "{\"B\":\"d1\"}", null, "v1", "alice", true);
        repo.save("proj-dev", "{\"B\":\"d2\"}", null, "v2", "bob", true); // now-active, but not what we want

        EffectiveConfigResolver.Effective effective = EffectiveConfigResolver.resolveAtVersions(
                repo, "proj-common", commonV1, "proj-dev", devV1);
        Map<String, String> flat = ConfigJsonTree.flatten(effective.mergedJson);

        assertEquals("c1", flat.get("A"), "must use the pinned common version, not the currently-active one");
        assertEquals("d1", flat.get("B"), "must use the pinned env version, not the currently-active one");
    }

    @Test
    void resolveAtVersionsWithNullCommonVersionNumberSkipsCommonLayer(@TempDir File tempDir) {
        ConfigSetRepository repo = new ConfigSetRepository(tempDir);
        int devV1 = repo.save("proj-dev", "{\"B\":\"d1\"}", null, "v1", "alice", true);

        EffectiveConfigResolver.Effective effective = EffectiveConfigResolver.resolveAtVersions(
                repo, null, null, "proj-dev", devV1);
        assertEquals("d1", ConfigJsonTree.flatten(effective.mergedJson).get("B"));
    }
}
