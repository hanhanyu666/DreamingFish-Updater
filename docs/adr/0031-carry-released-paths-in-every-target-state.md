---
status: accepted
---

# Carry released paths in every target state

When an owner removes a managed source file, publishing requires an explicit choice between deleting the player copy and releasing management while preserving it. Released paths remain in every later complete desired-state manifest until the path is managed again, so players updating directly from any old baseline cannot miss a one-time ownership transfer; releases carrying this semantic require an explicit player capability and older players fail closed.
