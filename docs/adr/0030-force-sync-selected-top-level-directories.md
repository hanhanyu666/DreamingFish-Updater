---
status: accepted
---

# Force-sync selected top-level directories

A project may independently mark selected top-level source directories as complete mirrors. A signed release carries those directory choices and requires an explicit player capability; extra local files below those directories are moved into permanent player-managed archives inside the update transaction, while directories without that policy retain unmanaged files. Publishing fails when a configured mirror directory is absent, preventing an accidental missing source path from emptying player content.
