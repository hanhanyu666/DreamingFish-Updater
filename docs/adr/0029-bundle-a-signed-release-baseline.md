---
status: accepted
---

# Bundle a signed release baseline

Every formally distributed player instance carries the signed immutable release manifest that its packaged files represent. The management tool requires an explicit historical release when preparing an instance and verifies or materializes that release's files, allowing a fresh copy of any old official package to distinguish formerly managed files from player additions and converge directly to the latest release without an incremental update chain.
