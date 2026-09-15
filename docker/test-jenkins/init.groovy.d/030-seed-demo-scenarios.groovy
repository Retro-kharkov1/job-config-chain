/*
 * Auto-seeds VISUAL demonstrations of business-logic scenarios already proven correct by
 * JUnit tests this session (ConfigTemplateSubstituteStepTest, ConfigTemplateValidateStepTest,
 * DriftCheckerTest) — so the owner can click through the browser and see the exact same
 * scenario shapes for themselves, instead of only trusting the test suite.
 *
 * Runs on every Jenkins boot, same as 020-seed-config-template-sync.groovy (see that file's
 * header for the init.groovy.d execution model). Purely additive: does NOT touch the
 * "test-app" Config Key or job seeded by 020-... at all. Every block below checks "does this
 * already exist?" before creating anything, so re-running on an already-seeded instance is a
 * no-op (idempotent against `docker compose up` on an existing named volume).
 *
 * Five areas, the first four each mirroring one already-passing test's exact scenario shape, the
 * fifth (added 2026-09-14) exercising the full useBase/configKey/version resolution matrix live,
 * since none of the first four areas exercise those parameters at all. Rewritten
 * 2026-09-14 for the Job-scoped pipeline-call resolution model (ConfigSetRole.ENV/
 * EnvConfigSetPage removed in full — see pipeline-steps.md's "Pipeline call resolution — the
 * final parameter model" and removal-candidates.md's "FULL ENTITY REMOVAL" section). Every
 * pipeline call below resolves against its Job's own JobConfigTemplateProperty content by
 * construction; there is no more `projectKey`/`environment` calling form:
 *   1) Multi-format Config Set examples — XML and YAML siblings of the existing JSON
 *      "test-app" common Config Set, independently viewable/editable in the UI.
 *   2) Rebuild-replay demo — mirrors ConfigTemplateSubstituteStepTest#fr54b_... : same job,
 *      3 real builds, build #3's redeployFromRun replays build #1's frozen output
 *      byte-identically despite the Job's own active version having moved on. Now expressed as
 *      two versions of the Job's OWN local config (JobConfigTemplateProperty) instead of two
 *      versions of a COMMON Config Set referenced via an env overlay.
 *   3) Negative/validation demo — mirrors ConfigTemplateValidateStepTest#missingKey_... and
 *      #orphanedKey_... : one build that fails naming a missing token, one that succeeds with
 *      a WARN naming an orphaned key. Now against each demo Job's own local config.
 *   4) Multi-base chain demo — mirrors
 *      ConfigTemplateSubstituteStepTest#multiEntryCrossProjectChainSubstitutesFromAllBases : a
 *      3-entry base chain, still across independent global COMMON Config Keys, now declared on
 *      the Job's own JobConfigTemplateVersion instead of an env Config Set Version.
 *
 *   5) Parameter-override matrix demo — exercises every row of the useBase/configKey/version
 *      resolution matrix (pipeline-steps.md's 7-row table) as 7 separate builds of one job, each
 *      build isolated to avoid Deployment Binding replay contaminating a later no-version call.
 *
 * projectKey/configKey/job names below are demo-scoped (rebuild-demo-app, validation-demo-*,
 * multichain-demo-app, shared-database, shared-logging, matrixdemo-base-db, matrixdemo-base-logging,
 * unrelated-shared-config) — never referencing any motivating/originating client project, per the
 * same NFR-4 constraint 020-... follows.
 */

import io.github.retrokharkov1.configtemplatesync.model.BaseConfigReference
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole
import io.github.retrokharkov1.configtemplatesync.model.ContentType
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository
import io.github.retrokharkov1.configtemplatesync.ui.JobConfigTemplateProperty
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def logger = { msg -> println("[config-template-sync-demo-seed] ${msg}") }

def jenkins = Jenkins.get()
def repository = new ConfigSetRepository()

