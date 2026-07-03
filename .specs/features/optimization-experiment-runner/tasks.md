# Optimization Experiment Runner — Tasks

**Spec:** `.specs/features/optimization-experiment-runner/spec.md`
**Context:** `.specs/features/optimization-experiment-runner/context.md`
**Design:** `.specs/features/optimization-experiment-runner/design.md`
**Status:** Approved

---

## Execution Plan

### Phase 1: Skeleton (Sequential)

```
T1
```

### Phase 2: Independent Components (Parallel OK)

```
     ┌→ T2 ─┐
     │ T3    │
T1 ──┤ T4    ├──→ T5
     │ T6    │
     └───────┘
```

### Phase 3: Integration (Sequential)

```
T5 → T7
```

---

## Task Breakdown

### T1: Create project skeleton directories and placeholder files

**What**: Create empty directories: `project/algos/`, `project/validator/`, `project/orchestrator/`, `project/analysis/`, `project/results/`. Placeholder `.gitkeep` in each.

**Where**: `project/` tree

**Depends on**: None

**Requirement**: None (infrastructure)

**Done when**:

- [ ] All 6 directories exist
- [ ] Each directory has at least a `.gitkeep` placeholder

**Verify**:
```bash
ls -R project/
```

---

### T2: Implement `algo_runner.cpp` with CLI contract and exec_time

**What**: C++17 binary that:
- Parses CLI args: `--binary`, `--input`, `--params`, `--time-limit`, `--seed`, `--output`
- Dispatches to `grasp` or `simulated_annealing` by name (unknown → exit 2)
- Parses instance content from `--input` string (no file reads)
- Starts timer after parsing, algorithm runs, stops timer before JSON write
- Mock algorithm sleeps ~200ms, returns `{"selected_orders":[],"visited_aisles":[],"exec_time":<elapsed>}`
- Writes solution JSON to `--output` path
- Exit codes: 0=success, 1=bad/missing args, 2=unknown binary

**Where**: `project/algos/algo_runner.cpp`

**Depends on**: T1

**Requirement**: ALGO-01, ALGO-02, ALGO-03, ALGO-04, ALGO-05, ALGO-06

**Done when**:

- [ ] Compiles clean: `g++ -O2 -std=c++17 -o project/algos/algo_runner project/algos/algo_runner.cpp`
- [ ] Valid run produces JSON with `exec_time`: `./project/algos/algo_runner --binary=grasp --input='61 155 116' --params='{}' --time-limit=5 --seed=1 --output=/tmp/out.json && cat /tmp/out.json | python -c "import sys,json; d=json.load(sys.stdin); assert 'exec_time' in d"`
- [ ] Unknown binary: exit code 2
- [ ] Missing args: exit code 1

**Verify**:
```bash
g++ -O2 -std=c++17 -o project/algos/algo_runner project/algos/algo_runner.cpp
./project/algos/algo_runner --binary=grasp --input='61 155 116' --params='{}' --time-limit=5 --seed=1 --output=/tmp/out.json
cat /tmp/out.json
echo "Exit code: $?"
```

---

### T3: Implement `validator.py` with placeholder logic

**What**: Python module with `validate(instance_path, solution_path) -> dict` returning `{"status": "feasible", "objective": 0.0, "gap": 0.0, "items": 0, "aisles": 0}`. Runnable as CLI: `python validator/validator.py <instance> <solution>` → prints JSON to stdout.

**Where**: `project/validator/validator.py`

**Depends on**: T1

**Requirement**: VAL-01, VAL-02, VAL-03, VAL-04

**Done when**:

- [ ] CLI mode prints valid JSON with all 5 keys
- [ ] Importable as module: `from validator import validate; validate(path, path)`

**Verify**:
```bash
python project/validator/validator.py datasets/examples/example.txt /tmp/out.json
```

---

### T4: Create `config.example.json`

**What**: Minimal experiment config.

```json
{
  "datasets": {
    "a": ["instance_0001.txt", "instance_0002.txt"],
    "b": ["instance_0001.txt"]
  },
  "algorithms": [
    {"id": "grasp", "binary": "grasp", "params": {}},
    {"id": "sa", "binary": "simulated_annealing", "params": {}}
  ],
  "runs_per_instance": 3,
  "time_limit": 10,
  "n_workers": 4
}
```

