---
status: accepted
---

# Roll back by publishing a new immutable release

Published releases and signed manifests are never edited or deleted to perform a rollback. The Management Tool creates a new release at the end of the release line whose desired state reuses a selected historical release, preserving audit history and giving fresh, stale, and already-updated clients one unambiguous latest target.
