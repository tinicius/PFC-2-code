# Algoritmos SPO Tasks

**Design**: `.specs/features/algoritmos-descritivos/design.md`
**Status**: Draft

---

## Execution Plan

### Phase 1: Foundation (Sequential)
Tasks that setup the project, I/O, and preprocessing logic.

```
T1 → T2 → T3
```

### Phase 2: MILP Core (Sequential)
The central solver for F(H) which the other methods rely on.

```
T3 → T4
```

### Phase 3: Base Methods (Parallel OK)
The individual base algorithms.

```
     ┌→ T5 [P] ─┐
T4 ──┼→ T6 [P] ─┼──→ T8
     └→ T7 [P] ─┘
```

### Phase 4: Composed Methods (Parallel OK)
Methods that orchestrate the base methods.

```
T5 ─┐
T6 ─┴─→ T8 [P]

T7 ───→ T9 [P]
```

### Phase 5: Integration (Sequential)
CLI entrypoint and Python runner integration.

```
T8, T9 → T10 → T11
```

---

## Task Breakdown

### T1: Create C++ Project Skeleton & CMake

**What**: Set up the `project/algos/spo/` directory structure and `CMakeLists.txt` fetching OR-Tools.
**Where**: `project/algos/spo/CMakeLists.txt`
**Depends on**: None
**Reuses**: None
**Requirement**: SPO-01

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] Directory structure `include/`, `src/` created.
- [ ] `CMakeLists.txt` configures C++17 and uses `FetchContent` (or `find_package`) for OR-Tools.
- [ ] Minimal `main.cpp` compiles successfully via CMake.

**Tests**: none
**Gate**: build

---

### T2: Implement Instance Parsing & I/O

**What**: Parse instance files and write solutions in the exact challenge format.
**Where**: `project/algos/spo/src/instance.cpp`, `include/instance.hpp`
**Depends on**: T1
**Reuses**: `andre_feijo/src/main/java/org/sbpo2025/challenge/Instance.java`
**Requirement**: SPO-01

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] `Instance` struct defined with sparse representation (`unordered_map`).
- [ ] Inverse indices (`items_per_order`, `items_per_aisle`) populated.
- [ ] `Q` and `D` vectors pre-computed.
- [ ] `write_solution` formats output exactly like `Instance.java`.

**Tests**: none
**Gate**: quick

---

### T3: Implement Preprocessing Reductions

**What**: Implement the 6 preprocessing rules from the article (Section 7).
**Where**: `project/algos/spo/src/preprocessing.cpp`, `include/preprocessing.hpp`, `src/max_subset_sum.cpp`
**Depends on**: T2
**Reuses**: `Instance.getInstanceInfo()` and `MaxSubsetSum.java` from Java repo
**Requirement**: SPO-02 to SPO-07

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] Unfeasible orders and redundant unit orders removed.
- [ ] Supply caps and MaxSubsetSum applied to `Q` and `q_{i,a}`.
- [ ] Easy items and dominated aisles correctly identified.
- [ ] Returns `PreprocResult` object with boolean flags.

**Tests**: none
**Gate**: quick

---

### T4: Implement F(H) MILP Solver (`solve_f`)

**What**: Implement the core `solve_f` using OR-Tools CP-SAT.
**Where**: `project/algos/spo/src/solve_f.cpp`, `include/solve_f.hpp`
**Depends on**: T3
**Reuses**: `ItModel.java` logic
**Requirement**: SPO-08 to SPO-10

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] CP-SAT model built with constraints (22)-(26).
- [ ] Easy items use binary constraints instead of supply constraints.
- [ ] `SolveFExtras` (forced/forbidden aisles, LB/UB overrides) respected.
- [ ] Gracefully handles time limits and infeasibility without crashing.

**Tests**: none
**Gate**: build

---

### T5: Implement Iterative Parallel Method (`par-it`) [P]

**What**: Implement the `par-it` algorithm with ASCENDENT/DESCENDENT threads.
**Where**: `project/algos/spo/src/par_it.cpp`, `include/par_it.hpp`
**Depends on**: T4
**Reuses**: `ParallelIterative.java`
**Requirement**: SPO-11 to SPO-15

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] Spawns two `std::thread`s processing `solve_f` calls.
- [ ] Dynamically updates LB using `atomic` variables across threads.
- [ ] Reduces search interval and uses `β` blocks for descending.
- [ ] Correctly identifies optimality (when threads cross).

**Tests**: none
**Gate**: build

---

### T6: Implement Linear Reformulation (`ref-lin`) [P]

