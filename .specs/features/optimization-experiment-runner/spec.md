# Optimization Experiment Runner — Specification

## Problem Statement

Running optimization algorithm benchmarks requires executing multiple algorithm variants against many problem instances across several datasets, often with multiple randomized runs per configuration. Doing this manually is error-prone (missed instances, inconsistent timeouts, no traceability). We need a minimal pipeline that automates the full experiment lifecycle: task generation, parallel execution with timeouts, result collection.

## Goals

- [ ] Automate parallel execution of C++ optimizers against instance collections with reproducible result directories
- [ ] Normalize solution evaluation through a single validator shared across all algorithms
- [ ] Produce raw per-run CSVs with no manual steps
- [ ] Complete end-to-end pipeline verification in <60s with mock algorithms

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Real algorithm implementations | Swapped in after pipeline verification; separate concern |
| GUI / visualization | CLI-only; data is CSV-exportable to any tool |
| Multi-machine distribution | Single-machine process pool |
| Real-time progress monitoring | Batch-mode only |

---

## User Stories

### P1: C++ Algorithm Binary — Run with Named Variants ⭐ MVP

**User Story**: As a researcher, I want to run a C++ optimizer with a specific algorithm, named parameter configuration, random seed, and time limit so I can get a solution from the command line.

**Why P1**: Every downstream component depends on this binary's output.

**Acceptance Criteria**:

1. WHEN I run `./algo_runner --binary=grasp --input='<instance content>' --params='{"alpha":0.3}' --time-limit=60 --seed=42 --output=<path>` THEN the binary SHALL write a JSON solution `{"selected_orders":[...],"visited_aisles":[...],"exec_time":0.0}` to the output path
2. WHEN I run with valid arguments and the algorithm completes within the time limit THEN the binary SHALL exit with code 0
3. WHEN I run with an invalid `--binary` name THEN the binary SHALL exit with code 2
4. WHEN I run with missing required arguments THEN the binary SHALL exit with code 1
5. WHEN the algorithm is killed externally or crashes unexpectedly THEN the binary SHALL exit with a non-zero code (not 0, 1, or 2)
6. WHEN the algorithm hits the time limit internally THEN the binary SHALL return the best solution found so far and exit with code 0

**Instance input**: The `--input` flag receives the entire instance file content as a single string argument. The algorithm parses this string directly — no file reads inside `algo_runner`. This ensures file I/O time is never conflated with algorithm execution time.

**Processing time**: The solution JSON includes an `exec_time` field (float, seconds) reporting only the algorithm processing time — measured internally after input parsing is complete, excluding any setup or I/O.

**Independent Test**: `./algo_runner --binary=grasp --input='61 155 116\n3 13 1 36 1 135 1' --params='{}' --time-limit=5 --seed=1 --output=/tmp/out.json && cat /tmp/out.json` produces valid JSON

### P1: Solution Validator — Compute Feasibility and Objective ⭐ MVP

**User Story**: As a researcher, I want to validate a solution JSON against its instance file so I get standardized metrics regardless of which algorithm produced it.

**Why P1**: Keeps solution evaluation consistent and decoupled from algorithm implementations.

**Acceptance Criteria**:

1. WHEN I call `validate(instance_path, solution_path)` with a valid instance and solution THEN the function SHALL return a dict with keys `status`, `objective`, `gap`, `items`, `aisles`
2. WHEN the solution is feasible THEN `status` SHALL be `"feasible"` and `objective` SHALL be a positive number
3. WHEN the solution is infeasible THEN `status` SHALL be `"infeasible"`
4. WHEN called as a CLI script `validator.py <instance> <solution>` THEN it SHALL print the result dict as JSON to stdout

**Independent Test**: `python validator/validator.py datasets/a/instance_0001.txt /tmp/out.json` returns well-formed JSON

### P1: Config-Driven Orchestrator — Task Grid and Parallel Execution ⭐ MVP

**User Story**: As a researcher, I want to define an experiment in a JSON config file and run it so the orchestrator generates all task combinations, executes them in parallel, and writes raw CSV results.

**Why P1**: This is the primary user-facing entry point of the system.

**Acceptance Criteria**:

1. WHEN I run `python run_experiment.py config.json` THEN the orchestrator SHALL create `results/result_XXXX/` where `XXXX` is the next available number
2. WHEN the experiment runs THEN the orchestrator SHALL copy the config to `result_XXXX/config.json` for traceability
3. WHEN the experiment completes THEN there SHALL be one CSV per `(dataset, algorithm, instance)` combination under `result_XXXX/<dataset>/<algorithm>/<instance>.csv`
4. WHEN the experiment completes THEN each CSV SHALL have exactly `runs_per_instance` rows plus a header
5. WHEN a worker's subprocess exceeds the configured `time_limit` THEN the orchestrator SHALL kill it and record `status="timeout"`
6. WHEN a worker's subprocess exits with non-zero code THEN the orchestrator SHALL record `status="error"`
7. WHEN a worker's subprocess exits with code 0 THEN the orchestrator SHALL run the validator on the produced solution and record the validator's output
8. WHEN all workers complete THEN the orchestrator SHALL print a summary: `N total, N feasible, N timeout, N error`
9. WHEN `n_workers > 1` THEN tasks SHALL execute in parallel across that many worker processes

