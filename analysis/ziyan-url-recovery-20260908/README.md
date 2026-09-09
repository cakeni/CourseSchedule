# Ziyan school URL recovery

Audit workspace for the 222 bundled `ziyan` entries that currently have no
locally loadable timetable URL. Only school-controlled or otherwise
independently verifiable entry points are eligible for the application catalog.

Status: second-pass recovery applied. Of 1,088 `ziyan` rows, 866 already had a
safe candidate URL in the bundled source and 118 more were recovered here. The
984 reviewed rows now use the app's experimental on-device parsers; 104 rows
remain adapter requests because no reliable public-domain entry was confirmed.
Those 104 references currently break down into 57 bare public-IP endpoints,
29 private-network endpoints, 10 VPN/zero-trust-only routes, 7 missing or
placeholder values, and 1 other unverified reference.

Sources are kept separate from the application catalog until an entry point is
verified. `fetch_official_homepages.ps1` joins the 222 records to the MIT-licensed
EduCN school-homepage dataset; `collect_official_links.ps1` then inspects links
published by those homepages for teaching, portal, and identity-system entry
points. Search-engine candidates are evidence for follow-up only.

`probe_official_candidates.ps1` checks the externally addressable candidates
published by official homepages and records redirects, page titles, and local
parser fingerprints without storing response bodies.

`collect_department_links.ps1` follows only short, explicit teaching-office
links from the official homepage and searches those pages for a direct system
entry point.

`discover_same_domain_hosts.ps1` resolves common teaching/portal subdomains
under the same institutional domain and filters wildcard DNS answers.
`probe_same_domain_hosts.ps1` then inspects only those non-wildcard hosts.

`verified_urls.json` is the auditable 118-row enabled decision set. Twelve rows
also declare the exact reviewed authentication scope needed for their login
redirect. The set otherwise excludes private addresses, bare public IPs,
generic VPN login roots, and informational pages. `apply_verified_urls.ps1`
applies that set without replacing a different existing URL.
