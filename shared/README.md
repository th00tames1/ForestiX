# ForestiX Shared

This folder holds cross-platform contracts for the native iOS and Android apps.
The apps stay native, but measurement math and data behavior should be verified
against the same shared specs and golden fixtures.

Current scope:
- DBH chord/silhouette geometry spec
- DBH golden cylinder fixtures consumed by both app test suites

Recommended layout:
- `specs/`: human-readable platform contracts
- `fixtures/`: small deterministic test cases shared by iOS and Android
