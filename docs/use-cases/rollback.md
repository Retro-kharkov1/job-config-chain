# Rollback: reverting config and replaying old builds

[Use cases](README.md) · [Guide home](../README.md) · [Pipeline reference](../pipeline/README.md)

## UF-5 — Roll back a bad config change

When a just-activated config version turns out to be broken, open the Config Set's version
history and click "Activate" on a prior version.

*Example:* on a Common Config Set's edit page, select version 3 in the history table → Activate.
The active pointer flips from version 4 back to version 3; no content is duplicated.

*Outcome:* the system confirms which version is now active and shows a diff versus what was active
a moment ago. Activation never triggers a live deploy by itself — the operator must separately
trigger a new deploy for the rollback to reach the running server.

## UF-6 — Roll back a server to an older build with its matching old config

When you redeploy (or reactivate) an older build/version identifier through the normal deploy
pipeline, pass `redeployFromRun` so the matching old config comes back automatically.

*Example:*
```groovy
configChainSubstitute(file: 'app.json', redeployFromRun: 42)
```
*Outcome:* if a Deployment Binding exists for build #42, substitution uses that binding's pinned
versions — not whatever is currently "active." If no binding exists, the system falls back to
currently-active and says so explicitly in the log.