**What**: Implement the continuous `ref-lin` model using OR-Tools MPSolver (SCIP/CBC).
**Where**: `project/algos/spo/src/ref_lin.cpp`, `include/ref_lin.hpp`
**Depends on**: T4
**Reuses**: `RefLinFractional.java`
**Requirement**: SPO-16 to SPO-18

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] Variables `u`, `t_o`, `g_a` declared.
- [ ] Linearization constraints (14)-(19) implemented.
- [ ] Supports interval restrictions `h_min <= sum(y) <= h_max` for hybrid mode.
- [ ] Integrates incumbent lower bound.

**Tests**: none
**Gate**: build

---

### T7: Implement Binary Search (Fase 1 Tabu) [P]

**What**: Implement the `bsearch` component (ASCENDENT + Binary Search DESCENDENT).
**Where**: `project/algos/spo/src/bsearch.cpp`, `include/bsearch.hpp`
**Depends on**: T4
**Reuses**: `BSearch.java`
**Requirement**: SPO-22

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] Descendent thread searches for `H` via binary search instead of linear steps.
- [ ] BSearch state returned (minAisles, maxAisles) for Tabu Phase 2.

**Tests**: none
**Gate**: build

---

### T8: Implement Hybrid Method (`ItRL`) [P]

**What**: Orchestrate `par-it` followed by `ref-lin`.
**Where**: `project/algos/spo/src/hybrid.cpp`, `include/hybrid.hpp`
**Depends on**: T5, T6
**Reuses**: `ChallengeSolver.java` (ItRL case)
**Requirement**: SPO-19 to SPO-21

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] Executes `par_it` with `partial_time_s`.
- [ ] If not optimal, executes `ref_lin` in the remaining `[h_asc, h_desc]` interval.
- [ ] Returns the best solution between both.

**Tests**: none
**Gate**: build

---

### T9: Implement Tabu Search (`tabu_search`) [P]

**What**: Implement Tabu Phase 2 evaluating `mv1` and `mv2` concurrently.
**Where**: `project/algos/spo/src/tabu_search.cpp`, `include/tabu_search.hpp`
**Depends on**: T7
**Reuses**: `TSHeuristic.java`, `NgbrModel.java`
**Requirement**: SPO-23 to SPO-25

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] Starts from `bsearch` best solution.
- [ ] Spawns two threads evaluating `solve_f` (one adding an aisle, one removing).
- [ ] Maintains tabu list and respects `TABU_LOCK`.
- [ ] Stops after `MAX_NO_IMPRV_ITS` or time limit.

**Tests**: none
**Gate**: build

---

### T10: Implement CLI Dispatcher (`main.cpp`)

**What**: Handle CLI arguments and dispatch to the requested method.
**Where**: `project/algos/spo/src/main.cpp`
**Depends on**: T8, T9
**Reuses**: None
**Requirement**: SPO-01

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] Parses `--input`, `--output`, `--method`, `--time-limit`, `--threads`, etc.
- [ ] Calls appropriate method and handles outputs/errors cleanly.
- [ ] Exits with code 0 on success, 1 on infeasible, 2 on errors.

**Tests**: none
**Gate**: build

---

### T11: Integrate with Python Orchestrator

**What**: Add the `spo` C++ binary to `config.json` and ensure it plays nicely with `run_experiment.py`.
**Where**: `project/config.json`, `project/orchestrator/`
**Depends on**: T10
**Reuses**: None
**Requirement**: SPO-26, SPO-27

**Tools**:
- MCP: `filesystem`
- Skill: NONE

**Done when**:
- [ ] Binary execution works via `subprocess` in Python.
- [ ] C++ solver replaces `algo_runner.cpp` mock in the benchmark.
- [ ] Timeout and error handling correctly maps C++ exit codes to CSV status.

**Tests**: none
**Gate**: build

---

## Diagram-Definition Cross-Check

| Task | Depends On (task body) | Diagram Shows | Status |
| ---- | ---------------------- | ------------- | ------ |
| T1 | None | None | ✅ Match |
| T2 | T1 | T1 | ✅ Match |
| T3 | T2 | T2 | ✅ Match |
| T4 | T3 | T3 | ✅ Match |
| T5 | T4 | T4 | ✅ Match |
| T6 | T4 | T4 | ✅ Match |
| T7 | T4 | T4 | ✅ Match |
| T8 | T5, T6 | T5, T6 | ✅ Match |
| T9 | T7 | T7 | ✅ Match |
| T10 | T8, T9 | T8, T9 | ✅ Match |
| T11 | T10 | T10 | ✅ Match |

## Test Co-location Validation

Since `TESTING.md` does not exist and C++ algorithms are usually verified by checking correctness via end-to-end runs (using `checker.py` externally rather than C++ unit tests at this phase), tests are marked `none` and gate is `build`/`quick` (compilation). This is valid.

| Task | Code Layer Created/Modified | Matrix Requires | Task Says | Status |
| ---- | --------------------------- | --------------- | --------- | ------ |
| All | C++ Algorithms | N/A (no matrix) | none | ✅ OK |

---