**Independent Test**: Run against `config.example.json` with mock algo_runner; verify `result_0001/` has correct folder/file structure and row counts

### P2: End-to-End Pipeline Smoke Test

**User Story**: As a developer, I want a single script sequence (compile → run_experiment) that completes in <60s so I can verify all components integrate correctly before scaling up.

**Why P2**: Catches integration bugs early; provides a repeatable verification for CI or local dev.

**Acceptance Criteria**:

1. WHEN I run the compile and run_experiment commands sequentially THEN each SHALL exit with code 0
2. WHEN the pipeline completes THEN `results/result_0001/` SHALL contain the correct CSV structure
3. WHEN the pipeline completes THEN the elapsed time SHALL be < 60 seconds

**Independent Test**: Run `g++ ... && python run_experiment.py config.example.json` and verify exit code 0

### P3: Real Algorithm Implementations

**User Story**: As a researcher, I want the mock algorithm bodies replaced with real optimization logic so the pipeline produces meaningful solutions.

**Why P3**: The pipeline is verified with mocks; real algorithms are a separate implementation effort.

**Acceptance Criteria**:

1. WHEN I run `algo_runner --binary=<real_algo>` THEN the returned solution SHALL be computed by the real optimization logic
2. WHEN the real algorithm hits the time limit THEN it SHALL return the best-found solution (same contract as mocks)

### P3: Real Validator Logic

**User Story**: As a researcher, I want the placeholder validator replaced with the actual feasibility rules and objective function so solution metrics are meaningful.

**Why P3**: Validator placeholder suffices for pipeline testing; real rules are a separate concern.

---

## Result Output Structure

After a successful run, the `results/` directory looks like this:

```
results/
├── result_0001/
│   ├── config.json                          # copy of experiment config (traceability)
│   ├── datasets/a/
│   │   ├── grasp_a03_i100/
│   │   │   ├── instance_0001.csv            # 3 rows (runs_per_instance=3)
│   │   │   ├── instance_0002.csv
│   │   │   └── instance_0003.csv
│   │   └── sa_t1000_c095/
│   │       ├── instance_0001.csv
│   │       ├── instance_0002.csv
│   │       └── instance_0003.csv
│   └── datasets/b/
│       ├── grasp_a03_i100/
│       │   ├── instance_0001.csv
│       │   └── instance_0002.csv
│       └── sa_t1000_c095/
│           ├── instance_0001.csv
│           └── instance_0002.csv
├── result_0002/
│   └── ...
└── result_0003/
    └── ...
```

Each `instance.csv` contains one row per run (matching `runs_per_instance` in the config), with the following columns:

| Column | Type | Description |
| ------ | ---- | ----------- |
| `run_id` | int | Run number (1..runs_per_instance) |
| `seed` | int | Random seed used for this run |
| `status` | string | `feasible` / `infeasible` / `timeout` / `error` |
| `objective` | float | Objective value (0.0 if timeout/error) |
| `gap` | float | Optimality gap percentage (0.0 if timeout/error) |
| `items` | int | Number of items selected/picked |
| `aisles` | int | Number of aisles visited |
| `exec_time` | float | Algorithm processing time in seconds (excludes instance loading, setup, I/O) |

Example `instance_0001.csv` for `runs_per_instance=3`:
```csv
run_id,seed,status,objective,gap,items,aisles,exec_time
1,42,feasible,1250.5,2.3,45,7,1.234
2,99,feasible,1280.1,4.1,44,8,1.456
3,17,timeout,0.0,0.0,0,0,10.000
```

---

## Parallel Execution Model

### Task Grid Generation

The orchestrator builds the full task list as a Cartesian product of `(dataset, algorithm, instance, run_id)` before dispatching:

```
total_tasks = sum(instances(d) for d in datasets) × algorithms × runs_per_instance
```

Each task is fully self-contained — it carries its own seed, instance path, algorithm id, and params. There is no shared mutable state between tasks.

### Work Distribution

Tasks are dispatched via `concurrent.futures.ProcessPoolExecutor(max_workers=n_workers)`. The executor maintains an internal work queue; workers pull the next available task as they finish the previous one (dynamic load balancing — no manual partitioning).

```
Main Process                 Pool Workers
    │                             │
    ├─ build task list ───────────┤
    ├─ executor.map(tasks) ──────→├─ worker 1: task_1 → task_5 → ...
    │                             ├─ worker 2: task_2 → task_6 → ...
    │                             ├─ worker 3: task_3 → task_7 → ...
    │                             └─ worker 4: task_4 → task_8 → ...
    │                                     │
    ├─ collect results ←─── all done ────┘
    └─ write CSVs sequentially
```

### Per-Worker Lifecycle

Each worker executes one task at a time — isolated from all others:

