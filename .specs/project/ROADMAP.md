# Roadmap — Optimization Experiment Runner

## Milestones

### M1: Skeleton & CLI Binary (Steps 0-1)

- [ ] Step 0: Create empty directory structure (`algos/`, `validator/`, `orchestrator/`, `analysis/`, `data/`, `results/`) with placeholder files
- [ ] Step 1: Implement `algo_runner.cpp` with the specified CLI contract and 2 mock algorithms (`grasp`, `simulated_annealing`)
- [ ] Compile and smoke-test: verify JSON output format and exit codes (0, 1, 2)

**Verification:** `g++ -O2 -std=c++17 -o algos/algo_runner algos/algo_runner.cpp` builds clean; runs produce valid JSON; bad `--binary` returns exit code 2

### M2: Validation & Config (Steps 2-3)

- [ ] Step 2: Implement `validator.py` — importable `validate(instance_path, solution_path) -> dict` with placeholder feasibility/objective logic
- [ ] Step 3: Create `config.example.json` — minimal config (2 datasets, 2 algos, 3 runs, 4 workers)

**Verification:** Validator returns well-formed dict against mock output; config loads without error

### M3: Orchestrator (Step 4)

- [ ] Step 4: Implement `run_experiment.py` — config load, task generation, parallel execution via `ProcessPoolExecutor`, result collection, CSV output
- [ ] Handle 3 outcome categories: `feasible`, `timeout`, `error`
- [ ] Write `result_XXXX/<dataset>/<algorithm>/<instance>.csv` files

**Verification:** Run against `config.example.json` with mock `algo_runner`; verify output folder structure and CSV row counts

### M4: Analysis (Step 5)

- [ ] Step 5: Create `analyze_results.py` — reads raw CSVs, groups by (dataset, algorithm, instance), outputs summary stats CSV

**Verification:** `python analyze_results.py result_0001/ summary.csv` — row count matches `datasets * algorithms * instances`

### M5: End-to-End Smoke Test (Step 6)

- [ ] Step 6: Run full pipeline: compile → run_experiment → analyze_results
- [ ] Confirm complete in <60s with mock config
- [ ] Confirm valid summary.csv produced

### M6: Real Implementation (Post-Pipeline)

- [ ] Replace mock algorithm bodies with real C++ optimization logic
- [ ] Replace placeholder validator with real feasibility/objective rules
- [ ] Scale config to full 3 datasets (20+15+15 instances) and real run counts

## Feature List

| # | Feature | Milestone | Priority |
| - | ------- | --------- | -------- |
| 1 | Project skeleton | M1 | P1 |
| 2 | C++ algo binary with mock algorithms | M1 | P1 |
| 3 | Solution validator | M2 | P1 |
| 4 | Experiment config | M2 | P1 |
| 5 | Parallel orchestrator | M3 | P1 |
| 6 | Results analysis / aggregation | M4 | P2 |
| 7 | End-to-end smoke test | M5 | P2 |
| 8 | Real algorithm implementations | M6 | P3 |
| 9 | Real validator logic | M6 | P3 |
| 10 | Dataset scaling | M6 | P3 |
