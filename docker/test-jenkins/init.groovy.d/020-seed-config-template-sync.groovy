/*
 * Auto-seeds test data for the config-template-sync plugin's e2e job, so `docker compose
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
 * Seeds (projectKey="test-app", environment="dev" throughout, per NFR-4 — no
 * IceMobile/Caffenio/apilealtad/andatti references anywhere in this fixture):
 *   - a common ConfigSet with 2 versions (v2 active) holding one plain key
 *     (App.Name / App.Version) and one manifest-declared secret key (Database.Password)
 *   - an env ConfigSet (dev) with 1 version (active) overlaying Database.Host
 *   - a StringCredentialsImpl matching the secret manifest entry, with an obviously-fake
 *     local test value
 *   - the "config-template-sync-e2e" Pipeline job, defined from the fixture Jenkinsfile
 *     baked into the image — NOT auto-triggered; the owner clicks "Build Now" themselves
 */

import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.util.Secret
import io.github.retrokharkov1.configtemplatesync.model.ConfigSet
import io.github.retrokharkov1.configtemplatesync.model.ConfigSetRole
import io.github.retrokharkov1.configtemplatesync.model.ContentType
import io.github.retrokharkov1.configtemplatesync.model.SecretPlaceholder
import io.github.retrokharkov1.configtemplatesync.persistence.ConfigSetRepository
import jenkins.model.Jenkins
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def logger = { msg -> println("[config-template-sync-seed] ${msg}") }

def PROJECT_KEY = 'test-app'
def ENVIRONMENT = 'dev'
def SECRET_DOTTED_PATH = 'Database.Password'
def CREDENTIAL_ID = 'test-app-dev-db-password'
def CREDENTIAL_FAKE_VALUE = 'S3cr3tDbPass!'
def JOB_NAME = 'config-template-sync-e2e'
def FIXTURE_JENKINSFILE = new File('/opt/config-template-sync-e2e/e2e.Jenkinsfile')

def jenkins = Jenkins.get()
def repository = new ConfigSetRepository()

// ---------------------------------------------------------------------------------------
// 1) Common + env ConfigSets (skip entirely if the common one already exists — both are
//    always seeded together, so checking one is enough to detect "already seeded").
// ---------------------------------------------------------------------------------------
if (repository.findCommon(PROJECT_KEY) != null) {
    logger("ConfigSets for projectKey '${PROJECT_KEY}' already exist — skipping seed.")
} else {
    logger("Seeding common + env ConfigSets for projectKey '${PROJECT_KEY}'...")

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

    def env = new ConfigSet(PROJECT_KEY, ConfigSetRole.ENV, ENVIRONMENT, "Test App - ${ENVIRONMENT}", ContentType.JSON)
    // Sparse RFC 7396 overlay: only overrides Database.Host for this environment.
    def envV1Json = """{
        "Database": { "Host": "db.${ENVIRONMENT}.internal.test" }
    }"""
    env.addVersion(envV1Json, "Initial ${ENVIRONMENT} overlay", 'seed-script', System.currentTimeMillis())
    env.activate(1)
    repository.save(env)
    logger("Saved env ConfigSet '${env.getStorageKey()}' with ${env.getVersions().size()} version(s), active=v${env.getActiveVersionNumber()}.")
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
            "e2e seed: fake DB password for ${PROJECT_KEY}/${ENVIRONMENT} (local test instance only, not a real secret)",
            Secret.fromString(CREDENTIAL_FAKE_VALUE))
    credentialsStore.addCredentials(globalDomain, credential)
    logger("Created credential '${CREDENTIAL_ID}'.")
}

// ---------------------------------------------------------------------------------------
// 3) The e2e Pipeline job itself — created, never auto-triggered.
// ---------------------------------------------------------------------------------------
if (jenkins.getItemByFullName(JOB_NAME) != null) {
    logger("Job '${JOB_NAME}' already exists — skipping.")
} else if (!FIXTURE_JENKINSFILE.exists()) {
    logger("[ERROR] Fixture Jenkinsfile not found at ${FIXTURE_JENKINSFILE} — image build is broken, job NOT created.")
} else {
    def job = jenkins.createProject(WorkflowJob.class, JOB_NAME)
    job.setDefinition(new CpsFlowDefinition(FIXTURE_JENKINSFILE.text, true))
    job.setDescription(
            "e2e smoke test for the config-template-sync plugin: runs configTemplateValidate then " +
            "configTemplateSubstitute against projectKey='${PROJECT_KEY}', environment='${ENVIRONMENT}'. " +
            "Not auto-triggered on startup — click Build Now.")
    job.save()
    logger("Created job '${JOB_NAME}' (not triggered).")
}

logger("Seed script finished.")