// =========================================================================================
// AREA 1: Multi-format Config Set examples — XML and YAML siblings of "test-app"'s existing
// JSON common content shape (App.Name / App.Version). Common Config Sets only, no job —
// independently viewable/editable at /configTemplates/<key>/. The env-overlay sibling this
// used to also seed no longer has a replacement concept (ConfigSetRole.ENV removed in full).
// =========================================================================================
def seedMultiFormatCommon = { String projectKey, ContentType contentType, String commonV1, String commonV2 ->
    if (repository.findCommon(projectKey) != null) {
        logger("Common ConfigSet for projectKey '${projectKey}' already exists — skipping seed.")
        return
    }
    logger("Seeding ${contentType} common ConfigSet for projectKey '${projectKey}'...")

    def common = new ConfigSet(projectKey, ConfigSetRole.COMMON, null, "Test App (${contentType}) - Common", contentType)
    common.addVersion(commonV1, 'Initial common baseline', 'seed-script', System.currentTimeMillis())
    common.addVersion(commonV2, 'Add App.Version field', 'seed-script', System.currentTimeMillis())
    common.activate(2)
    repository.save(common)
    logger("Saved common ConfigSet '${common.getStorageKey()}', active=v${common.getActiveVersionNumber()}.")
}

seedMultiFormatCommon('test-app-xml', ContentType.XML,
        '<root><App><Name>test-app-xml</Name></App><Database><Host>db.internal.test</Host></Database></root>',
        '<root><App><Name>test-app-xml</Name><Version>1.0.0</Version></App>' +
                '<Database><Host>db.internal.test</Host></Database></root>')

seedMultiFormatCommon('test-app-yaml', ContentType.YAML,
        "App:\n  Name: test-app-yaml\nDatabase:\n  Host: db.internal.test\n",
        "App:\n  Name: test-app-yaml\n  Version: 1.0.0\nDatabase:\n  Host: db.internal.test\n")

// =========================================================================================
// AREA 2: Rebuild-replay demo — mirrors fr54b_sameJobRebuildViaRedeployFromRunReplaysByteIdentical...
// Same job, 3 real builds triggered synchronously during seeding so all 3 are already in the
// build history the first time the owner opens the job. "Configuration A/B" is now two
// versions of THIS JOB'S OWN local config (JobConfigTemplateProperty), zero base chain —
// mirrors what used to be two versions of a COMMON Config Set referenced via an env overlay.
// =========================================================================================
def REBUILD_JOB_NAME = 'config-template-sync-rebuild-demo'
def rebuildJob = jenkins.getItemByFullName(REBUILD_JOB_NAME)

if (rebuildJob != null) {
    logger("Job '${REBUILD_JOB_NAME}' already exists — skipping rebuild-replay demo seed.")
} else {
    logger("Seeding rebuild-replay demo (job-scoped own config)...")

    rebuildJob = jenkins.createProject(WorkflowJob.class, REBUILD_JOB_NAME)

    def rebuildProperty = new JobConfigTemplateProperty()
    def v1 = rebuildProperty.addVersion('{"a":"configuration-A-value"}', 'Configuration A (v1)', 'seed-script',
            System.currentTimeMillis(), Collections.emptyList(), 'JSON')
    rebuildProperty.activate(v1)
    rebuildJob.addProperty(rebuildProperty)
    rebuildJob.save()

    // Build #1: plain call, auto-pins to Configuration A (v1).
    rebuildJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'x=#{a}#'\n" +
                    "  configTemplateSubstitute(file: 'app.json')\n" +
                    "  echo \"BUILD1:\${readFile('app.json')}\"\n" +
                    "}", true))
    rebuildJob.save()
    def build1 = rebuildJob.scheduleBuild2(0).get()
    logger("Rebuild demo build #${build1.getNumber()} result=${build1.getResult()}")

    // Bump this Job's own config to Configuration B (v2), same as the test does mid-scenario.
    def reloadedProperty = rebuildJob.getProperty(JobConfigTemplateProperty.class)
    int v2 = reloadedProperty.addVersion('{"a":"configuration-B-value"}', 'Configuration B (v2)', 'seed-script',
            System.currentTimeMillis(), Collections.emptyList(), null)
    reloadedProperty.activate(v2)
    rebuildJob.save()

    // Build #2: same job, plain call again, no redeployFromRun -> live-resolves to Configuration B.
    rebuildJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'x=#{a}#'\n" +
                    "  configTemplateSubstitute(file: 'app.json')\n" +
                    "  echo \"BUILD2:\${readFile('app.json')}\"\n" +
                    "}", true))
    rebuildJob.save()
    def build2 = rebuildJob.scheduleBuild2(0).get()
    logger("Rebuild demo build #${build2.getNumber()} result=${build2.getResult()}")

    // Build #3: same job, explicit redeployFromRun: '1' -> must replay build #1's frozen
    // Configuration A output byte-identically, even though Configuration B is now active.
    rebuildJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'x=#{a}#'\n" +
                    "  configTemplateSubstitute(file: 'app.json', redeployFromRun: '1')\n" +
                    "  echo \"BUILD3:\${readFile('app.json')}\"\n" +
                    "}", true))
    rebuildJob.setDescription(
            "Demonstrates FR-54/UF-6 rebuild-replay (see " +
            "ConfigTemplateSubstituteStepTest#fr54b_sameJobRebuildViaRedeployFromRunReplaysByteIdenticalDespiteLaterActiveVersionBump), " +
            "now expressed as two versions of this Job's OWN local config (JobConfigTemplateProperty). " +
            "Build #1: plain call, auto-pins to Configuration A (v1, x=configuration-A-value). " +
            "Build #2: plain call again AFTER Configuration B (v2) was activated on this Job's own config " +
            "-> live-resolves to Configuration B (x=configuration-B-value). " +
            "Build #3: redeployFromRun: '1' -> replays build #1's ORIGINAL output byte-identically " +
            "(x=configuration-A-value), even though Configuration B is now the active version. " +
            "Click into each build's console output to see the BUILD1/BUILD2/BUILD3 echo lines.")
    rebuildJob.save()
    def build3 = rebuildJob.scheduleBuild2(0).get()
    logger("Rebuild demo build #${build3.getNumber()} result=${build3.getResult()}")

    logger("Rebuild-replay demo seeded: job '${REBUILD_JOB_NAME}' has ${rebuildJob.getBuilds().size()} builds.")
}

