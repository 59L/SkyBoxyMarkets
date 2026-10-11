# SkyBoxyMarket

Paper marketplace plugin (Java 25, Gradle 9.2.1, one package `dev.skyboxy.market`). Tested development build, **not production-ready**.

## Start here

1. `RESUME_PLAN.md` — where the Codex sessions stopped, the ordered plan, and the next step to take.
2. `FEATURE_STATUS.md` — honest per-area status and the open release gates.
3. `CODEX_IMPLEMENTATION_PLAN.md` — authoritative product requirements and the Codex execution record.

When a step in `RESUME_PLAN.md` is finished, update that file and `FEATURE_STATUS.md` in the same commit.

## Build and test

```powershell
$env:JAVA_HOME = "$PWD\.temp\toolchain\jdk-25.0.4.1+1"
$env:GRADLE_USER_HOME = "$PWD\.temp\gradle-home"
.\gradlew.bat clean test shadowJar --no-daemon
```

The system default Java is 21 and cannot build this. Paper acceptance runs on the isolated scratch server in `.temp/paper-smoke/` via `run-claims.ps1` and `run-full-sequence.ps1`; see `RESUME_PLAN.md` section 2. `.temp/` is git-ignored and machine-local.

## Rules

- Keep edits inside this directory; the parent repository holds unrelated sibling plugins.
- Do not install anything to the live server (`PluginsNew`) without asking first.
- Do not commit the third-party shop JARs in the project root.
- Never auto-generate booths, and never create per-booth WorldGuard regions.
- Never clear containers or shop stock before the recovery ledger commit is durable.
- Shop integrations fail closed and never invent sales figures.
- Claim "verified" only for scenarios that were actually run; record the JAR SHA-256 whenever the artifact changes.
