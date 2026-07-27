---
status: accepted
---

# Self-update the Player Updater but not the bootstrap Agent

The JavaFX Player Updater and update engine are installed in project-signed version directories. The current process atomically selects a verified new directory and exits without denying launch; the bootstrap Agent reloads that selection, starts the new program, and falls back to the previous verified program if it exits before granting permission. V1 keeps the minimal Agent fixed because it is loaded inside the Minecraft JVM and replacing that trust anchor safely adds disproportionate complexity; an Agent fix requires redistributing the base modpack integration.
