---
status: accepted
---

# Version release manifests and fail closed on incompatibility

Release manifests carry an explicit schema version and may require a minimum Player Updater version. The Player Updater updates itself before interpreting a release and refuses unknown required semantics instead of silently ignoring them, while optional additions remain backward compatible.
