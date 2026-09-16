# Requesting official Jenkins hosting

> **TODO (owner action):** the GitHub repository itself still needs a manual rename to
> `job-config-chain` (Settings → Repository name). Once done, update every
> `github.com/Retro-kharkov1/config-template-sync` URL in this file to the new repository name.

This plugin is not yet hosted in the `jenkinsci` GitHub organization or distributed via the
official Jenkins Update Center — it currently lives at `Retro-kharkov1/config-template-sync` and
is installed by manual `.hpi` upload (or a self-hosted private Update Center; see the README's
"Install / build" section).

Getting onto the official Update Center means going through Jenkins Infrastructure's **hosting
request** process. That process requires the repo owner's own GitHub and Jenkins identity, so it
cannot be automated or done on the owner's behalf. This document is the step-by-step guide for
the owner to complete it.

Sources verified current as of 2026-09 (fetched directly, not from training-data memory):

- <https://www.jenkins.io/doc/developer/publishing/requesting-hosting/> — the request process
- <https://www.jenkins.io/doc/developer/publishing/preparation/> — prerequisites
- <https://www.jenkins.io/doc/developer/publishing/style-guides/> — groupId convention
- <https://github.com/jenkins-infra/repository-permissions-updater/issues/new?assignees=&labels=hosting-request&template=1-hosting-request.yml>
  — the actual issue template used for submission

## 1. Prerequisites checklist

### Already satisfied (per the 2026-09 repo audit)

- [x] Public GitHub repository with the plugin source (`Retro-kharkov1/config-template-sync`).
- [x] License declared in both places Jenkins requires: `pom.xml` (`<license>`) and a `LICENSE`
      file at the repo root — this repo uses MIT, an OSI-approved license, which is acceptable.
