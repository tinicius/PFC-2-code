# Optimization Experiment Runner

A minimal, reproducible experiment pipeline that runs C++ optimization algorithms against multiple datasets in parallel, captures raw results, and aggregates them into summary statistics.

## Project Structure

- `datasets/`
  Contains the problem instances organized by dataset (`a`, `b`, `x`).

- `project/algos/`
  C++ implementations of the algorithms (`grasp`, `simulated_annealing`), a `Makefile` for compilation, and the entry point dispatcher (`algo_runner.cpp`).

- `project/validator/`
  Contains the Python validation logic (`validator.py`) responsible for verifying optimization results.

- `project/orchestrator/`
  Contains the main runner script (`run_experiment.py`) that coordinates parallel execution of the algorithms and the experiment configuration (`config.json`).

- `project/analysis/`
  Contains `analyze_results.py`, which is used to parse and generate insights from the benchmark runs.

- `project/results/`
  The directory where execution outputs, generated CSV files, and copied configurations are stored for each unique experiment run (e.g., `result_0001/`).

## Execution Commands

### 1. Build the Algorithms
Before running any experiments, you must compile the C++ algorithm binaries using the provided `Makefile`.

```bash
cd project/algos
make
cd ../..
```

### 2. Run the Experiment
Once the binary is compiled, you can execute the parallel benchmark orchestrated by the Python script.

```bash
python3 project/orchestrator/run_experiment.py project/orchestrator/config.json
```

The script will automatically allocate background workers based on the configuration, dispatch the tasks, save JSON metrics from each algorithmic run, validate the results, and export per-instance CSV metrics into a uniquely incremented `project/results/result_XXXX/` directory.
