---
status: accepted
---

# Allow offline starts from a verified installation

When the update service is temporarily unreachable, V1 grants the game-start gate if the local modpack is the last completely applied and verified installation, while the UI reports that update freshness could not be confirmed. An incomplete transaction, integrity failure, or invalid release signature always blocks startup; this preserves availability during outages without treating an untrusted local state as safe.
