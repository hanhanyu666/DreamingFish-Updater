---
status: accepted
---

# Use a dedicated modpack source directory

Each project points to one dedicated standard modpack directory that the server owner edits directly. The Management Tool scans this directory, applies exclusions and file policies, previews the resulting differences, and imports content into the hash-addressed store only when publishing; it does not maintain a duplicate McPatch-style workspace or treat a player instance or running server directory as the release source, avoiding ambiguous ownership and accidental publication of runtime files.
