# Disposable e2e-test Jenkins for config-template-sync

A throwaway, single-command Jenkins instance with this repo's plugin built from source,
installed, and pre-seeded with test data — for manually poking at the plugin in a real
Jenkins UI, or re-running the seeded e2e job after a code change.

**Not a hardened deployment.** The setup wizard is disabled and no security realm is
configured, so anyone who can reach the port has full admin access with no login. Only
ever run this on localhost for local testing.

## Run it

From the repo root:

```
docker compose up --build
```

(`--build` rebuilds the plugin's `.hpi` from your current checkout; plain `docker compose
up` reuses whatever was last built.)

Open **http://localhost:28080** — no login screen, you land straight on the dashboard.

To fully reset back to a clean seeded state (wipe everything the seed script created plus
anything you clicked/typed on top of it):

```
docker compose down -v
```

## What's pre-seeded (via `init.groovy.d/020-seed-config-template-sync.groovy`)

- **Config Sets** for `projectKey = "test-app"`:
  - a **common** Config Set with 2 versions (v2 is active) — `App.Name`, `App.Version`
    (added in v2), and a manifest-declared secret at `Database.Password` (stored as the
    `__SECRET__` placeholder, never a real value)
  - an **env** Config Set for `environment = "dev"` with 1 version (active), overlaying
    `Database.Host`
- **Credential** `test-app-dev-db-password` (Secret text / `StringCredentialsImpl`,
  global scope) — value `S3cr3tDbPass!`. This is a fake, throwaway value for this local
  test instance only.
- **Pipeline job** `config-template-sync-e2e` — runs `configTemplateValidate` then
  `configTemplateSubstitute` against a sample `appsettings.json`-shaped file with matching
  `#{Path}#` tokens. `configTemplateSubstitute` resolves `Database.Password` exclusively
  from the `test-app-dev-db-password` credential above (FR-13/FR-21) — the Jenkinsfile does
  **not** manually inject it via `withCredentials`/`withEnv`, proving the step resolves the
  manifest-declared secret itself. **Not auto-triggered** — click **Build Now** on the job
  yourself to watch it run.

Re-running `docker compose up` against the same (already-seeded) volume does not error or
duplicate anything — the seed script checks for existing data before creating it.

## Where things live

| What | Path |
|---|---|
| Compose file | `docker-compose.yml` (repo root) |
| Multi-stage Dockerfile (builds the `.hpi`, then bakes it into a Jenkins image) | `docker/test-jenkins/Dockerfile` |
| Base plugin list | `docker/test-jenkins/plugins.txt` |
| Seed script | `docker/test-jenkins/init.groovy.d/020-seed-config-template-sync.groovy` |
| Seeded job's Jenkinsfile | `docker/test-jenkins/fixtures/e2e.Jenkinsfile` |
| Jenkins state (named volume, survives `docker compose restart`) | `jenkins_home` volume |
