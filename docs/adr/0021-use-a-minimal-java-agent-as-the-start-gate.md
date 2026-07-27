---
status: accepted
---

# Use a minimal Java Agent as the game-start gate

A Java 8-compatible bootstrap Agent runs before Minecraft main and mod-loader discovery, launches the independent JavaFX Player Updater, and blocks only until it receives the game-start grant. Downloading, installation, logging, and UI remain outside the game JVM; after the grant the Agent returns immediately while the separate UI may remain visible, keeping loader integration small and compatible with game runtimes from Java 8 onward.
