import sys
import json
import os
import shutil
import subprocess
import traceback
import argparse
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from collections import defaultdict
import random
from datetime import datetime

# Need to import validator dynamically or use subprocess. Let's just run it or import it.
sys.path.append(os.path.join(os.path.dirname(__file__), "..", "validator"))
try:
    import validator
except ImportError:
    pass

def worker(task):
    dataset = task['dataset']
    instance = task['instance']
    run_id = task['run_id']
    algo = task['algo']
    time_limit = task['time_limit']
    result_dir = task['result_dir']
    
    # Path to instance file
    datasets_dir = Path(__file__).resolve().parent.parent.parent / "datasets"
    instance_path = str(datasets_dir / dataset / instance)
        
    solution_path = os.path.join(result_dir, "temp", f"{dataset}_{instance}_{algo['id']}_{run_id}.json")

    algo_name = algo.get('binary', 'sa')
    java_dir = "project/algos_java"
    
    
    seed = task.get('seed', random.randint(1, 100000))

    if algo_name == "andre_feijo":
        jar_path = os.path.join(java_dir, "andre_feijo", "target", "ChallengeSBPO2025-1.0.jar")
        
        # Search for CPLEX native library path dynamically, or use the known path
        cplex_lib_path = "/opt/ibm/ILOG/CPLEX_Studio222/cplex/bin/x86-64_linux"
        
        # andre_feijo accepts an optional 3rd arg as the seed (long).
        cmd = [
            "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
            "-Xmx8g",
            f"-Djava.library.path={cplex_lib_path}",
            "-jar", jar_path,
            instance_path,
            solution_path,
            str(seed)
        ]
    else:
        cmd = [
            "/usr/lib/jvm/java-21-openjdk-amd64/bin/java", "-cp", java_dir, "Main",
            f"--input={instance_path}",
            f"--output={solution_path}",
            f"--time-limit={time_limit}",
            f"--seed={seed}",
            f"--algo={algo_name}",
            f"--params={json.dumps(algo.get('params', {}))}",
        ]
    
    metrics = {
        "algo_id": algo['id'],
        "run_id": run_id,
        "seed": seed,
        "status": "error",
        "objective": 0.0,
        "items": 0,
        "aisles": 0,
        "exec_time": 0.0
    }
    
    # print(cmd)
    # return
        
    proc = None
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=time_limit + 30)
        if proc.stderr:
            print(proc.stderr, file=sys.stderr)
            
        if os.path.exists(solution_path):
            with open(solution_path, "r") as f:
                sol = json.load(f)
                if "exec_time" in sol:
                    metrics["exec_time"] = sol["exec_time"]
            
            # Validate
            if 'validator' in globals():
                val_res = validator.validate(instance_path, solution_path)
                val_res.pop('message', None)  # diagnostic-only field, not stored
                metrics.update(val_res)
    except Exception as e:
        metrics["status"] = "timeout_or_error"
        sep = "=" * 60
        print(f"\n{sep}", file=sys.stderr)
        print(f"[TIMEOUT/ERROR] {dataset}/{instance} | algo={algo['id']} run={run_id} seed={seed}", file=sys.stderr)
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
        
    return seed, dataset, instance, metrics

def load_seeds(seeds_path: Path) -> dict:
    """Load the deterministic seed map from seeds.json."""
    if not seeds_path.exists():
        raise FileNotFoundError(
            f"seeds.json not found at {seeds_path}. "
            "Run generate_seeds.py to create it."
        )
    with open(seeds_path, "r") as f:
        return json.load(f)


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
            "heuristic/Heuristic.java", "heuristic/SA.java", "heuristic/ILS.java",
            "model/Problem.java", "model/Solution.java",
            "neighborhood/Move.java", "neighborhood/AddAisle.java", "neighborhood/RemoveAisle.java",
            "neighborhood/SwapAisle.java", "neighborhood/SwapOrder.java", 
            "neighborhood/AddOrder.java", "neighborhood/RemoveOrder.java",
            "constructive/AisleFirst.java"],
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
                        'time_limit': config.get('time_limit', 10),
                        'result_dir': result_dir,
                        'seed': seed
                    })
                    
    results_by_instance = defaultdict(lambda: defaultdict(list))
    
    total = len(tasks)
    n_workers = config.get('n_workers', 4)
    now = datetime.now()

    print(f"Starting At: {now.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"Starting {total} tasks with {n_workers} workers...")
    with ProcessPoolExecutor(max_workers=n_workers) as executor:
        futures = [executor.submit(worker, t) for t in tasks]
        for completed, future in enumerate(as_completed(futures), start=1):
            seed, dataset, instance, metrics = future.result()
            results_by_instance[dataset][instance].append(metrics)
            print(f"[{completed}/{total}] {dataset}/{instance} - {metrics['algo_id']} (run {metrics['run_id']}) (seed {seed}) -> {metrics['status']}")
            
    for dataset, instances in results_by_instance.items():
        dataset_dir = os.path.join(result_dir, dataset)
        os.makedirs(dataset_dir, exist_ok=True)
        for instance, metrics_list in instances.items():
            csv_path = os.path.join(dataset_dir, f"{instance.replace('.txt', '')}.csv")
            with open(csv_path, "w") as f:
                headers = ["algo_id", "run_id", "seed", "status", "objective", "items", "aisles", "exec_time"]
                f.write(",".join(headers) + "\n")
                for m in metrics_list:
                    f.write(",".join(str(m.get(h, "")) for h in headers) + "\n")
                
    # Clean up temp solution files
    shutil.rmtree(temp_dir, ignore_errors=True)

    print(f"Summary: Completed {len(tasks)} tasks across {sum(len(v) for v in results_by_instance.values())} instances. Results in {result_dir}")

if __name__ == "__main__":
    main()
