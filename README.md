# config-template-sync

Jenkins plugin: a JSON-shaped, versioned configuration store (`ConfigSet` / `ConfigSetVersion`)
persisted natively inside `$JENKINS_HOME` — no git, no external database — plus two Pipeline
steps that validate and substitute `#{Path}#` tokens into a target config file (e.g. an
`appsettings.Production.json`-shaped file) from that store, with RFC 7396 JSON Merge Patch
semantics for layering a per-environment override on top of a shared/common base, and
build-version config pinning so a redeploy of an older build replays the config it actually
shipped with.

This is a **generic, reusable** plugin — it has no knowledge of any specific project. A
consuming project supplies its own `ConfigSet` keys (naming convention only, e.g.
`myapp-common` / `myapp-dev`) and Jenkinsfile.

## Status: Milestone 1 (MVP)

Persistence + domain model + the two Pipeline steps are implemented and tested
(`mvn clean verify`). **No Jelly/Stapler UI yet** — until Milestone 2 lands, `ConfigSet` content
is authored via the interim Script Console path documented below. Multi-instance distribution
via a private Jenkins Update Center is a later milestone (see `docs/plan` notes / project history)
— for now, install via direct `.hpi` upload on one instance at a time.

## Domain model

- **`ConfigSet`** — a named, versioned JSON configuration bucket. A project typically has TWO:
  a **common** set (e.g. `myapp-common`, full nested JSON) and a **per-env** set (e.g.
  `myapp-dev`, a **sparse RFC 7396 merge-patch overlay** relative to common — only the keys that
  env adds/overrides, not a duplicated copy of everything). Secret leaves in either layer hold the
  literal marker `"__SECRET__"` — real secret values never enter this store; they're referenced by
  Jenkins credential ID via each `ConfigSet`'s secrets manifest and resolved by the calling
  Jenkinsfile at substitution time (e.g. via `withCredentials`/`withEnv`).
- **`ConfigSetVersion`** — append-only (`versionNumber` = max+1, never reused); exactly one
  version per `ConfigSet` is `active` at a time. "Save" appends a new version; "activate/rollback"
  flips the `active` flag onto an existing version in place — no content is ever cloned.
