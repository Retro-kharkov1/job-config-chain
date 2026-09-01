// Seeded by docker/test-jenkins/init.groovy.d/020-seed-config-template-sync.groovy as the
// "config-template-sync-e2e" job's pipeline definition. NOT auto-triggered on startup —
// click "Build Now" on the job to run it.
//
// Scripted (not declarative) pipeline on purpose: it only needs workflow-aggregator's
// bundled workflow-cps/workflow-job/workflow-durable-task-step, with no dependency on
// whether pipeline-model-definition happens to be pulled in transitively.
//
// Exercises both pipeline steps this plugin provides, against the seeded
// projectKey="test-app", environment="dev" Config Sets and the seeded
// "test-app-dev-db-password" credential:
//   1. configTemplateValidate — checks the sample target file's #{Dotted.Path}# tokens
//      against the effective (common+env merged) configuration.
//   2. configTemplateSubstitute — replaces those tokens for real. The Database.Password
//      token is resolved EXCLUSIVELY from the "test-app-dev-db-password" Jenkins credential
//      declared in the common ConfigSet's secrets manifest (FR-13/FR-21) — deliberately no
//      withCredentials/withEnv wiring here anymore, to prove the step resolves it itself
//      rather than depending on the calling Jenkinsfile to re-inject it under a matching
//      env-var name.
node {
    def targetFile = 'appsettings.json'

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

    stage('Validate') {
        configTemplateValidate(projectKey: 'test-app', environment: 'dev', file: targetFile)
    }

    stage('Substitute') {
        configTemplateSubstitute(projectKey: 'test-app', environment: 'dev', file: targetFile, buildVersion: '1.0.0')
    }

    stage('Show substituted file') {
        echo "---- appsettings.json (after substitution) ----"
        sh "cat ${targetFile}"
    }
}
