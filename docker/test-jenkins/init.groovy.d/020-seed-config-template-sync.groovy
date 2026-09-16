/*
 * Auto-seeds test data for the job-config-chain plugin's e2e job, so `docker compose
 * up` needs zero manual Script Console steps.
 *
 * Runs on every Jenkins boot (init.groovy.d scripts execute at the end of Jenkins
 * initialization, with full access to Jenkins core AND every installed plugin's classes —
 * see https://www.jenkins.io/doc/book/managing/groovy-hook-scripts/). This file itself
 * only gets copied into $JENKINS_HOME/init.groovy.d once (the official Jenkins image never
 * overwrites a file already present in the JENKINS_HOME volume), but once it's there it
 * re-runs every restart — so every block below checks "does this already exist?" before
 * creating anything, making `docker compose up` idempotent against an already-seeded
 * named volume. `docker compose down -v` is the documented way to wipe the volume and
 * start over from a clean seed.
 *
 * Seeds (projectKey="test-app" throughout, per NFR-4 — no references to any
 * motivating/originating client project anywhere in this fixture):
 *   - a common ConfigSet with 2 versions (v2 active) holding one plain key
 *     (App.Name / App.Version) and one manifest-declared secret key (Database.Password)
 *   - a StringCredentialsImpl matching the secret manifest entry, with an obviously-fake
 *     local test value
 *   - the "config-template-sync-e2e" Pipeline job, defined from the fixture Jenkinsfile
 *     baked into the image — NOT auto-triggered; the owner clicks "Build Now" themselves
 *   - the job's OWN Config Templates content (JobConfigTemplateProperty), referencing the
 *     common ConfigSet above via its baseChain — every pipeline call resolves against this
 *     Job-scoped content by construction (2026-09-14: ConfigSetRole.ENV/EnvConfigSetPage
 *     removed in full; there is no more env-Config-Set layer to seed)
 */

import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.util.Secret
import io.jenkins.plugins.jobconfigchain.model.ConfigSet
import io.jenkins.plugins.jobconfigchain.model.ConfigSetRole
import io.jenkins.plugins.jobconfigchain.model.ContentType
import io.jenkins.plugins.jobconfigchain.model.SecretPlaceholder
import io.jenkins.plugins.jobconfigchain.model.BaseConfigReference
import io.jenkins.plugins.jobconfigchain.persistence.ConfigSetRepository
import io.jenkins.plugins.jobconfigchain.ui.JobConfigTemplateProperty
import jenkins.model.Jenkins
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def logger = { msg -> println("[config-template-sync-seed] ${msg}") }

def PROJECT_KEY = 'test-app'
def SECRET_DOTTED_PATH = 'Database.Password'
def CREDENTIAL_ID = 'test-app-dev-db-password'
def CREDENTIAL_FAKE_VALUE = 'S3cr3tDbPass!'
def JOB_NAME = 'config-template-sync-e2e'
def FIXTURE_JENKINSFILE = new File('/opt/job-config-chain-e2e/e2e.Jenkinsfile')

def jenkins = Jenkins.get()
def repository = new ConfigSetRepository()

// ---------------------------------------------------------------------------------------
// 1) The common ConfigSet (2026-09-14: ConfigSetRole.ENV removed in full — there is no more
//    env-layer ConfigSet to seed here; the Job's own Config Templates content, seeded in
//    step 4 below, references this common ConfigSet via its own baseChain instead).
// ---------------------------------------------------------------------------------------
if (repository.findCommon(PROJECT_KEY) != null) {
    logger("Common ConfigSet for projectKey '${PROJECT_KEY}' already exists — skipping seed.")
} else {
    logger("Seeding common ConfigSet for projectKey '${PROJECT_KEY}'...")

    def common = new ConfigSet(PROJECT_KEY, ConfigSetRole.COMMON, null, 'Test App - Common', ContentType.JSON)
    // Declare the secret BEFORE adding any version — addVersion() enforces that every
    // manifest-declared path already holds the placeholder marker at the time it's called.
    common.putSecretManifestEntry(SECRET_DOTTED_PATH, CREDENTIAL_ID)

    def commonV1Json = """{
        "App": { "Name": "${PROJECT_KEY}" },
        "Database": { "Host": "db.internal.test", "Password": "${SecretPlaceholder.VALUE}" }
    }"""
    common.addVersion(commonV1Json, 'Initial common baseline', 'seed-script', System.currentTimeMillis())

    def commonV2Json = """{
        "App": { "Name": "${PROJECT_KEY}", "Version": "1.0.0" },
        "Database": { "Host": "db.internal.test", "Password": "${SecretPlaceholder.VALUE}" }
    }"""
    common.addVersion(commonV2Json, 'Add App.Version field', 'seed-script', System.currentTimeMillis())

    common.activate(2)
    repository.save(common)
    logger("Saved common ConfigSet '${common.getStorageKey()}' with ${common.getVersions().size()} versions, active=v${common.getActiveVersionNumber()}.")
}

