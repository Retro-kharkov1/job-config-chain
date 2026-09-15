// Seeded by docker/test-jenkins/init.groovy.d/020-seed-config-template-sync.groovy as the
// "config-template-sync-e2e" job's pipeline definition. NOT auto-triggered on startup —
// click "Build Now" on the job to run it.
//
// Scripted (not declarative) pipeline on purpose: it only needs workflow-aggregator's
// bundled workflow-cps/workflow-job/workflow-durable-task-step, with no dependency on
// whether pipeline-model-definition happens to be pulled in transitively.
//
// Exercises both pipeline steps this plugin provides, against this job's own seeded
// JobConfigTemplateProperty content (which itself references the seeded "test-app" common
// ConfigSet via its baseChain, ACTIVE) and the seeded "test-app-dev-db-password" credential.
// Since 2026-09-14 every call resolves against the CALLING JOB's own attached local config by
// construction — there is no `projectKey`/`environment` parameter any more. Per
// pipeline-steps.md's "Pipeline call resolution — the final parameter model", a single
// `def cfg = [file: targetFile]` Map is built once and passed as the sole argument to both
// configTemplateValidate(cfg) and configTemplateSubstitute(cfg), instead of repeating the
// same parameters across both calls:
//   1. configTemplateValidate — checks the sample target file's #{Dotted.Path}# tokens
//      against the effective (Job's own base chain + own override, merged) configuration.
//   2. configTemplateSubstitute (no parameters beyond file) — replaces those tokens for real.
//      Per FR-96/FR-97 (automatic build-identity pinning), this call automatically derives
//      this Run's own identity and unconditionally creates/updates a Deployment Binding for
//      it — no `buildVersion` parameter is needed or accepted any more (it was removed
//      entirely, not merely deprecated). The Database.Password token is resolved EXCLUSIVELY
//      from the "test-app-dev-db-password" Jenkins credential declared in the common
//      ConfigSet's secrets manifest (FR-13/FR-21).
//   3. On every build after the first, a second demonstration stage calls
//      configTemplateSubstitute again with `redeployFromRun: '1'` (FR-100–FR-103) — this
//      replays build #1's frozen config chain byte-for-byte, even if the common ConfigSet's
//      active version has changed since (the core FR-54/UF-6 rollback guarantee), and its
//      own successful run additionally forward-chains a fresh binding keyed to the CURRENT
//      build (FR-102) so a later build could, in turn, redeploy from THIS one.
node {
    def targetFile = 'appsettings.json'
    def redeployFile = 'appsettings.redeploy.json'

    stage('Prepare sample target file') {
        writeFile file: targetFile, text: '''{
  "App": {
    "Name": "#{App.Name}#"
  },
  "Database": {
    "Host": "#{Database.Host}#",
    "Password": "#{Database.Password}#"
  }
}
'''
        echo "---- appsettings.json (before substitution) ----"
        sh "cat ${targetFile}"
    }

    // Build the shared cfg Map once, per the documented calling pattern (pipeline-steps.md's
    // "Pipeline call resolution — the final parameter model"), and pass it to both steps
    // instead of repeating the same parameters twice.
    def cfg = [file: targetFile]

    stage('Validate') {
        configTemplateValidate(cfg)
    }

    stage('Substitute (default-on binding, no parameters)') {
        // FR-96/FR-97: this alone is enough to both substitute AND record a Deployment
        // Binding for this exact build — no buildVersion/redeployFromRun/version needed for
        // the ordinary deploy case. Resolves against this Job's own Config Templates content
        // by construction (matrix row 1/2).
        configTemplateSubstitute(cfg)
    }

    stage('Show substituted file') {
        echo "---- appsettings.json (after substitution, build #${currentBuild.number}) ----"
        sh "cat ${targetFile}"
    }

    if (currentBuild.number > 1) {
        stage('Redeploy demonstration: replay build #1 via redeployFromRun') {
            writeFile file: redeployFile, text: '''{
  "App": {
    "Name": "#{App.Name}#"
  },
  "Database": {
    "Host": "#{Database.Host}#",
    "Password": "#{Database.Password}#"
  }
}
'''
            // FR-100–FR-103: replays build #1's frozen base chain, byte-identical, even if
            // the common ConfigSet's active version has since changed (UF-6/FR-54).
            configTemplateSubstitute(file: redeployFile, redeployFromRun: '1')
            echo "---- appsettings.redeploy.json (redeployFromRun: '1', from build #${currentBuild.number}) ----"
            echo "Compare this against build #1's own 'Show substituted file' console output — must be byte-identical."
            sh "cat ${redeployFile}"
        }
    }
}