// =========================================================================================
// AREA 3: Negative/validation demo — mirrors missingKey_failsBuildNamingTheToken and
// orphanedKey_warnsButDoesNotFailBuild. One job, two builds triggered synchronously: build #1
// fails naming a missing token, build #2 succeeds with a WARN naming an orphaned key. Each
// build now resolves against ITS JOB'S own local config (own version, zero base chain).
// =========================================================================================
def VALIDATION_JOB_NAME = 'config-template-sync-validation-demo'
def validationJob = jenkins.getItemByFullName(VALIDATION_JOB_NAME)

if (validationJob != null) {
    logger("Job '${VALIDATION_JOB_NAME}' already exists — skipping validation demo seed.")
} else {
    logger("Seeding validation demo (job-scoped own config)...")

    validationJob = jenkins.createProject(WorkflowJob.class, VALIDATION_JOB_NAME)
    def validationProperty = new JobConfigTemplateProperty()
    int missingKeyVersion = validationProperty.addVersion('{"a":1}', 'Missing-key scenario seed', 'seed-script',
            System.currentTimeMillis(), Collections.emptyList(), 'JSON')
    validationProperty.activate(missingKeyVersion)
    validationJob.addProperty(validationProperty)
    validationJob.save()

    // Build #1: token '#{doesNotExist}#' has no matching config key -> FAILURE, message names it.
    validationJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'x=#{a}# y=#{doesNotExist}#'\n" +
                    "  configTemplateValidate(file: 'app.json')\n" +
                    "}", true))
    validationJob.save()
    def build1 = validationJob.scheduleBuild2(0).get()
    logger("Validation demo build #${build1.getNumber()} (missing-key) result=${build1.getResult()}")

    // Add a second version — config key 'unused' has no matching token -> SUCCESS with a WARN naming it.
    def reloadedValidationProperty = validationJob.getProperty(JobConfigTemplateProperty.class)
    int orphanedKeyVersion = reloadedValidationProperty.addVersion('{"a":1,"unused":2}',
            'Orphaned-key scenario seed', 'seed-script', System.currentTimeMillis(), Collections.emptyList(), null)
    reloadedValidationProperty.activate(orphanedKeyVersion)
    validationJob.save()

    validationJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'x=#{a}#'\n" +
                    "  configTemplateValidate(file: 'app.json')\n" +
                    "}", true))
    validationJob.setDescription(
            "Demonstrates FR-17/18/19 drift validation (see ConfigTemplateValidateStepTest" +
            "#missingKey_failsBuildNamingTheToken and #orphanedKey_warnsButDoesNotFailBuild), " +
            "now against this Job's own local config (JobConfigTemplateProperty). " +
            "Build #1 (this Job's own v${missingKeyVersion} active at the time): template references " +
            "token #{doesNotExist}# with no matching config key -> build FAILS, console names " +
            "'doesNotExist' in the missing-keys error. Build #2 (this Job's own v${orphanedKeyVersion}, " +
            "activated afterward): config key 'unused' has no matching token in the template -> build " +
            "SUCCEEDS, console prints a [WARN] line naming 'unused' as orphaned. Click into each build's " +
            "console output to see the exact drift messages.")
    validationJob.save()
    def build2 = validationJob.scheduleBuild2(0).get()
    logger("Validation demo build #${build2.getNumber()} (orphaned-key) result=${build2.getResult()}")

    logger("Validation demo seeded: job '${VALIDATION_JOB_NAME}' has ${validationJob.getBuilds().size()} builds.")
}

