# Post-mortems

Blameless, written within five working days of any incident that paged someone or consumed more than 20%
of a monthly error budget ([SLO policy](../slo.md#error-budget-policy)). The goal is learning and
prevention, not attribution: describe what the system and the process allowed, not who made a mistake.

Name files `YYYY-MM-DD-short-title.md`. An example of the expected depth is
[EXAMPLE-gds-timeouts.md](EXAMPLE-gds-timeouts.md) (a fictional incident used to demonstrate the format).

## Template

```markdown
# <Title>

- **Date:** YYYY-MM-DD   **Duration:** hh:mm   **Severity:** SEV1 | SEV2 | SEV3
- **Authors:**           **Status:** Draft | Reviewed | Actions complete
- **Error budget consumed:** n% of the monthly availability budget

## Summary
Two or three sentences: what customers experienced, for how long, and the root cause in plain words.

## Impact
Who was affected and how much (requests, bookings, money, support contacts). Which SLOs were breached.

## Timeline (UTC)
| Time | Event |
|------|-------|
| hh:mm | First signal (alert / customer report) |
| hh:mm | Mitigation applied |
| hh:mm | Resolved |

## Root cause and contributing factors
The chain of causes (5 whys). Separate the trigger from the conditions that let it become an incident.

## Detection
How we found out, how long it took, and whether an alert should have fired earlier.

## Response and recovery
What worked, what slowed us down (access, tooling, runbook gaps).

## Action items
| Action | Type (prevent / detect / mitigate / process) | Owner | Due | Ticket |
|--------|----------------------------------------------|-------|-----|--------|

## Lessons learned
What went well, what went badly, where we got lucky.
```