// ---------------------------------------------------------------------------------------
// 2) Credential matching the secret manifest entry above.
// ---------------------------------------------------------------------------------------
def credentialsStore = jenkins.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()
def globalDomain = Domain.global()
def existingCredential = credentialsStore.getCredentials(globalDomain).find { it.id == CREDENTIAL_ID }

if (existingCredential != null) {
    logger("Credential '${CREDENTIAL_ID}' already exists — skipping.")
} else {
    def credential = new StringCredentialsImpl(
            CredentialsScope.GLOBAL,
            CREDENTIAL_ID,
            "e2e seed: fake DB password for ${PROJECT_KEY} (local test instance only, not a real secret)",
            Secret.fromString(CREDENTIAL_FAKE_VALUE))
    credentialsStore.addCredentials(globalDomain, credential)
    logger("Created credential '${CREDENTIAL_ID}'.")
}

// ---------------------------------------------------------------------------------------
// 3) The e2e Pipeline job itself — created, never auto-triggered.
// ---------------------------------------------------------------------------------------
def job = jenkins.getItemByFullName(JOB_NAME)
if (job != null) {
    logger("Job '${JOB_NAME}' already exists — skipping creation.")
} else if (!FIXTURE_JENKINSFILE.exists()) {
    logger("[ERROR] Fixture Jenkinsfile not found at ${FIXTURE_JENKINSFILE} — image build is broken, job NOT created.")
} else {
    job = jenkins.createProject(WorkflowJob.class, JOB_NAME)
    job.setDefinition(new CpsFlowDefinition(FIXTURE_JENKINSFILE.text, true))
    job.setDescription(
            "e2e smoke test for the job-config-chain plugin: runs configTemplateValidate then " +
            "configTemplateSubstitute against this Job's own Config Templates (useBase: true, " +
            "configKey='${PROJECT_KEY}'). Not auto-triggered on startup — click Build Now.")
    job.save()
    logger("Created job '${JOB_NAME}' (not triggered).")
}

// ---------------------------------------------------------------------------------------
// 4) The job's OWN Config Templates content (JobConfigTemplateProperty) — replaces the old
//    association-to-a-separate-ConfigSet property wholesale (tech-lead design contract,
//    2026-09-09). A one-version, one-row-base-chain seed (referencing the common ConfigSet
//    seeded in step 1, ACTIVE) rather than an empty-chain default: this is deliberately a real,
//    demonstrable exercise of the new job-scoped model — an empty-chain seed would exercise
//    nothing e2e/manual-testing couldn't already see from an on-the-fly Save — so it earns its
//    place in this fixture (owner-facing rationale for keeping seeding at all, per the design
//    contract's "your call" note).
//
//    Deliberately OUTSIDE the create-or-skip block above: an already-existing job (a volume
//    seeded by an older image) must still pick this content up on the next start, instead of the
//    seed skipping the whole block and leaving the job without it.
// ---------------------------------------------------------------------------------------
if (job != null) {
    def existing = job.getProperty(JobConfigTemplateProperty.class)
    if (existing != null && !existing.getVersions().isEmpty()) {
        logger("Job '${JOB_NAME}' already has Config Templates content — skipping.")
    } else {
        def property = existing != null ? existing : new JobConfigTemplateProperty()
        def jobBaseChain = [BaseConfigReference.active(PROJECT_KEY)]
        def jobV1Json = """{ "Job": { "Note": "seeded via JobConfigTemplateProperty" } }"""
        def v1 = property.addVersion(jobV1Json, 'Initial job-scoped override', 'seed-script',
                System.currentTimeMillis(), jobBaseChain, 'JSON')
        property.activate(v1)
        if (existing == null) {
            job.addProperty(property)
        }
        job.save()
        logger("Seeded job '${JOB_NAME}' with a JobConfigTemplateProperty version referencing " +
                "'${PROJECT_KEY}' (ACTIVE), active=v${property.getActiveVersionNumber()}.")
    }
}

logger("Seed script finished.")