// =========================================================================================
// AREA 4: Multi-base chain demo — mirrors multiEntryCrossProjectChainSubstitutesFromAllBases.
// A 3-entry base chain across independent global COMMON Config Keys, still all folded into one
// substitution — now declared on the Job's own JobConfigTemplateVersion (rows 1/2 of the
// resolution matrix) instead of an env Config Set Version's baseChain.
// =========================================================================================
def CHAIN_PROJECT_KEY = 'multichain-demo-app'
def SHARED_DB_KEY = 'shared-database'
def SHARED_LOGGING_KEY = 'shared-logging'
def CHAIN_JOB_NAME = 'config-template-sync-multichain-demo'
def chainJob = jenkins.getItemByFullName(CHAIN_JOB_NAME)

if (chainJob != null) {
    logger("Job '${CHAIN_JOB_NAME}' already exists — skipping multi-base chain demo seed.")
} else {
    logger("Seeding multi-base chain demo (job-scoped baseChain='${CHAIN_PROJECT_KEY}')...")

    def own = repository.findCommon(CHAIN_PROJECT_KEY)
    if (own == null) {
        own = new ConfigSet(CHAIN_PROJECT_KEY, ConfigSetRole.COMMON, null, 'Multichain Demo - Own Common', ContentType.JSON)
        own.addVersion('{"App":"multichain-demo-app"}', 'seed', 'seed-script', System.currentTimeMillis())
        own.activate(1)
        repository.save(own)
    }

    def sharedDb = repository.findCommon(SHARED_DB_KEY)
    if (sharedDb == null) {
        sharedDb = new ConfigSet(SHARED_DB_KEY, ConfigSetRole.COMMON, null, 'Shared Database Common', ContentType.JSON)
        sharedDb.addVersion('{"Database":{"Host":"db.shared.test"}}', 'seed', 'seed-script', System.currentTimeMillis())
        sharedDb.activate(1)
        repository.save(sharedDb)
    }

    def sharedLogging = repository.findCommon(SHARED_LOGGING_KEY)
    if (sharedLogging == null) {
        sharedLogging = new ConfigSet(SHARED_LOGGING_KEY, ConfigSetRole.COMMON, null, 'Shared Logging Common', ContentType.JSON)
        sharedLogging.addVersion('{"Logging":{"Level":"INFO"}}', 'seed', 'seed-script', System.currentTimeMillis())
        sharedLogging.activate(1)
        repository.save(sharedLogging)
    }

    chainJob = jenkins.createProject(WorkflowJob.class, CHAIN_JOB_NAME)
    def chainProperty = new JobConfigTemplateProperty()
    def chainBaseChain = Arrays.asList(
            BaseConfigReference.active(CHAIN_PROJECT_KEY),
            BaseConfigReference.active(SHARED_DB_KEY),
            BaseConfigReference.active(SHARED_LOGGING_KEY))
    int chainVersion = chainProperty.addVersion('{}', 'Chain of 3 bases, no own overlay keys', 'seed-script',
            System.currentTimeMillis(), chainBaseChain, 'JSON')
    chainProperty.activate(chainVersion)
    chainJob.addProperty(chainProperty)
    chainJob.save()

    chainJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'App=#{App}# Database.Host=#{Database.Host}# Logging.Level=#{Logging.Level}#'\n" +
                    "  configTemplateSubstitute(file: 'app.json')\n" +
                    "  echo \"RESULT:\${readFile('app.json')}\"\n" +
                    "}", true))
    chainJob.setDescription(
            "Demonstrates FR-8/FR-51 multi-base chains (see ConfigTemplateSubstituteStepTest" +
            "#multiEntryCrossProjectChainSubstitutesFromAllBases), now declared on this Job's own " +
            "JobConfigTemplateVersion. This Job's ONE version declares a 3-entry base chain: " +
            "'${CHAIN_PROJECT_KEY}'@ACTIVE, '${SHARED_DB_KEY}'@ACTIVE, '${SHARED_LOGGING_KEY}'@ACTIVE — " +
            "three independent global COMMON Config Keys. The single build's console output shows all " +
            "three keys (App, Database.Host, Logging.Level) folded into one substituted file, proving the " +
            "chain resolves every base, not just this Job's own '${CHAIN_PROJECT_KEY}' common Config Set.")
    chainJob.save()
    def build = chainJob.scheduleBuild2(0).get()
    logger("Multi-base chain demo build #${build.getNumber()} result=${build.getResult()}")
}

