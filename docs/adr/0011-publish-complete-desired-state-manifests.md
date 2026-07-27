---
status: accepted
---

# Publish complete desired-state manifests

Every release is an immutable manifest of the complete managed target state rather than an operation chain from the previous release. Artifacts are identified and reused by content hash, so clients download only missing or changed content while fresh, stale, and damaged installations all converge through the same process without retaining every historical patch path.
