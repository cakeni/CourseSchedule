# School adapter v2 work area

This directory contains only reproducible, public-endpoint audit tooling and
generated evidence summaries for the first local-parser expansion. It must not
contain credentials, cookies, authenticated timetable responses, or copied
third-party implementation code.

The runtime application continues to parse timetable data on the device. Audit
results may promote a directory entry only to `experimental`; `verified`
requires an end-to-end check by a user of that school.

Commands:

```powershell
node analysis/school-adapter-v2-20260909/upgrade-directory-v3.mjs
node analysis/school-adapter-v2-20260909/audit-public-endpoints.mjs
node analysis/school-adapter-v2-20260909/validate-directory-security.mjs
```

The security check requires one cleartext-enabled base configuration and zero
domain rules, so HTTP schools and authentication redirects never depend on a
maintained host whitelist.

The audit is deliberately non-promoting. A maintainer must add and review
school-official evidence before changing an `adapter_required` entry to
`experimental`.