- [x] User documentation exists (`README.md`, with a full use-case catalog and screenshots).
- [x] The plugin doesn't duplicate an existing hosted plugin's purpose (config-token
      validate/substitute with base-chain composition and build-identity pinning is a distinct
      niche — worth a final check against <https://plugins.jenkins.io/> immediately before filing,
      since the Update Center's catalog changes over time).
- [x] **`groupId` decision — resolved 2026-09-15.** Jenkins's current style guide states: *"All new
      hosting requests are instructed to use `io.jenkins.plugins` as group ID."* This repo's
      `pom.xml` now uses `io.jenkins.plugins` (artifactId `job-config-chain`, base Java package
      `io.jenkins.plugins.jobconfigchain`) — the one-way-door groupId choice is made, before any
      hosted release has gone out.

### Still open — owner decision/action required before filing

1. **Parent POM version.** `pom.xml` still pins `org.jenkins-ci.plugins:plugin` at `4.88`; current
   parent POMs use a newer `6.xxxx.vHASH`-style version. The hosting docs referenced above do not
   themselves gate submission on a specific parent POM version, but bumping it is good practice
   before requesting hosting since the Hosting team's review and the post-hosting CI build
   (`ci.jenkins.io`, see step 4 below) are far more likely to just work on a current parent. This
   needs an actual build/compatibility pass — not a blind version bump.
2. **No `Jenkinsfile` yet.** Not required to file the hosting request itself, but required before
   the post-hosting CI step (`ci.jenkins.io` builds only start once the repo has been transferred
   into the `jenkinsci` org — see step 4). Can be prepared in advance or added right after the
   transfer.
3. **GitHub two-factor authentication (2FA).** The hosting-request docs fetched above do not
   explicitly restate a 2FA requirement, but GitHub has required 2FA for anyone contributing code
   on github.com since March 2023, and the `jenkinsci` org (into which this repo will be forked)
   enforces org-wide 2FA for its members. Confirm 2FA is enabled on the GitHub account that will
   be invited to `jenkinsci` before filing — being un-enrolled would block accepting that
   invitation later.
4. **Jenkins community account.** A Jenkins account (via <https://www.jenkins.io/sso/>) is
   required to receive release permission — it grants access to Artifactory (the Maven repository
   used for releases). Create this ahead of time if not already done; the "Jenkins project users
   to have release permission" field in the request template (step 3 below) needs Jenkins
   usernames, not GitHub handles.

## 2. Filing the request (the exact current mechanism)

The current submission mechanism is a **GitHub issue**, filed against
**`jenkins-infra/repository-permissions-updater`**, using a specific issue-form template — not a
wiki edit or a mailing-list post (older docs describing those are stale).

Direct link to open a pre-filled issue with the correct template already selected:

<https://github.com/jenkins-infra/repository-permissions-updater/issues/new?assignees=&labels=hosting-request&template=1-hosting-request.yml>

The docs explicitly say: *"Make sure to fill out all fields as described."* The template
(`1-hosting-request.yml`) asks for:

| Field | What it needs |
|---|---|
| **Repository URL** | The current repo URL to host — `https://github.com/Retro-kharkov1/config-template-sync`. |
| **New Repository Name** | The name it will have inside `jenkinsci`. Plugin repos must end in `-plugin` and be lowercase, so this should be `job-config-chain-plugin` (confirm the exact name against the current naming convention page before submitting — it may have evolved). |
| **Description** | What the plugin does and how it differs from anything similar already hosted — the README's intro paragraph and use-case catalog are good source material to summarize from. |
| **GitHub users to have commit permission** | GitHub handles (`@user1, @user2, …`) to grant push access on the new `jenkinsci`-hosted repo. |
| **Jenkins project users to have release permission** | Jenkins (not GitHub) usernames of people who will cut releases — they must already have signed into Jira and Artifactory with that identity at least once. Do not use `@`-mentions here. |
| **Automated release via GitHub Actions** | Yes/No — whether releases will be cut via GitHub Actions (CD) vs. manual `mvn release`. Worth deciding in advance; see "Releasing" docs linked from the hosting page for what each choice implies. |

Only the repository owner can file this issue meaningfully, since it asks for identities
(GitHub commit access, Jenkins release access) that only the owner can authoritatively grant —
this is the step that cannot be delegated to an agent or automated.

## 3. What happens after submission

Per the current docs:

- **Review timeline:** *"A member of the Hosting team will review your request within a few
  days."* No harder SLA is published.
- **Repository transfer:** Once approved, the existing repository is **forked into the
  `jenkinsci` GitHub organization**, and the owner is invited to join `jenkinsci` as a
  collaborator on the new fork. This is the actual hosting event — the plugin now lives at
  `github.com/jenkinsci/job-config-chain-plugin` (or whatever name was requested), not at
  `Retro-kharkov1/config-template-sync`.
- **Old repo cleanup:** the owner is asked to delete the original `Retro-kharkov1` repo (or at
  minimum stop treating it as canonical) so GitHub's fork network correctly shows the `jenkinsci`
  copy as the primary source rather than a fork-of-a-fork.
- **CI enablement:** `ci.jenkins.io` builds are wired up next, driven by a `Jenkinsfile` in the
  repo (see the still-open item #2 above) — this is the point at which that file becomes
  necessary, not before.
- **Release/Update-Center listing:** upload permissions to Artifactory get granted per the
  `repository-permissions-updater` repo's own README instructions (a follow-up PR against that
  repo, distinct from the hosting-request issue), after which releases cut under the granted
  Jenkins identities become visible on the official Update Center. Categorization in the plugin
  site and optional Jira-issue autolinking are configured at this stage too.

## 4. Owner-only step — cannot be automated

To be explicit: everything in **section 2** (opening and filling out the hosting-request issue)
requires the repo owner's own GitHub account (to open the issue and later accept the `jenkinsci`
org invitation) and Jenkins community account (to be named for release permission). No agent or
automation in this repo can file that request on the owner's behalf. The still-open items in
section 1 are things an implementer *could* mechanically do (bump the parent POM, add a
`Jenkinsfile`) — the one-way-door `groupId` decision itself is already resolved (see the
"Already satisfied" checklist above).