- **Effective/merged config** = `applyMergePatch(activeCommon.contentJson, activeEnv.contentJson)`
  per [RFC 7396](https://www.rfc-editor.org/rfc/rfc7396) — computed on read by
  `EffectiveConfigResolver`, never persisted as a third copy. A key set to JSON `null` in the env
  overlay deletes that key from the merged result; an absent key is inherited from common
  unchanged.
- **`ConfigDeploymentBinding`** — records which exact common+env version numbers a given
  `buildVersion` last used, keyed by the env `ConfigSet`'s key. Recorded automatically after every
  successful real `configTemplateSubstitute` run that passed a `buildVersion`. See
  "Build-version pinning" below.

All persistence is one `XmlFile`/`XStream2`-serialized `.xml` file per unit under
`$JENKINS_HOME/config-template-sync/` (one per `ConfigSet` key) and
`$JENKINS_HOME/config-template-sync/bindings/` (one per project-env key) — the same native
mechanism Jenkins uses for job configs and global configuration.

## Pipeline steps

### `configTemplateValidate`

```groovy
configTemplateValidate(
    commonConfigSet: 'myapp-common',   // optional - omit/blank to skip the common layer entirely
    envConfigSet:    'myapp-dev',
    file:            'deploy/appsettings.Production.json'
)
```

Merges the active common+env versions (env wins on conflict), flattens the result + the merged
secrets manifest into the full set of "keys the store knows about," extracts every `#{...}#`
token from the target file, and:

- any token in the file with **no** matching store key → **fails the build**, naming every
  missing key;
- any store key with **no** matching token in the file → **non-fatal** `[WARN] Orphan store
  keys: ...` log line only.

Run this *before* `configTemplateSubstitute` in a deploy pipeline, right after the artifact is
fetched and before the file is touched.

### `configTemplateSubstitute`

```groovy
configTemplateSubstitute(
    commonConfigSet: 'myapp-common',
    envConfigSet:    'myapp-dev',
    file:            'deploy/appsettings.Production.json',
    buildVersion:    env.APP_VERSION   // optional - see "Build-version pinning" below
)
```

Merges the common+env layers the same way, flattens to `KEY=VALUE`, then substitutes `#{KEY}#`
tokens in the target file — first from the merged store, then from whatever the calling
Jenkinsfile has already exported into the environment (secrets pulled via its own
`withCredentials`/`withEnv` block using the credential IDs named in the secrets manifest, plus any
build-stamp values like `AppVersion`/`AppCommit`/`AppBuildDate`). Throws if any `#{...}#` token
remains unsubstituted afterward — same safety guard as the hand-written PowerShell stage this step
replaces.

#### Build-version pinning (`buildVersion` param)

If `buildVersion` is supplied **and** a binding already exists for that exact build version (i.e.
this exact build has been deployed with this plugin before), the step uses the **pinned**
common/env version numbers from that binding for the merge — instead of whatever is currently
"active" — so redeploying (e.g. rolling the server back to) an older build replays the config it
actually shipped with, not a newer config that may not even be valid for that older code.

If `buildVersion` is omitted, or no existing binding matches it yet, the step falls back to
current-active versions (unchanged default behavior) — and if a real substitution then succeeds
with a `buildVersion` set, the binding for that build version is created/updated to the version
numbers actually used, so the *next* redeploy of that same build will replay them.

## Example Jenkinsfile stage

```groovy
stage('Config template') {
    steps {
        configTemplateValidate(
            commonConfigSet: 'myapp-common',
            envConfigSet:    'myapp-dev',
            file:            'deploy/appsettings.Production.json'
        )
    }
}
stage('Substitute tokens') {
    steps {
        withCredentials([string(credentialsId: 'myapp-dev-db-password', variable: 'Db.Password')]) {
            configTemplateSubstitute(
                commonConfigSet: 'myapp-common',
                envConfigSet:    'myapp-dev',
                file:            'deploy/appsettings.Production.json',
                buildVersion:    env.APP_VERSION
            )
        }
    }
}
```

## Interim authoring path: Script Console (until the UI milestone lands)

There is no Jelly UI yet for creating/editing `ConfigSet` content. Until then, author versions via
**Manage Jenkins → Script Console**, using the plugin's own repository/domain API directly:

```groovy
import io.github.retrokharkov1.configtemplatesync.ConfigSetRepository

def repo = ConfigSetRepository.onMaster()

// Save a new "common" version and activate it immediately:
repo.save(
    'myapp-common',                                    // ConfigSet key
    '''{
      "AppSettings": { "TimeoutSeconds": "30" },
      "ConnectionStrings": { "LocalSqlServer": "__SECRET__" },
      "OpenTelemetry": { "Environment": "__SECRET__" }
    }''',                                               // contentJson (full nested JSON for common)
    null,                                                // secretsManifest (dotted path -> credential ID), or null to leave unchanged
    'initial seed',                                      // note
    'your-username',                                     // author
    true                                                 // activateOnSave
)

// Save a new "env" version (sparse RFC 7396 overlay - only keys this env adds/overrides):
repo.save(
    'myapp-dev',
    '''{ "AppSettings": { "FeatureX": "on" } }''',
    ['ConnectionStrings.LocalSqlServer': 'myapp-dev-db-secrets',
     'OpenTelemetry.Environment':        'myapp-dev-otel-secrets'],
    'seed dev overrides',
    'your-username',
    true
)

// Roll back to an older version later (flips the active flag in place, mints nothing new):
repo.activate('myapp-dev', 1, 'your-username')

// Inspect history:
repo.listVersions('myapp-dev').each { println it }
```

This requires the calling admin account to have Script Console access and the script to pass
Jenkins' Script Security approval on first run, per this instance's standard Script Console
governance.

## Deferred to later milestones

- **Jelly/Stapler UI** — list/edit/history/diff/activate/"Generate template" screens. Everything
  above is drivable from Script Console + Pipeline in the meantime.
- **Multi-instance distribution** — a private Jenkins Update Center (`update-center.json` +
  hosted `.hpi`) so any Jenkins master can install/upgrade this plugin through the normal
  Manage Plugins search-and-install flow instead of a manual `.hpi` upload per instance.
- **Multi-project rollout** — this repo ships the generic plugin only; wiring it into any specific
  project's Jenkinsfile/ConfigSets (seeding real `ConfigSet` content, updating pipeline stages) is
  done in that project's own repo/Jenkins instance, not here.

## Building

```
mvn clean verify
```

Produces `target/config-template-sync.hpi`. Install via **Manage Jenkins → Plugins → Advanced
settings → Deploy Plugin** (direct `.hpi` upload) on a single instance.

## License

MIT — see `LICENSE`.