// =========================================================================================
// AREA 5: Parameter-override matrix demo — exercises EVERY row of pipeline-steps.md's 7-row
// "Pipeline call resolution" matrix (useBase/configKey/version) live, in the browser, so the
// owner can click through actual build console output instead of only trusting the 340
// unit/integration tests. None of Areas 1-4 above exercise useBase/configKey/version at all.
//
// Job-scoped setup:
//   - matrix-demo Job's OWN config (JobConfigTemplateProperty) has 2 versions:
//       v1 (older): Own.Override='own-v1-override-value', baseChain=[matrixdemo-base-db@ACTIVE,
//                    matrixdemo-base-logging@ACTIVE]  (2 entries -- proves row 5 folds a
//                    version's own multi-entry chain cleanly, no fail-loud, regardless of length)
//       v2 (ACTIVE): Own.Override='own-v2-override-value', baseChain=[matrixdemo-base-db@ACTIVE]
//                    (1 entry -- deliberately a DIFFERENT chain shape than v1, so row 4 (active
//                    version's chain) and row 5 (v1's own chain) are visibly distinguishable by
//                    Logging.Level's presence/absence, not just by a different own-override value)
//   - Two global COMMON Config Sets referenced BY that chain: matrixdemo-base-db (2 versions, so
//     the chain has real content behind it) and matrixdemo-base-logging (1 version).
//   - A THIRD global COMMON Config Set, 'unrelated-shared-config', that is NEVER referenced in
//     the Job's own baseChain at all (2 versions) -- proves matrix rows 6/7's "direct global
//     lookup by configKey, NOT restricted to the calling Job's own baseChain" isolation rule.
//
// Each matrix row below is seeded as its OWN separate build (mirrors Area 2/3's proven pattern)
// rather than multiple stages inside one build. This is deliberate, not just stylistic: every
// configTemplateSubstitute call that OMITS 'version' (rows 1/4/6) resolves via
// ConfigTemplateSubstituteStep's "own-Run Deployment Binding" branch, which -- once ANY earlier
// no-version substitute call in the SAME build/Run has already written a binding -- replays that
// FROZEN chain for every later no-version call in that same build, ignoring that later call's own
// useBase/configKey entirely. Rows 1/4/6 packed into one shared build would therefore silently
// misrepresent rows 4/6 the moment they ran after row 1. Giving each row its own build/Run sidesteps
// this binding-replay behavior entirely (each build's first substitute call always sees "no prior
// binding for this Run" and resolves live) and matches this file's own established multi-build
// idiom.
// =========================================================================================
def MATRIX_JOB_NAME = 'config-template-sync-matrix-demo'
def MATRIX_DB_KEY = 'matrixdemo-base-db'
def MATRIX_LOGGING_KEY = 'matrixdemo-base-logging'
def UNRELATED_KEY = 'unrelated-shared-config'
def matrixJob = jenkins.getItemByFullName(MATRIX_JOB_NAME)

