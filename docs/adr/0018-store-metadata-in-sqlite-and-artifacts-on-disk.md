---
status: accepted
---

# Store metadata in SQLite and artifacts on disk

The Management Tool stores projects, file policies, release metadata, and operational state in embedded SQLite, requiring no external database service and allowing transactional CLI/WebUI access. Artifact bytes remain as immutable content-addressed files on disk rather than database blobs, enabling hash-based reuse while keeping database size and resource usage low.
