# Optimization Experiment Runner — Context

**Gathered:** 2026-07-03 (updated 2026-07-03)
**Spec:** `.specs/features/optimization-experiment-runner/spec.md`
**Status:** Ready for design

---

## Feature Boundary

Set up a minimal project skeleton and pipeline for running optimization experiments. Focus on the *experiment setup infrastructure* (orchestration, parallel execution, result collection) — not on algorithm logic. All algorithm/validator logic is stubbed.

---

## Implementation Decisions

### Problem Domain

- **Decision**: Do not focus on algorithm implementation details. This spec is purely about experiment setup and pipeline infrastructure.
- **Implementation**: Use mock algorithms that return empty/zero-valued solution JSONs. Validator placeholder computes trivial metrics.

### Datasets

- **Decision**: Use existing `datasets/a/`, `datasets/b/`, `datasets/x/` directories directly — do not copy or symlink into `project/data/`.
- **Implementation**: The config file references these paths (relative or absolute). The `project/data/` directory can be removed or left empty.

### Analysis Script

- **Decision**: Create an empty `analyze_results.py` file only. No aggregation logic needed for now.
- **Implementation**: `project/analysis/analyze_results.py` will exist as a placeholder file.

### Algorithm Implementations

- **Decision**: `algo_runner.cpp` returns empty/zero-valued solution JSON `{"selected_orders":[],"visited_aisles":[],"exec_time":<elapsed>}`. No real optimization logic. The two mock algorithms (`grasp`, `simulated_annealing`) just sleep briefly and return the empty solution with measured exec_time.

### Instance Input

- **Decision**: Instance content is passed via `--input='<content>'` string argument, not as a file path.
- **Rationale**: Orchestrator reads the instance file and passes the content as a string. The C++ binary never reads files, ensuring file I/O is not conflated with algorithm execution time.

### Timing

- **Decision**: `exec_time` is measured internally by `algo_runner` after input parsing is complete. The orchestrator reads `exec_time` from the solution JSON, not from wall-clock measurement.
- **Rationale**: The user explicitly requested that load time not be considered in exec_time.

### Contracts (Locked)

- `algo_runner` CLI interface, JSON output format, and exit codes are fixed as specified
- Validator I/O contract (instance_path + solution_path → dict) is fixed
- Config JSON format is fixed
- Result directory layout is fixed (`result_XXXX/<dataset>/<algorithm>/<instance>.csv`)

### Orchestrator Output Verbosity

- **Decision**: Print `"Running N tasks with W workers..."` at start, then only the final summary line (`N total, N feasible, N timeout, N error`). No per-task output.
- **Rationale**: Minimal but informative. User knows the experiment started and gets the result. No clutter.

### Failure Tolerance & Partial Results

- **Decision**: Always write everything. Even if 100% of tasks fail, write all CSVs with error/timeout rows and print the summary. The experiment "succeeded" in running — the data just shows failures. Exit code 0.
- **Missing instances**: Log a warning (e.g., `"WARNING: Instance 'datasets/a/instance_9999.txt' not found, skipping"`) and continue with remaining tasks. Summary reflects only tasks that ran.

### Config Validation

- **Decision**: Minimal validation — only check that the file is valid JSON and has the top-level keys. Let downstream code fail naturally on bad values.
- **Rationale**: Less code, and the user is the primary consumer (not untrusted input).

### Subprocess Management

- **Grace period**: 5 seconds (`timeout=time_limit + 5`). Generous for slow I/O or large solution writes.
- **Temp solution files**: Stay in `/tmp` after the experiment. They're small and might be useful for debugging.
- **Signal handling (Ctrl+C)**: Register a SIGINT/SIGTERM handler that kills all child processes and exits cleanly. No result files for incomplete experiments.

### Parallel Execution Details

- **Worker count**: Use `n_workers` as-is from config. No capping at CPU count. The user knows their system.
- **Temp solution file location**: Use `tempfile.mkstemp(dir=None)` — system temp dir (`/tmp`), auto-unique filenames.
- **CSV row ordering**: Completion order (no sorting). Row order is not functionally important.
- **Task ordering in dispatch**: Agent's discretion.

### Agent's Discretion

- Exact number of mock dataset instances to reference in the example config
- Error message text for CLI args
- CSV column ordering and naming
- Which Python version to target (3.10+)
- Mock sleep duration for dummy algorithms
- Whether `project/data/` directory is removed or kept empty
- Task ordering before dispatch (deterministic sort or config order)

---

## Specific References

- "dont focus in the algo impl, just the experiments seteup"
- "Use existing datasets/a/b/x directly"
- "create a empty file" (for analyze_results.py)
- "just a c++ code that return empty or 0 values"
- "receive as parameter the input from the instance. The load time should not be consider when saving the exec time"

---

## Deferred Ideas

- Real algorithm implementations (separate effort, not part of this pipeline setup)
- Real validator / feasibility rules (separate effort)
- Summary statistics aggregation (analyze_results.py will be filled in later)
- Configurable logging levels for orchestrator
- Checkpoint/resume for interrupted experiments
- Per-algorithm time limits (different from global)
- Git-commit hash embedding in result directories for traceability
