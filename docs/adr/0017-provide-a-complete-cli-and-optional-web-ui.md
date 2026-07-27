---
status: accepted
---

# Provide a complete CLI and optional WebUI

Every management workflow is available through a complete CLI, while the local WebUI is an optional module that can be disabled on constrained hosts. Both entry points call the same application services, and the public artifact HTTP service operates independently of the WebUI, preventing duplicated behavior and keeping headless recovery possible.
