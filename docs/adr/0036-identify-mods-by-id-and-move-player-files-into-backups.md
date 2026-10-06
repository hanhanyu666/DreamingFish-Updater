---
status: accepted
---

# Identify mods by mod ID and move player files into backups

The updater identifies mods by mod ID and version rather than file name. Self-managing or disabling a mod follows its mod ID across renamed updates, a published mod replaces a different local copy of the same mod (the old copy is backed up), and an extra jar declaring the mod ID of a published mod is moved out because two copies crash the game.

Player files are moved, never deleted: an unchanged official copy may be deleted, but a modified, self-managed, replaced, withdrawn, corrected or cleaned-up file goes into `backups/archive/<time>_<release>_<transaction>/` with an index recording each file's reason, release and version. The updater shows these backups and can put a file back after checking that the next update would not move it again. When the owner deletes a mod, that deletion also applies to players who self-manage it (their copy is backed up) unless the owner chose to keep self-managed copies.

Extends ADR 0020; decided on 2026-10-02.
