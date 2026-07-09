import sys
import json
import os
import shutil
import subprocess
import glob
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
        
    solution_path = os.path.join(result_dir, "temp", f"{instance}_{algo['id']}_{run_id}.json")

    algo_name = algo.get('binary', 'sa')
    java_dir = "project/algos_java"

    cmd = [
        "java", "-cp", java_dir, "Main",
        f"--input={instance_path}",
        f"--output={solution_path}",
        f"--time-limit={time_limit}",
        f"--seed={random.randint(1, 100000)}",
        f"--algo={algo_name}",
        f"--params={json.dumps(algo.get('params', {}))}",
    ]
    
    metrics = {
        "algo_id": algo['id'],
        "run_id": run_id,
        "status": "error",
        "objective": 0.0,
        "items": 0,
        "aisles": 0,
        "exec_time": 0.0
    }
    
    # print(cmd)
    # return
        
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
                metrics.update(val_res)
    except Exception as e:
        metrics["status"] = "timeout_or_error"
        
    return instance, metrics

def main():
    if len(sys.argv) != 2:
        print("Usage: python run_experiment.py <config.json>")
        sys.exit(1)
        
    config_path = sys.argv[1]
    with open(config_path, "r") as f:
        config = json.load(f)
        
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
        ["javac", "-d", ".",
            "Main.java",
            "heuristic/Heuristic.java", "heuristic/SA.java",
            "model/Problem.java", "model/Solution.java",
            "neighborhood/Move.java", "neighborhood/AddAisle.java", "neighborhood/RemoveAisle.java",
            "neighborhood/SwapAisle.java", "neighborhood/SwapOrder.java", 
            "neighborhood/AddOrder.java", "neighborhood/RemoveOrder.java",
            "constructive/AisleFirst.java"],
        cwd=java_dir, check=True
    )

    tasks = []
    for dataset, instances in config['datasets'].items():
        for instance in instances:
            for algo in config['algorithms']:
                for run_id in range(1, config.get('runs_per_instance', 1) + 1):
                    tasks.append({
                        'dataset': dataset,
                        'instance': instance,
                        'run_id': run_id,
                        'algo': algo,
                        'time_limit': config.get('time_limit', 10),
                        'result_dir': result_dir
                    })
                    
    results_by_instance = defaultdict(list)
    
    total = len(tasks)
    n_workers = config.get('n_workers', 4)
    now = datetime.now()

    print(f"Starting At: {now.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"Starting {total} tasks with {n_workers} workers...")
    with ProcessPoolExecutor(max_workers=n_workers) as executor:
        futures = [executor.submit(worker, t) for t in tasks]
        for completed, future in enumerate(as_completed(futures), start=1):
            instance, metrics = future.result()
            results_by_instance[instance].append(metrics)
            print(f"[{completed}/{total}] {instance} - {metrics['algo_id']} (run {metrics['run_id']}) -> {metrics['status']}")
            
    for instance, metrics_list in results_by_instance.items():
        csv_path = os.path.join(result_dir, f"{instance.replace('.txt', '')}.csv")
        with open(csv_path, "w") as f:
            headers = ["algo_id", "run_id", "status", "objective", "items", "aisles", "exec_time"]
            f.write(",".join(headers) + "\n")
            for m in metrics_list:
                f.write(",".join(str(m.get(h, "")) for h in headers) + "\n")
                
    print(f"Summary: Completed {len(tasks)} tasks across {len(results_by_instance)} instances. Results in {result_dir}")

if __name__ == "__main__":
    main()