if (matrixJob != null) {
    logger("Job '${MATRIX_JOB_NAME}' already exists — skipping parameter-override matrix demo seed.")
} else {
    logger("Seeding parameter-override matrix demo (full useBase/configKey/version resolution matrix)...")

    def matrixDb = repository.findCommon(MATRIX_DB_KEY)
    if (matrixDb == null) {
        matrixDb = new ConfigSet(MATRIX_DB_KEY, ConfigSetRole.COMMON, null, 'Matrix Demo - Shared DB (chain member)', ContentType.JSON)
        matrixDb.addVersion('{"Database":{"Host":"matrixdemo-db-v1.test"}}', 'DB v1', 'seed-script', System.currentTimeMillis())
        matrixDb.addVersion('{"Database":{"Host":"matrixdemo-db-v2.test"}}', 'DB v2', 'seed-script', System.currentTimeMillis())
        matrixDb.activate(2)
        repository.save(matrixDb)
    }

    def matrixLogging = repository.findCommon(MATRIX_LOGGING_KEY)
    if (matrixLogging == null) {
        matrixLogging = new ConfigSet(MATRIX_LOGGING_KEY, ConfigSetRole.COMMON, null, 'Matrix Demo - Shared Logging (chain member)', ContentType.JSON)
        matrixLogging.addVersion('{"Logging":{"Level":"INFO"}}', 'Logging v1', 'seed-script', System.currentTimeMillis())
        matrixLogging.activate(1)
        repository.save(matrixLogging)
    }

    def unrelated = repository.findCommon(UNRELATED_KEY)
    if (unrelated == null) {
        // Deliberately NEVER added to the matrix-demo Job's own baseChain below -- proves rows
        // 6/7's direct-by-configKey global lookup is not restricted to the calling Job's own chain.
        unrelated = new ConfigSet(UNRELATED_KEY, ConfigSetRole.COMMON, null, 'Matrix Demo - Unrelated (NOT in any Job baseChain)', ContentType.JSON)
        unrelated.addVersion('{"Shared":{"Value":"unrelated-v1-value"}}', 'Unrelated v1', 'seed-script', System.currentTimeMillis())
        unrelated.addVersion('{"Shared":{"Value":"unrelated-v2-value"}}', 'Unrelated v2', 'seed-script', System.currentTimeMillis())
        unrelated.activate(2)
        repository.save(unrelated)
    }

    matrixJob = jenkins.createProject(WorkflowJob.class, MATRIX_JOB_NAME)
    def matrixProperty = new JobConfigTemplateProperty()
    def ownV1Chain = Arrays.asList(BaseConfigReference.active(MATRIX_DB_KEY), BaseConfigReference.active(MATRIX_LOGGING_KEY))
    int ownV1 = matrixProperty.addVersion('{"Own":{"Override":"own-v1-override-value"}}', 'Own config v1 (2-entry chain)',
            'seed-script', System.currentTimeMillis(), ownV1Chain, 'JSON')
    def ownV2Chain = Arrays.asList(BaseConfigReference.active(MATRIX_DB_KEY))
    int ownV2 = matrixProperty.addVersion('{"Own":{"Override":"own-v2-override-value"}}', 'Own config v2 (1-entry chain, ACTIVE)',
            'seed-script', System.currentTimeMillis(), ownV2Chain, null)
    matrixProperty.activate(ownV2)
    matrixJob.addProperty(matrixProperty)
    matrixJob.setDescription(
            "Demonstrates the FULL parameter-override resolution matrix (see pipeline-steps.md's " +
            "\"Pipeline call resolution — the final parameter model\", 7-row table). This Job's own " +
            "config: v1 (Own.Override=own-v1-override-value, 2-entry chain [${MATRIX_DB_KEY}@ACTIVE, " +
            "${MATRIX_LOGGING_KEY}@ACTIVE]), v2/ACTIVE (Own.Override=own-v2-override-value, 1-entry " +
            "chain [${MATRIX_DB_KEY}@ACTIVE]). '${UNRELATED_KEY}' is a THIRD global COMMON Config Set " +
            "never referenced by this Job's own chain at all — proves rows 6/7 reach it directly by " +
            "configKey regardless. Each build below is one matrix row (its own build avoids the " +
            "Deployment Binding replay behavior that would otherwise contaminate later no-version " +
            "calls sharing a build with an earlier one — see the seed script's own comment for why). " +
            "Row 1 (build #1, default): own ACTIVE v2 override + v2's own chain (Database.Host only). " +
            "Row 2 (build #2, version:1): own v1 override + v1's own chain (Database.Host + " +
            "Logging.Level) — differs from row 1 in BOTH override value and chain shape. " +
            "Row 4 (build #3, useBase:true): v2's own chain only, override ignored (Database.Host " +
            "only, own value absent). " +
            "Row 5 (build #4, useBase:true, version:1): v1's own chain only, override ignored " +
            "(Database.Host + Logging.Level — proves a >1-entry chain folds cleanly for a pinned " +
            "own-version, no fail-loud, and is visibly distinct from row 4 via Logging.Level). " +
            "Row 6 (build #5, useBase:true, configKey:'${UNRELATED_KEY}'): direct global lookup, " +
            "ACTIVE version (Shared.Value=unrelated-v2-value) — a config NOT in this Job's own chain. " +
            "Row 7 (build #6, useBase:true, configKey:'${UNRELATED_KEY}', version:1): same global " +
            "config, pinned to v1 (Shared.Value=unrelated-v1-value). " +
            "Row 3 / fail-loud (build #7): configKey supplied WITHOUT useBase:true — caught in a " +
            "try/catch, echoes the exact AbortException message so the build still succeeds.")
    matrixJob.save()

    // Row 1 (build #1): default call — own ACTIVE version (v2), full effective content
    // (own override + v2's own 1-entry chain).
    matrixJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'Own.Override=#{Own.Override}# Database.Host=#{Database.Host}#'\n" +
                    "  def cfg = [\n" +
                    "      file: 'app.json'\n" +
                    "  ]\n" +
                    "  configTemplateSubstitute(cfg)\n" +
                    "  echo \"ROW1-DEFAULT(own-ACTIVE-v2,full-effective):\${readFile('app.json')}\"\n" +
                    "}", true))
    matrixJob.save()
    def rowBuild1 = matrixJob.scheduleBuild2(0).get()
    logger("Matrix demo row 1 (default) build #${rowBuild1.getNumber()} result=${rowBuild1.getResult()}")

    // Row 2 (build #2): version:1 — own v1, full effective content (own v1 override + v1's own
    // 2-entry chain). Must visibly differ from row 1's output.
    matrixJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'Own.Override=#{Own.Override}# Database.Host=#{Database.Host}# Logging.Level=#{Logging.Level}#'\n" +
                    "  def cfg = [\n" +
                    "      file: 'app.json',\n" +
                    "      version: 1\n" +
                    "  ]\n" +
                    "  configTemplateSubstitute(cfg)\n" +
                    "  echo \"ROW2-VERSION-PIN(own-v1,full-effective):\${readFile('app.json')}\"\n" +
                    "}", true))
    matrixJob.save()
    def rowBuild2 = matrixJob.scheduleBuild2(0).get()
    logger("Matrix demo row 2 (version:1) build #${rowBuild2.getNumber()} result=${rowBuild2.getResult()}")

    // Row 4 (build #3): useBase:true — own ACTIVE version's (v2) folded base chain only, own
    // override ignored. Must visibly differ from row 1 (Own.Override absent).
    matrixJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'Database.Host=#{Database.Host}#'\n" +
                    "  def cfg = [\n" +
                    "      file: 'app.json',\n" +
                    "      useBase: true\n" +
                    "  ]\n" +
                    "  configTemplateSubstitute(cfg)\n" +
                    "  echo \"ROW4-USEBASE(own-ACTIVE-v2-chain-only,override-ignored):\${readFile('app.json')}\"\n" +
                    "}", true))
    matrixJob.save()
    def rowBuild3 = matrixJob.scheduleBuild2(0).get()
    logger("Matrix demo row 4 (useBase:true) build #${rowBuild3.getNumber()} result=${rowBuild3.getResult()}")

    // Row 5 (build #4): useBase:true, version:1 — own v1's RECORDED base chain (2 entries) folded
    // cleanly, override ignored. Proves "unambiguous regardless of chain length" — no fail-loud.
    matrixJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'Database.Host=#{Database.Host}# Logging.Level=#{Logging.Level}#'\n" +
                    "  def cfg = [\n" +
                    "      file: 'app.json',\n" +
                    "      useBase: true,\n" +
                    "      version: 1\n" +
                    "  ]\n" +
                    "  configTemplateSubstitute(cfg)\n" +
                    "  echo \"ROW5-USEBASE-VERSION-PIN(own-v1-chain-2entries,override-ignored):\${readFile('app.json')}\"\n" +
                    "}", true))
    matrixJob.save()
    def rowBuild4 = matrixJob.scheduleBuild2(0).get()
    logger("Matrix demo row 5 (useBase:true, version:1) build #${rowBuild4.getNumber()} result=${rowBuild4.getResult()}")

    // Row 6 (build #5): useBase:true, configKey:'unrelated-shared-config' — direct global lookup
    // by name, ACTIVE version, NOT restricted to this Job's own baseChain (isolation rule).
    matrixJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'Shared.Value=#{Shared.Value}#'\n" +
                    "  def cfg = [\n" +
                    "      file: 'app.json',\n" +
                    "      useBase: true,\n" +
                    "      configKey: '${UNRELATED_KEY}'\n" +
                    "  ]\n" +
                    "  configTemplateSubstitute(cfg)\n" +
                    "  echo \"ROW6-USEBASE-CONFIGKEY(direct-global-ACTIVE,not-in-own-chain):\${readFile('app.json')}\"\n" +
                    "}", true))
    matrixJob.save()
    def rowBuild5 = matrixJob.scheduleBuild2(0).get()
    logger("Matrix demo row 6 (useBase:true, configKey) build #${rowBuild5.getNumber()} result=${rowBuild5.getResult()}")

    // Row 7 (build #6): useBase:true, configKey:'unrelated-shared-config', version:1 — same global
    // config, pinned to v1. Must visibly differ from row 6's ACTIVE (v2) output.
    matrixJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'Shared.Value=#{Shared.Value}#'\n" +
                    "  def cfg = [\n" +
                    "      file: 'app.json',\n" +
                    "      useBase: true,\n" +
                    "      configKey: '${UNRELATED_KEY}',\n" +
                    "      version: 1\n" +
                    "  ]\n" +
                    "  configTemplateSubstitute(cfg)\n" +
                    "  echo \"ROW7-USEBASE-CONFIGKEY-VERSION-PIN(direct-global-pinned-v1):\${readFile('app.json')}\"\n" +
                    "}", true))
    matrixJob.save()
    def rowBuild6 = matrixJob.scheduleBuild2(0).get()
    logger("Matrix demo row 7 (useBase:true, configKey, version:1) build #${rowBuild6.getNumber()} result=${rowBuild6.getResult()}")

    // Row 3 / fail-loud (build #7): configKey supplied WITHOUT useBase:true — must throw,
    // caught so the build still succeeds and the exact error message is visible in the console.
    matrixJob.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  writeFile file: 'app.json', text: 'Shared.Value=#{Shared.Value}#'\n" +
                    "  def cfg = [\n" +
                    "      file: 'app.json',\n" +
                    "      configKey: '${UNRELATED_KEY}'\n" +
                    "      // useBase: true,   // deliberately omitted -- configKey without useBase must fail loud\n" +
                    "  ]\n" +
                    "  try {\n" +
                    "    configTemplateSubstitute(cfg)\n" +
                    "    echo 'ROW3-FAILLOUD: UNEXPECTED — call did not throw'\n" +
                    "  } catch (err) {\n" +
                    "    echo \"ROW3-FAILLOUD-CAUGHT(configKey-without-useBase):\${err.getMessage()}\"\n" +
                    "  }\n" +
                    "}", true))
    matrixJob.save()
    def rowBuild7 = matrixJob.scheduleBuild2(0).get()
    logger("Matrix demo row 3 (fail-loud) build #${rowBuild7.getNumber()} result=${rowBuild7.getResult()}")

    logger("Parameter-override matrix demo seeded: job '${MATRIX_JOB_NAME}' has ${matrixJob.getBuilds().size()} builds.")
}

logger("Demo scenario seed script finished.")
