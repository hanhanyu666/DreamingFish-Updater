---
status: accepted
---

# Optionally keep external upload credentials for automatic uploads

Owners who serve players from OSS, WebDAV or another static host had to export and upload by hand after every publish, pause and automatic player publish, retyping the secret each time. That made the optional automatic player publishing (ADR 0040) incomplete for them: players behind a static host never saw the new version until the owner remembered to upload.

The owner may now tick "保存密钥" on a WebDAV or S3-compatible upload. The destination and its credentials are kept only after that upload succeeded, one destination per project, in `keys/<project>.hosting.json` beside the project signing key: owner-only on POSIX, the data directory's ACL on Windows, and inside the encrypted management backup like the signing key. The workbench receives only non-secret fields (address, bucket, prefix, Access Key ID, user name); secrets are never returned, logged or written to exports, deployment packages or public release archives. Session tokens expire and are refused; addresses carrying user info or query parameters are refused, so a credential cannot hide in a displayed field. "删除保存的密钥" removes the file and the upload status.

With automatic upload on (the default when saving), the running management service checks a cheap digest of everything an export publishes (release list and signatures, history, presentation, player programs, update policy) shortly after any management change and every ten minutes, and exports and uploads only when it differs from the last upload to the same destination. A failure is recorded on the hosting page and retried after an hour or as soon as something new is published; a busy project is retried on the next pass. Uploads still require the management service to be running and still use the existing incremental, retirement-checking uploader.

This amends ADR 0039: credentials remain excluded from task inputs and are never replayed from a task record, but an owner may explicitly keep them in management data. Owners who do not tick the option keep the previous behaviour.
