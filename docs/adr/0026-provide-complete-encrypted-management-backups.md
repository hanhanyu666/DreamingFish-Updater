---
status: accepted
---

# Provide complete encrypted publishing-data backups

V1 provides CLI backup and restore operations whose encrypted archive contains SQLite project metadata and project configuration, private keys, signed modpack and player-program manifests, and every published content object. Restoring it recovers publishing authority and previously served releases. Runtime settings, Web administrator credentials and unpublished source content are separate migration inputs; the CLI archive is not a complete backup of the host or the application home directory.

Restore validates the archive before replacing existing data. If installation fails it attempts to restore the original directory; if both operations fail, it preserves the original and verified restored directories and reports their locations. Cleanup must not remove the sole recovery copy.

Scope clarified on 2026-10-02 to match the archive's contents.
