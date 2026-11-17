import sys
import json
import os
import shutil
import subprocess
import traceback
import argparse
import csv
import time
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from collections import defaultdict, Counter
from datetime import datetime

# The validator is the feasibility gate of the whole experiment: if it cannot be
# imported, every run would be reported as unchecked. Fail loudly instead.
sys.path.append(os.path.join(os.path.dirname(__file__), "..", "validator"))
import validator  # noqa: E402

# Extra wall-clock seconds tolerated on top of the configured time limit before a
# run is flagged as having overrun it. Covers JVM startup, instance parsing and
# solution writing, which sit outside the solver's own timer.
DEFAULT_TIME_GRACE = 15.0

# Location of the instance files. Module-level so tests can point it elsewhere.
DATASETS_DIR = Path(__file__).resolve().parent.parent.parent / "datasets"

JAVA_BIN = "/usr/lib/jvm/java-21-openjdk-amd64/bin/java"
JAVA_DIR = "project/algos_java"
CPLEX_LIB_PATH = "/opt/ibm/ILOG/CPLEX_Studio222/cplex/bin/x86-64_linux"

# Fields written to the per-instance CSV files.
CSV_FIELDS = [
    "algo_id", "run_id", "seed", "status", "reason",
    "objective", "items", "aisles", "exec_time", "wall_time", "time_ok",
    "returncode",
]


def build_command(algo, instance_path, solution_path, time_limit, seed):
    """Build the solver command line for one run."""
    algo_name = algo.get('binary', 'sa')

    if algo_name == "andre_feijo":
        jar_path = os.path.join(JAVA_DIR, "andre_feijo", "target",
                                "ChallengeSBPO2025-1.0.jar")
        # andre_feijo accepts an optional 3rd arg as the seed (long). It takes no
        # time-limit argument: its budget is hardcoded in ChallengeSolver.
        return [
            JAVA_BIN,
            "-Xmx8g",
            f"-Djava.library.path={CPLEX_LIB_PATH}",
            "-jar", jar_path,
            instance_path,
            solution_path,
            str(seed),
        ]

    return [
        JAVA_BIN, "-cp", JAVA_DIR, "Main",
        f"--input={instance_path}",
        f"--output={solution_path}",
        f"--time-limit={time_limit}",
        f"--seed={seed}",
        f"--algo={algo_name}",
        f"--params={json.dumps(algo.get('params', {}))}",
    ]


