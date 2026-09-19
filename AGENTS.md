# Publication boundary

This repository publishes the application, not private research.

- Exclude manuscripts, literature, field measurements, research datasets,
  analysis scripts, paper figures, tables, and generated research outputs from
  commits and pushes unless the user explicitly authorizes the exact material.
- In particular, do not publish `paper/`, `val/`, `research/`, `data/`,
  `datasets/`, `.codex-backups/`, `docs/ABSTRACT_COVERAGE.md`,
  `docs/VALIDATION.md`, or `tools/validation/audit_v9_artifacts.py`.
- Already tracked research files remain private for FUTURE changes: never use
  broad `git add -A` or `git add -u` when preparing application commits.
  Inspect and stage only the intended application paths.
- Synthetic application test fixtures and application reference resources are
  not field datasets; inspect their contents before including them.
- Do not bypass publication guards with `--no-verify` or change hook settings
  to publish research. Unknown new data paths require a scope check.
- Keep local research files intact. Removing existing public files, changing
  repository visibility, or rewriting published history requires a separate,
  explicit user request. Ignoring files does not remove previously published
  copies or history.

This checkout has local pre-commit and pre-push guards in `.git/hooks/`.
Git does not transfer these hooks to another clone; configure equivalent guards
before publishing from another computer.