**Where**: `project/orchestrator/config.example.json`

**Depends on**: T1

**Requirement**: None (test config)

**Done when**:

- [ ] File is valid JSON
- [ ] References 2 datasets, 2 algos, `runs_per_instance=3`, `time_limit=10`, `n_workers=4`

**Verify**:
```bash
python -c "import json; json.load(open('project/orchestrator/config.example.json'))"
```

---

### T5: Implement `run_experiment.py` orchestrator

**What**: Python script that:
1. Loads config JSON
2. Scans `results/` for next available `result_XXXX` dir
3. Copies config into result dir
4. Builds task list from Cartesian product
5. Executes via `ProcessPoolExecutor(max_workers=n_workers)`
6. Each worker: reads instance file → passes via `--input` → subprocess → timeout/error/validation → extracts `exec_time` from solution JSON
7. Main process collects results in memory
8. Writes per-instance CSVs (grouped, one file per instance)
9. Prints summary

**Where**: `project/orchestrator/run_experiment.py`

**Depends on**: T2, T3, T4

**Requirements**: ORCH-01 through ORCH-09

**Done when**:

- [ ] `python project/orchestrator/run_experiment.py project/orchestrator/config.example.json` exits 0
- [ ] Creates `project/results/result_0001/` with `config.json` copy
- [ ] Creates CSVs with correct paths and row counts
- [ ] Prints summary line

**Verify**:
```bash
python project/orchestrator/run_experiment.py project/orchestrator/config.example.json
ls -R project/results/
```

---

### T6: Create empty `analyze_results.py`

**What**: Empty placeholder file, valid Python syntax.

**Where**: `project/analysis/analyze_results.py`

**Depends on**: T1

**Requirement**: None (placeholder)

**Done when**:

- [ ] File exists, valid Python syntax

**Verify**:
```bash
python -c "import py_compile; py_compile.compile('project/analysis/analyze_results.py', doraise=True)"
```

---

### T7: End-to-end smoke test

**What**: Run full pipeline: compile → run_experiment → verify output.

**Where**: N/A (verification only)

**Depends on**: T2, T5, T6

**Requirements**: E2E-01, E2E-02, E2E-03

**Done when**:

- [ ] `g++` exits 0
- [ ] `run_experiment.py` exits 0
- [ ] `project/results/result_0001/` has correct structure
- [ ] Completes in < 60 seconds

**Verify**:
```bash
time (
  g++ -O2 -std=c++17 -o project/algos/algo_runner project/algos/algo_runner.cpp \
  && python project/orchestrator/run_experiment.py project/orchestrator/config.example.json
)
find project/results/ -type f | sort
head -5 $(find project/results/ -name '*.csv' | head -1)
```

---

## Requirement-to-Task Mapping

| ID | Task | Status |
| -- | ---- | ------ |
| ALGO-01 through ALGO-06 | T2 | Pending |
| VAL-01 through VAL-04 | T3 | Pending |
| ORCH-01 through ORCH-09 | T5 | Pending |
| E2E-01 through E2E-03 | T7 | Pending |

**Coverage**: 21/21 mapped ✅

---

## Task Granularity Check

| Task | Scope | Status |
| ---- | ----- | ------ |
| T1 | 6 dirs + placeholders | ✅ |
| T2 | 1 C++ binary (single file) | ✅ |
| T3 | 1 Python module | ✅ |
| T4 | 1 JSON file | ✅ |
| T5 | 1 Python script | ✅ |
| T6 | 1 empty file | ✅ |
| T7 | Verification only | ✅ |

## Cross-Check (Diagram vs Dependencies)

| Task | Depends On | Diagram | Status |
| ---- | ---------- | ------- | ------ |
| T1 | None | root | ✅ |
| T2 | T1 | T1→T2 | ✅ |
| T3 | T1 | T1→T3 | ✅ |
| T4 | T1 | T1→T4 | ✅ |
| T5 | T2,T3,T4 | T2/T3/T4→T5 | ✅ |
| T6 | T1 | T1→T6 | ✅ |
| T7 | T2,T5,T6 | T5/T6→T7 | ✅ |
