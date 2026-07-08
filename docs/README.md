# Docs map

Three rules keep this documentation useful:

1. Living state, not journals. Each doc describes what is true now in its lane
   and is updated in place; history lives in git.
2. One home per fact. Link instead of duplicating; if two docs explain the same
   thing, one of them is wrong.
3. Shortest complete form. A doc that cannot answer "what is true now, what
   happens next" in its lane gets fixed, not extended.

| Doc | Lane | Update when |
|-----|------|-------------|
| [../README.md](../README.md) | What the project is, quickstart | Setup or commands change |
| [../AGENTS.md](../AGENTS.md) | Directives for coding agents | New rule, command, or pattern emerges |
| [../CHANGELOG.md](../CHANGELOG.md) | User-visible changes | With each change, under Unreleased |
| [design.md](design.md) | Vision, decisions (D*), assumptions (A*), format, architecture, risks | Any decision made or changed |
| [plan.md](plan.md) | Current phase plan and exit criteria | Phase progress; rewritten at phase turnover |
| [roadmap.md](roadmap.md) | Future versions and gated ideas | Scope moves in or out |
| [../lab/survivability-matrix.md](../lab/survivability-matrix.md) | Phase 0 ground truth | Each lab run |
| [../spec/engram-spec-v0.md](../spec/engram-spec-v0.md) | Engram format specification (open, CC BY 4.0) | Wire or binding changes |
