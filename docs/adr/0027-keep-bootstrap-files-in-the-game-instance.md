---
status: accepted
---

# Keep bootstrap files in the game instance

The bootstrap Agent and project binding file remain at stable paths inside the version-isolated game instance because third-party launcher JVM arguments reference the Agent directly. The JavaFX Player Updater, cache, logs, and local state default to that instance but may move together to a player-selected directory, which the binding file records; this avoids launcher-specific configuration rewriting while preserving portable storage.
