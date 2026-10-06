---
status: accepted
---

# Keep transaction backups only until commit

The Player Updater stages and verifies all required content before applying changes, moves replaced or removed files into a transaction backup, and restores that backup after failure or interrupted recovery. A successful commit records the verified installation and removes the temporary backup; V1 does not retain a complete previous installation for player-selected rollback, which remains an operator-controlled new release.

Local mod moves and their preference indexes use a separate durable transaction with the same prepare/commit distinction. Uncommitted moves restore their prior locations and preferences; committed cleanup cannot delete a stored copy whose ownership was released to the player. Player-owned disabled storage and forced-sync archives are permanent user data, separate from transaction cleanup copies.
