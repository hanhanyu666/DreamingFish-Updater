---
status: superseded by 0033
---

# Allow file-level forced sync

Projects may force-sync individual managed files as well as complete top-level directories. The signed target state carries exact forced file paths, which override player-local exemptions without claiming ownership of sibling files; the feature has its own required capability so older players cannot silently treat these files as ordinarily excludable.

Superseded on 2026-10-02 by ADR 0033: presets decide how published files are maintained and cleanup directories are a separate switch. Releases from before that change keep this behaviour through the compatibility mapping.