def worker(task):
    dataset = task['dataset']
    instance = task['instance']
    run_id = task['run_id']
    algo = task['algo']
    time_limit = task['time_limit']
    time_grace = task['time_grace']
    result_dir = task['result_dir']

    instance_path = str(DATASETS_DIR / dataset / instance)

    solution_path = os.path.join(result_dir, "temp", f"{dataset}_{instance}_{algo['id']}_{run_id}.json")

    # Seeds come from seeds.json only: an implicit random fallback would break
    # reproducibility silently.
    seed = task['seed']

    cmd = build_command(algo, instance_path, solution_path, time_limit, seed)

    metrics = {
        "algo_id": algo['id'],
        "run_id": run_id,
        "seed": seed,
        "status": "error",
        "reason": "not_run",
        "objective": 0.0,
        "items": 0,
        "aisles": 0,
        "exec_time": 0.0,
        "wall_time": 0.0,
        "time_ok": True,
        "returncode": None,
    }
    record = {
        "dataset": dataset,
        "instance": instance,
        "algo_id": algo['id'],
        "run_id": run_id,
        "seed": seed,
        "cmd": cmd,
        "time_limit": time_limit,
        "violations": [],
        "returncode": None,
        "stdout_tail": "",
        "stderr_tail": "",
    }

    proc = None
    started = time.perf_counter()
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=time_limit + 30)
        metrics["wall_time"] = round(time.perf_counter() - started, 3)
        metrics["returncode"] = proc.returncode
        record["returncode"] = proc.returncode
        if proc.stdout:
            record["stdout_tail"] = proc.stdout[-2000:]
        if proc.stderr:
            record["stderr_tail"] = proc.stderr[-2000:]
            print(proc.stderr, file=sys.stderr)

        if proc.returncode != 0:
            metrics["reason"] = f"exit_code_{proc.returncode}"

        if not os.path.exists(solution_path):
            metrics["status"] = "error"
            metrics["reason"] = "no_output_file"
            record["violations"] = ["solver produced no solution file"]
        else:
            # Wall-clock time is measured here, outside the solver, because the
            # solver's own exec_time excludes JVM startup and instance parsing.
            val_res = validator.validate(instance_path, solution_path)
            record["violations"] = val_res.get("violations", [])
            metrics["status"] = val_res["status"]
            metrics["reason"] = val_res.get("reason", "")
            metrics["objective"] = val_res["objective"]
            metrics["items"] = val_res["items"]
            metrics["aisles"] = val_res["aisles"]

            # exec_time as self-reported by the solver, kept for reference only.
            try:
                with open(solution_path, "r") as f:
                    sol = json.load(f)
                if isinstance(sol, dict) and isinstance(sol.get("exec_time"), (int, float)):
                    metrics["exec_time"] = float(sol["exec_time"])
            except (OSError, ValueError):
                pass

    except subprocess.TimeoutExpired as e:
        metrics["wall_time"] = round(time.perf_counter() - started, 3)
        metrics["status"] = "error"
        metrics["reason"] = "timeout"
        record["violations"] = [f"process killed after {e.timeout}s"]
        print(f"[TIMEOUT] {dataset}/{instance} | algo={algo['id']} run={run_id} seed={seed}",
              file=sys.stderr)
    except Exception as e:
        metrics["wall_time"] = round(time.perf_counter() - started, 3)
        metrics["status"] = "error"
        metrics["reason"] = f"exception_{type(e).__name__}"
        record["violations"] = [f"{type(e).__name__}: {e}"]
        sep = "=" * 60
        print(f"\n{sep}", file=sys.stderr)
        print(f"[ERROR] {dataset}/{instance} | algo={algo['id']} run={run_id} seed={seed}", file=sys.stderr)
        print(f"  Exception type : {type(e).__name__}", file=sys.stderr)
        print(f"  Exception msg  : {e}", file=sys.stderr)
        print(f"  Command        : {' '.join(cmd)}", file=sys.stderr)
        print("  Traceback:", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        if proc is not None and proc.stderr:
            print("  Java stderr output:", file=sys.stderr)
            print(proc.stderr, file=sys.stderr)
        if proc is not None and proc.stdout:
            print("  Java stdout output:", file=sys.stderr)
            print(proc.stdout, file=sys.stderr)
        print(sep, file=sys.stderr)

    # A run that used more budget than its competitors is not a comparable
    # observation; flagged separately from feasibility so the analysis decides.
    metrics["time_ok"] = metrics["wall_time"] <= time_limit + time_grace

    # Keep an extra copy of the solution file of every run that is not certified
    # feasible, so failures stand out instead of being mixed with valid runs in
    # temp/.
    if metrics["status"] != "feasible" and os.path.exists(solution_path):
        failed_dir = os.path.join(result_dir, "failed_solutions")
        os.makedirs(failed_dir, exist_ok=True)
        shutil.copy(solution_path, os.path.join(failed_dir, os.path.basename(solution_path)))

    record.update({k: metrics[k] for k in metrics})
    return seed, dataset, instance, metrics, record

def load_seeds(seeds_path: Path) -> dict:
    """Load the deterministic seed map from seeds.json."""
    if not seeds_path.exists():
        raise FileNotFoundError(
            f"seeds.json not found at {seeds_path}. "
            "Run generate_seeds.py to create it."
        )
    with open(seeds_path, "r") as f:
        return json.load(f)


def validate_config(config):
    """Fail fast on configuration errors that would silently corrupt results.

    Returns a list of problem descriptions; empty means the config is usable.
    Checked here rather than discovered mid-run, because a typo in an instance
    name would otherwise surface as a solver 'error' row and be mistaken for a
    solver failure.
    """
    problems = []

    for key in ("datasets", "algorithms"):
        if key not in config:
            problems.append(f"missing required config key '{key}'")
    if problems:
        return problems

    if not config["algorithms"]:
        problems.append("'algorithms' is empty")

    ids = [algo.get("id") for algo in config["algorithms"]]
    for i, algo_id in enumerate(ids):
        if not algo_id:
            problems.append(f"algorithms[{i}] has no 'id'")
    duplicates = sorted({i for i in ids if i and ids.count(i) > 1})
    if duplicates:
        problems.append(
            f"duplicate algorithm id(s) {duplicates}: their runs would be "
            f"indistinguishable in the results"
        )

    for dataset, instances in config["datasets"].items():
        if not instances:
            problems.append(f"dataset '{dataset}' lists no instance")
        dups = sorted({i for i in instances if instances.count(i) > 1})
        if dups:
            problems.append(f"dataset '{dataset}' lists {dups} more than once")
        for instance in instances:
            if not (DATASETS_DIR / dataset / instance).exists():
                problems.append(
                    f"instance file not found: {DATASETS_DIR / dataset / instance}"
                )

    time_limit = config.get("time_limit", 10)
    if not isinstance(time_limit, (int, float)) or time_limit <= 0:
        problems.append(f"time_limit must be a positive number, got {time_limit!r}")

    n_workers = config.get("n_workers", 4)
    if not isinstance(n_workers, int) or n_workers < 1:
        problems.append(f"n_workers must be a positive integer, got {n_workers!r}")

    return problems


def collect_provenance(config, result_dir, n_workers):
    """Record what produced these results, so a number can be traced back.

    Without this, a results directory cannot be tied to the code revision that
    generated it, which makes any reported figure unreproducible.
    """
    def run(cmd, stream="stdout"):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
            if out.returncode != 0:
                return None
            text = out.stdout if stream == "stdout" else out.stderr
            return text.strip()
        except (OSError, subprocess.SubprocessError):
            return None

    dirty = run(["git", "status", "--porcelain"])

    return {
        "started_at": datetime.now().isoformat(timespec="seconds"),
        "result_dir": result_dir,
        "git_commit": run(["git", "rev-parse", "HEAD"]),
        "git_branch": run(["git", "rev-parse", "--abbrev-ref", "HEAD"]),
        "git_dirty": bool(dirty) if dirty is not None else None,
        "python_version": sys.version.split()[0],
        # `java -version` reports on stderr.
        "java_version": run([JAVA_BIN, "-version"], stream="stderr"),
        "hostname": os.uname().nodename,
        "cpu_count": os.cpu_count(),
        "n_workers": n_workers,
        "time_limit": config.get("time_limit", 10),
        "time_grace": config.get("time_grace", DEFAULT_TIME_GRACE),
        "runs_per_instance": config.get("runs_per_instance", 1),
        "algorithms": [a.get("id") for a in config["algorithms"]],
    }


def get_seed(seeds: dict, dataset: str, instance: str, run_id: int) -> int:
    """Return the pre-defined seed for (dataset, instance, run_id).
    Raises KeyError if the combination is not in the map.
    """
    try:
        return seeds[dataset][instance][str(run_id)]
    except KeyError:
        raise KeyError(
            f"No seed defined for ({dataset}, {instance}, run_id={run_id}). "
            "Re-run generate_seeds.py to extend seeds.json."
        )


def main():
    parser = argparse.ArgumentParser(description="Run experiment from a JSON config.")
    parser.add_argument("config", help="Path to the experiment config JSON file.")
    parser.add_argument(
        "--first-run-id",
        type=int,
        default=1,
        dest="first_run_id",
        help="First run_id to execute (1-indexed, default: 1). "
             "Useful to resume or extend an experiment without re-running earlier runs.",
    )
    args = parser.parse_args()

    config_path = args.config
    first_run_id = args.first_run_id

    with open(config_path, "r") as f:
        config = json.load(f)

    # The runner uses paths relative to the repository root (javac, results).
    if not os.path.exists(os.path.join(JAVA_DIR, "Main.java")):
        print(f"ERROR: run this script from the repository root; "
              f"'{JAVA_DIR}/Main.java' is not visible from {os.getcwd()}.",
              file=sys.stderr)
        sys.exit(1)

    problems = validate_config(config)
    if problems:
        print("ERROR: invalid experiment config "
              f"({config_path}):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        sys.exit(1)

    # Load deterministic seed table
    seeds_path = Path(__file__).resolve().parent / "seeds.json"
    seeds = load_seeds(seeds_path)

    results_base = os.path.join("project", "results")
    existing_dirs = [d for d in os.listdir(results_base) if d.startswith("result_")]
    next_id = 1
    if existing_dirs:
        ids = [int(d.split("_")[1]) for d in existing_dirs if d.split("_")[1].isdigit()]
        if ids:
            next_id = max(ids) + 1

    result_dir = os.path.join(results_base, f"result_{next_id:04d}")
    os.makedirs(result_dir, exist_ok=True)
    
    temp_dir = os.path.join(result_dir, "temp")
    os.makedirs(temp_dir, exist_ok=True)
    
    shutil.copy(config_path, os.path.join(result_dir, "config.json"))

    # Compile Java code if needed
    java_dir = "project/algos_java"
    main_class = os.path.join(java_dir, "Main.class")
    # if not os.path.exists(main_class):
    print("Compiling Java code...")
    subprocess.run(
        ["/usr/lib/jvm/java-21-openjdk-amd64/bin/javac", "-d", ".",
            "Main.java",
            "heuristic/Heuristic.java", "heuristic/IlsVnd.java",
            "model/Problem.java", "model/Solution.java",
            "util/Ranking.java", "util/AislePruning.java",
            "neighborhood/Move.java", "neighborhood/AddAisle.java", "neighborhood/RemoveAisle.java",
            "neighborhood/SwapAisle.java", "neighborhood/SwapOrder.java", 
            "neighborhood/AddOrder.java", "neighborhood/RemoveOrder.java",
            "neighborhood/PruneAisles.java", "neighborhood/RemoveAisleBest.java",
            "neighborhood/AddAisleBest.java",
            "constructive/AisleBased.java", "constructive/OrderBased.java"],
        cwd=java_dir, check=True
    )

    has_andre = any(algo.get('binary') == 'andre_feijo' for algo in config['algorithms'])
    if has_andre:
        print("Compiling andre_feijo code (maven)...")
        try:
            env = os.environ.copy()
            env["JAVA_HOME"] = "/usr/lib/jvm/java-21-openjdk-amd64"
            subprocess.run(["mvn", "clean", "package"], cwd=os.path.join(java_dir, "andre_feijo"), env=env, check=True)
        except FileNotFoundError:
            print("ERROR: Maven ('mvn') is not installed or not in PATH.")
            print("Please install Maven to compile the 'andre_feijo' algorithm.")
            sys.exit(1)
        except subprocess.CalledProcessError as e:
            print(f"ERROR: Maven compilation failed with exit code {e.returncode}.")
            sys.exit(1)

    runs_per_instance = config.get('runs_per_instance', 1)
    last_run_id = first_run_id + runs_per_instance - 1

    if first_run_id < 1:
        print("ERROR: --first-run-id must be >= 1.", file=sys.stderr)
        sys.exit(1)

    print(f"Run IDs: {first_run_id} to {last_run_id} ({runs_per_instance} run(s) per instance)")

    time_limit = config.get('time_limit', 10)
    time_grace = config.get('time_grace', DEFAULT_TIME_GRACE)

    # andre_feijo hardcodes its own budget (9m50s) and takes no time-limit
    # argument, so it cannot be held to the configured limit.
    if has_andre and abs(time_limit - 590) > 1:
        print(
            f"WARNING: andre_feijo enforces its own hardcoded 590s budget and "
            f"ignores time_limit={time_limit}s. Runs are NOT time-comparable "
            f"with the other algorithms under this configuration.",
            file=sys.stderr,
        )

    tasks = []
    for dataset, instances in config['datasets'].items():
        for instance in instances:
            for run_id in range(first_run_id, last_run_id + 1):
                seed = get_seed(seeds, dataset, instance, run_id)
                for algo in config['algorithms']:
                    tasks.append({
                        'dataset': dataset,
                        'instance': instance,
                        'run_id': run_id,
                        'algo': algo,
                        'time_limit': time_limit,
                        'time_grace': time_grace,
                        'result_dir': result_dir,
                        'seed': seed
                    })

    results_by_instance = defaultdict(lambda: defaultdict(list))

    total = len(tasks)
    n_workers = config.get('n_workers', 4)
    now = datetime.now()

    if n_workers > os.cpu_count():
        print(f"WARNING: n_workers={n_workers} exceeds {os.cpu_count()} available "
              f"CPUs; time-limited runs will be distorted by CPU contention.",
              file=sys.stderr)

    print(f"Starting At: {now.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"Starting {total} tasks with {n_workers} workers...")

    provenance = collect_provenance(config, result_dir, n_workers)
    with open(os.path.join(result_dir, "provenance.json"), "w") as f:
        json.dump(provenance, f, indent=2)
    if provenance["git_dirty"]:
        print("WARNING: the working tree has uncommitted changes; "
              f"commit {provenance['git_commit']} alone does not describe the "
              f"code that produced these results.", file=sys.stderr)

    # Append-only audit trail: written as results arrive, so a crash mid-run
    # does not destroy the evidence, and every verdict keeps its diagnosis.
    audit_path = os.path.join(result_dir, "validation_log.jsonl")
    status_counter = Counter()
    reason_counter = Counter()
    not_feasible = []

    with open(audit_path, "w") as audit, \
            ProcessPoolExecutor(max_workers=n_workers) as executor:
        futures = [executor.submit(worker, t) for t in tasks]
        for completed, future in enumerate(as_completed(futures), start=1):
            seed, dataset, instance, metrics, record = future.result()
            results_by_instance[dataset][instance].append(metrics)

            audit.write(json.dumps(record) + "\n")
            audit.flush()

            status_counter[metrics['status']] += 1
            if metrics['status'] != 'feasible':
                reason_counter[metrics['reason']] += 1
                not_feasible.append((dataset, instance, metrics))
            if not metrics['time_ok']:
                reason_counter['time_overrun'] += 1
            if metrics['status'] == 'feasible' and metrics['returncode'] not in (0, None):
                # Feasible output from a process that failed afterwards: the run
                # is suspect even though the solution checks out.
                reason_counter['nonzero_exit_on_feasible_run'] += 1

            flag = "" if metrics['time_ok'] else " [TIME OVERRUN]"
            detail = "" if metrics['status'] == 'feasible' else f" ({metrics['reason']})"
            print(f"[{completed}/{total}] {dataset}/{instance} - {metrics['algo_id']} "
                  f"(run {metrics['run_id']}) (seed {seed}) -> "
                  f"{metrics['status']}{detail}{flag}")

    for dataset, instances in results_by_instance.items():
        dataset_dir = os.path.join(result_dir, dataset)
        os.makedirs(dataset_dir, exist_ok=True)
        for instance, metrics_list in instances.items():
            csv_path = os.path.join(dataset_dir, f"{instance.replace('.txt', '')}.csv")
            metrics_list.sort(key=lambda m: (m['algo_id'], m['run_id']))
            with open(csv_path, "w", newline="") as f:
                writer = csv.DictWriter(f, fieldnames=CSV_FIELDS, extrasaction="ignore")
                writer.writeheader()
                writer.writerows(metrics_list)

    # Solution files are kept on purpose: they are the only artifact that allows
    # a result to be re-validated offline, compared against best_solutions/ or
    # promoted to a new best after the run has finished.
    print(f"\nSolution files kept in: {temp_dir}")

    print(f"Summary: {len(tasks)} tasks across "
          f"{sum(len(v) for v in results_by_instance.values())} instances. "
          f"Results in {result_dir}")
    print("  Status: " + ", ".join(f"{k}={v}" for k, v in sorted(status_counter.items())))

    if reason_counter:
        sep = "!" * 72
        print(f"\n{sep}", file=sys.stderr)
        print("VALIDATION GATE: runs not certified feasible (excluded from any "
              "sound analysis):", file=sys.stderr)
        for reason, count in reason_counter.most_common():
            print(f"  {reason}: {count}", file=sys.stderr)
        if any(m['status'] == 'infeasible' for _, _, m in not_feasible):
            print("  'infeasible' means a solver emitted a constraint-violating "
                  "solution: a solver bug, not a bad result. Investigate before "
                  "reporting numbers.", file=sys.stderr)
        print(f"  Diagnoses: {audit_path}", file=sys.stderr)
        print(f"  Offending solution files: "
              f"{os.path.join(result_dir, 'failed_solutions')}", file=sys.stderr)
        print(f"{sep}\n", file=sys.stderr)

if __name__ == "__main__":
    main()
