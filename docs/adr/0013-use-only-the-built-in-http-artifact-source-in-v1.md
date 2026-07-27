---
status: accepted
---

# Use only the built-in HTTP artifact source in V1

V1 distributes public release manifests and artifacts through the Management Tool's built-in standard HTTP service. CDN, object storage, WebDAV, HTTPS termination, and custom transfer protocols are excluded; because transport is unauthenticated and unencrypted, signed manifests and cryptographic content hashes are mandatory, and no management credentials or private data may be exposed through this public endpoint.
