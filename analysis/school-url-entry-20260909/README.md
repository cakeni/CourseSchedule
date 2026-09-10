# School URL entry audit

WakeUp 6.1.20's decrypted `school_list.json` contains 3,570 rows: 3,091 have a
non-empty URL and 479 do not. All 22 generic-system rows have an empty URL by
design. They select a parser family; the user still needs to supply the
school's teaching-system address.

The bundled CourseSchedule directory exposes 2,682 one-tap routes after its
built-in replacements. Another 764 displayed rows now map to a local parser
but lack a loadable URL. They use the same generic flow and ask for a
user-confirmed school URL. The newly recovered routes include 32 `suda_post`,
13 `zju_post`, seven `xju_post`, six `cupl_post`, five `hit`, four `scau`, four
`hitsz`, two `xhtd`, two `gdei`, one `uestc_post`, and one `swjtu_post` row.
The remaining 120 rows stay non-actionable: 104 are `ziyan` remote-parser rows,
15 still need school-specific DOM or JSON/fetch capture, and one `login` row
has no parser dispatch evidence.

Reference URLs are shown only as historical guidance. They are never loaded
automatically, because many are private IPs, stale endpoints, or bare WebVPN
portals rather than timetable resources.
