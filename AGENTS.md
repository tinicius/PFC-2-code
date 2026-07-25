# Directory Structure Guide for Agents

This document explains the purpose of all major directories in the `pfc2` repository to help AI agents navigate and understand the codebase.

## Top-Level Directories

- **`.specs/`**: Contains specifications and design documents for project features and tasks.
- **`.venv/`**: Python virtual environment used for running orchestrator scripts, validation, and Jupyter notebooks.
- **`.vscode/`**: Contains Visual Studio Code workspace settings and configurations.
- **`best_solutions/`**: Stores the best known solutions found for the problem instances, likely used for reference or comparison.
- **`datasets/`**: Contains the problem instances organized by dataset groups (`a`, `b`, `x`, and `examples`). 
- **`docs/`**: Documentation directory containing algorithm descriptions (e.g., `ILS.md`, `SA.md`, `AisleBased.md`, `SimpleHeuristic.md`) and the problem description PDF (`pt_problem_description.pdf`).
- **`irace/`**: Contains the configuration files (`scenario.txt`, `parameters.txt`), and execution scripts (`target-runner`) required to run the `irace` package for automatic algorithm parameter tuning.
- **`notebooks/`**: Contains Jupyter Notebooks used for data analysis, aggregating experiment results, and generating visualizations (e.g., Pareto frontiers, comparative tables).
- **`skills/`**: Contains AI assistant skill definitions (e.g., `tlc-spec-driven`) used to guide agentic behaviors and structured feature planning in the repository.

## Project Subdirectories (`project/`)

The `project/` directory contains the core source code, orchestration logic, and outputs for the optimization experiments.

- **`project/algos_java/`**: The main Java source code for the optimization algorithms. Includes subdirectories for specific implementations and components:
  - `andre_feijo/`: Specific algorithm implementations.
  - `constructive/`: Constructive heuristic components.
  - `heuristic/`: Metaheuristic implementations (e.g., Simulated Annealing, Iterated Local Search, Variable Neighborhood Search).
  - `model/`: Data models and structures representing the problem.
  - `neighborhood/`: Neighborhood structures used for local search.
  - `bin/` & `classes/`: Compiled Java bytecode.
- **`project/orchestrator/`**: Python scripts (such as `run_experiment.py`) and configuration files (`config.json`, `config_andre_feijo.json`) responsible for coordinating the parallel execution of experiments.
- **`project/out/`**: Temporary output directory, often used for intermediate files during execution.
- **`project/results/`**: The directory where execution metrics, CSV files, configuration copies, and solver outputs are permanently stored for each experimental run (e.g., `result_0001/`, `result_0002/`).
- **`project/validator/`**: Contains Python validation scripts (`validator.py`) used to verify the correctness and feasibility of the solutions produced by the optimization algorithms.
