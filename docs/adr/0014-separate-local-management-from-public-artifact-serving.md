---
status: accepted
---

# Separate local management from public artifact serving

V1 exposes public manifests and artifacts on a dedicated HTTP listener while binding management UI and control APIs to localhost by default. Management may be reached through a trusted local network or secure tunnel, but direct public administration over unencrypted HTTP is unsupported; separate listeners and routes prevent an artifact-server configuration mistake from exposing publishing, deletion, or signing-key operations.
