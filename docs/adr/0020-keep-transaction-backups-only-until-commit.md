---
status: accepted
---

# Keep transaction backups only until commit

The Player Updater stages and verifies all required content before applying changes, moves replaced or removed files into a transaction backup, and restores that backup after failure or interrupted recovery. A successful commit records the verified installation and removes the temporary backup; V1 does not retain a complete previous installation for player-selected rollback, which remains an operator-controlled new release.