1. **Compute seed**: `seed_base + run_id` (ensures reproducibility across restarts)
2. **Read instance**: Read instance file content from disk into a string
3. **Build command**: `./algo_runner --binary=<algo> --input='<instance_content>' --params='<json>' --time-limit=<tl> --seed=<seed> --output=<tmp_solution_path>`
4. **Launch subprocess**: `subprocess.run(cmd, capture_output=True, timeout=time_limit + grace_period)`
5. **Outcome routing**:
   - `TimeoutExpired` (or wall-clock > `time_limit`) → kill subprocess → `status="timeout"`, `exec_time=time_limit`
   - Return code ≠ 0 (without timeout) → `status="error"`, `exec_time=0.0`
   - Return code == 0 → read solution JSON (includes `exec_time` from algo_runner), call validator → `status` from validator
6. **Return dict** to main process: `{dataset, algorithm, instance, run_id, seed, status, objective, gap, items, aisles, exec_time}` (exec_time from algo_runner's JSON, not wall-clock)

### Isolation and Safety

- Workers never write to the filesystem directly — all results go through the main process
- No shared memory, no locks, no queues
- A crash or hang in one worker does **not** affect other workers (the executor replaces it)
- The main process is single-threaded for result collection and file I/O

### CSV Write Strategy

Results are buffered in memory as a flat list of dicts. After all workers complete, the main process:

1. Groups results by `(dataset, algorithm, instance)`
2. Creates output directories as needed
3. Writes one CSV per group (header + all rows for that instance)
4. Prints the summary line

This approach avoids concurrent-write corruption and is safe for up to tens of thousands of runs. For larger experiments, a producer-consumer pattern with a single writer consuming from a queue can be substituted without changing the output format.

---

## Edge Cases

- WHEN `results/` directory does not exist THEN the orchestrator SHALL create it
- WHEN all runs for a (dataset, algorithm, instance) are `timeout` or `error` THEN the CSV SHALL still contain entries with those statuses
- WHEN the config specifies `runs_per_instance: 1` THEN the orchestrator SHALL still work correctly with single-run tasks
- WHEN `n_workers` exceeds the number of CPU cores THEN the orchestrator SHALL use the configured value (OS schedules accordingly)
- WHEN an instance file is missing or unreadable THEN the algo_runner SHALL exit with code 1
- WHEN the config references a dataset or instance that does not exist THEN the orchestrator SHALL log a warning and skip those tasks

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| ALGO-01 | P1: Algo binary — valid args produce JSON | Design | In Design |
| ALGO-02 | P1: Algo binary — exit code 0 on success | Design | In Design |
| ALGO-03 | P1: Algo binary — exit code 2 on unknown binary | Design | In Design |
| ALGO-04 | P1: Algo binary — exit code 1 on bad args | Design | In Design |
| ALGO-05 | P1: Algo binary — crash exit non-zero (not 0/1/2) | Design | In Design |
| ALGO-06 | P1: Algo binary — time limit returns best solution | Design | In Design |
| VAL-01 | P2: Validator — returns dict with 5 keys | Design | In Design |
| VAL-02 | P2: Validator — feasible status and positive objective | Design | In Design |
| VAL-03 | P2: Validator — infeasible status | Design | In Design |
| VAL-04 | P2: Validator — CLI mode prints JSON | Design | In Design |
| ORCH-01 | P1: Orchestrator — creates result_XXXX dir | Design | In Design |
| ORCH-02 | P1: Orchestrator — copies config for traceability | Design | In Design |
| ORCH-03 | P1: Orchestrator — creates per-instance CSVs | Design | In Design |
| ORCH-04 | P1: Orchestrator — correct CSV row count | Design | In Design |
| ORCH-05 | P1: Orchestrator — timeout kills worker | Design | In Design |
| ORCH-06 | P1: Orchestrator — error status on non-zero exit | Design | In Design |
| ORCH-07 | P1: Orchestrator — validates on exit code 0 | Design | In Design |
| ORCH-08 | P1: Orchestrator — prints summary | Design | In Design |
| ORCH-09 | P1: Orchestrator — parallel execution | Design | In Design |
| E2E-01 | P2: Smoke test — all commands exit 0 | Design | In Design |
| E2E-02 | P2: Smoke test — result_XXXX has correct structure | Design | In Design |
| E2E-03 | P2: Smoke test — completes in <60s | Design | In Design |

**ID format:** `[CATEGORY]-[NUMBER]`
**Status values:** Pending → In Design → In Tasks → Implementing → Verified

**Coverage:** 21 total, 0 mapped to tasks, 21 unmapped

---

## Success Criteria

- [ ] Running `g++ -O2 -std=c++17 -o algos/algo_runner algos/algo_runner.cpp` compiles without warnings
- [ ] Running `python orchestrator/run_experiment.py orchestrator/config.example.json` produces `results/result_0001/` with correct structure
- [ ] The entire smoke test completes in <60 seconds
- [ ] All 21 requirement IDs mapped to tasks and verified
