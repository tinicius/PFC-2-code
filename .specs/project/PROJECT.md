# Optimization Experiment Runner

**Vision:** A minimal, reproducible experiment pipeline that runs C++ optimization algorithms against multiple datasets in parallel, captures raw results, and aggregates them into summary statistics.

**For:** Researchers and engineers running optimization benchmarks who need consistent, auditable experiment execution across algorithm variants and problem instances.

**Solves:** Manual experiment orchestration — generating task grids, parallelizing runs, handling timeouts, normalizing solution evaluation, and aggregating results across runs.

## Goals

- Run N C++ optimization algorithm variants (with named parameter configurations) against instances from multiple datasets, X times each
- Execute runs in parallel using process pools
- Write raw per-run results into structured CSV files: `result_XXXX/<dataset>/<algorithm>/<instance>.csv`
- Aggregate raw results into a single summary statistics CSV with mean, std dev, min, max per metric
- Complete end-to-end smoke test in <60s with mock algorithms and minimal config

## Tech Stack

**Core:**

- Language: C++17 (algorithm binary), Python 3.10+ (orchestrator, validator, analysis)
- Build: g++ -O2 -std=c++17
- Parallelism: Python `concurrent.futures.ProcessPoolExecutor`
- Data: JSON (solution interchange), CSV (raw results + summary)

**Key dependencies:**

- Python standard library only (`json`, `csv`, `subprocess`, `concurrent.futures`, `pathlib`, `time`)
- g++ (C++17)

## Scope

**v1 includes:**

- C++ binary (`algo_runner`) with CLI interface and 2+ algorithm dispatch targets
- Python validator computing feasibility/objective/gap per solution
- Python orchestrator: config load, task generation, parallel execution, result collection
- Python analysis: raw CSV aggregation into summary statistics
- Config-driven experiment definitions (JSON)
- Result directory auto-naming (`result_0001`, `result_0002`, ...)
- Placeholder/scaffold algorithm logic (replaceable without changing I/O contracts)
- Placeholder validator logic (replaceable without changing I/O contracts)
- End-to-end smoke test with mock algorithms and minimal config

**Explicitly out of scope:**

| Feature | Reason |
| ------- | ------ |
| Real optimization algorithm implementations | Replaced after pipeline is verified; algorithm logic is a separate concern |
| GUI / dashboard | CLI-only pipeline; spreadsheet tools can consume CSV output |
| Distributed execution across machines | Single-machine process pool parallelism only |
| Database-backed result storage | File-based CSV storage; sufficient for experiment scale |
| Real-time experiment monitoring | Batch-mode execution only |

## Constraints

- Timeline: Sequential implementation with verification at each step (build plan Steps 0-6)
- Technical: C++ binary must not self-report execution time (orchestrator measures wall-clock externally)
- Resources: Single machine, no GPU, no cloud services
