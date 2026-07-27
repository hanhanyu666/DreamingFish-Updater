---
status: accepted
---

# Publish only consistent source snapshots

Publishing imports the scanned files and then verifies that the standard modpack directory did not change during the operation. Any detected change invalidates the preview and aborts publishing, accepting extra disk I/O to prevent an immutable release from mixing files from different source states.
