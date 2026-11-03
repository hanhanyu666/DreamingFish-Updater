---
status: accepted
---

# Approve official player programs per project

The official publisher signs a player catalogue with a dedicated Ed25519 identity pinned in management resources. Management verifies the catalogue, archive and complete program file set before preparing a project-signed candidate. Preparation does not change the approved program. Owners approve exact platform, version and project-manifest hash tuples; later official versions require another approval. The workspace presents preparation and approval as one "publish to players" action, the same project-signed publication as a manual upload. An opt-in automatic publisher on the running management service performs that action for newer catalogue versions; it never resumes a paused project and does not upload to external hosts.

A project-signed policy is the authority for enablement and approved targets. A monotonic revision detects stale writes and client replay. Existing latest pointers are read as revision zero for migration. Current deployments retain their authorized program when paused; neither pause nor activation of a known-good fallback authorizes arbitrary downgrade.

Dynamic endpoints hide legacy latest routes while paused. Static exports include a narrowly scoped retirement plan; built-in uploads delete those routes and verify 404 before reporting completion. Delivery records describe only the last built-in upload, not every external host. Clients check policy again before activation. Offline state, legacy in-flight downloads and external caches prevent immediate universal revocation.

Program content, project binding and server presentation remain independent. The Bootstrap Agent remains fixed as described in ADR 0022. Actual Agent identity is read from its installed build metadata; unknown identity is handled conservatively.
