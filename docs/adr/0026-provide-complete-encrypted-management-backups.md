---
status: accepted
---

# Provide complete encrypted Management Tool backups

V1 provides CLI backup and restore operations whose encrypted archive contains SQLite metadata, configuration, project private keys, signed manifests, and every content object. Full archives may be large, but restoring one on a fresh host must recover both publishing authority and all previously served releases without depending on another surviving copy.
