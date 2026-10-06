---
status: accepted
---

# Roll back by publishing a new immutable release

Published releases and signed manifests are never edited or deleted to perform a rollback. The Management Tool creates a new release at the end of the release line whose desired state reuses a selected historical release, preserving audit history and giving fresh, stale, and already-updated clients one unambiguous latest target.

Content rollback carries forward the current cumulative released paths and combines them with the historical target's declarations. Paths explicitly managed again by the target, or inside its forced mirror directories, leave that released set. Required capabilities are recomputed to include the preserved ownership semantics. This prevents a player skipping the original release-ownership version from losing a copy that already belongs to them.
