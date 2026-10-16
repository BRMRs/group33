# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the labels used in this repo's GitHub issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning |
| --- | --- | --- |
| `needs-triage` | `needs-triage` | Maintainer needs to evaluate this issue |
| `needs-info` | `needs-info` | Waiting on reporter for more information |
| `ready-for-agent` | `ready-for-agent` | Fully specified, ready for an AFK agent |
| `ready-for-human` | `ready-for-human` | Requires human implementation |
| `wontfix` | `wontfix` | Will not be actioned |

When a skill mentions a role, use the corresponding label string from this table.

The repository also uses workflow labels defined in `docs/ENGINEERING_PLAYBOOK.md`: `in-progress`, `in-review`, and `blocked`. These describe active delivery state and do not replace the five canonical triage labels above. Risk/type labels such as `security`, `contract`, and `migration` may be combined with either group.
