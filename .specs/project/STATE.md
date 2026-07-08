# State — Optimization Experiment Runner

## Decisions

| ID | Decision | Rationale | Date |
| -- | -------- | --------- | ---- |
| D001 | C++ binary uses CLI args (not stdin) for all parameters | Consistent with standard solver interfaces; easier to debug and log | 2026-07-03 |
| D017 | Implementar algoritmos SPO em Java (andre_feijo) em vez de C++ | O código Java com CPLEX já está parcialmente implementado; migrar para C++ seria redundante no contexto do TCC | 2026-07-06 |
| D018 | Feature spec `algoritmos-descritivos` cobre par-it, ref-lin, ItRL e TSHeuristic | Todos os 4 métodos do artigo precisam ser auditados e completados antes de integrar ao pipeline pfc2 | 2026-07-06 |
| D002 | Algo_runner measures internal processing time, not orchestrator wall-clock | Excludes file I/O and instance loading from timing; user requirement | 2026-07-03 |
| D003 | Instance content passed via `--input` string arg, not file path | Removes file I/O from C++ binary entirely; orchestrator owns all I/O | 2026-07-03 |
| D004 | Validator is a Python module, not C++ | Faster iteration for evaluation logic changes; single source of truth | 2026-07-03 |
| D005 | Results are buffered in memory and written once at end | Avoids concurrent-write corruption; fine for experiment scales <10K runs | 2026-07-03 |
| D006 | Use existing datasets/a/b/x directly | No duplication; datasets already in the repo | 2026-07-03 |
| D007 | Orchestrator verbosity: start message + final summary only | Minimal but informative; no per-task output clutter | 2026-07-03 |
| D008 | Always write all CSVs and exit 0, even on 100% failure | Experiment "succeeded" in running; data shows failures | 2026-07-03 |
| D009 | Skip missing instances with warning, continue remaining tasks | Partial results are better than abort | 2026-07-03 |
| D010 | Minimal config validation (valid JSON + top-level keys only) | Less code; user is primary consumer | 2026-07-03 |
| D011 | 5-second subprocess grace period beyond time_limit | Generous for slow I/O or large solution writes | 2026-07-03 |
| D012 | Temp solution files stay in /tmp after experiment | Small files; useful for debugging | 2026-07-03 |
| D013 | SIGINT/SIGTERM handler kills children and exits cleanly | No partial result dirs; clean shutdown | 2026-07-03 |
| D014 | Use n_workers as-is, no CPU count capping | User knows their system | 2026-07-03 |
| D015 | Temp solution files via tempfile.mkstemp() in system /tmp | Auto-unique filenames; simple | 2026-07-03 |
| D016 | CSV row order: completion order (no sorting) | Row order not functionally important | 2026-07-03 |

## Blockers

*None yet*

## Lessons

*None yet*

## Todos

- [x] Create feature spec with requirement IDs
- [x] Proceed to Design phase — design.md created
- [x] Proceed to Tasks phase — tasks.md created, 11 atomic tasks (T1-T11)
- [ ] Execute T1: Create C++ Project Skeleton & CMake
- [ ] Execute T2: Implement Instance Parsing & I/O
- [ ] Execute T3: Implement Preprocessing Reductions
- [ ] Execute T4: Implement F(H) MILP Solver (`solve_f`)
- [ ] Execute T5: Implement Iterative Parallel Method (`par-it`)
- [ ] Execute T6: Implement Linear Reformulation (`ref-lin`)
- [ ] Execute T7: Implement Binary Search (Fase 1 Tabu)
- [ ] Execute T8: Implement Hybrid Method (`ItRL`)
- [ ] Execute T9: Implement Tabu Search (`tabu_search`)
- [ ] Execute T10: Implement CLI Dispatcher (`main.cpp`)
- [ ] Execute T11: Integrate with Python Orchestrator

## Deferred Ideas

- Configurable logging levels for orchestrator
- Checkpoint/resume for interrupted experiments
- Per-algorithm time limits (different from global)
- Git-commit hash embedding in result directories for traceability
