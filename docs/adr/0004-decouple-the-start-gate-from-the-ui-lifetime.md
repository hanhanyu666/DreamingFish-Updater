---
status: accepted
---

# Decouple the game-start gate from the updater UI lifetime

The launcher may continue as soon as the Player Updater determines that the local modpack is safe to use, while the updater window may remain visible and close later. A required update holds the start gate until its verified transaction is committed; separating the gate from the UI lifetime avoids delaying game startup solely for presentation and requires the bootstrap and visible UI to coordinate as independent processes.
