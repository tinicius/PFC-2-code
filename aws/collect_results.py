#!/usr/bin/env python3
import boto3
import json
import os
import sys
import argparse
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import tempfile

sys.path.append(os.path.join("project", "validator"))
try:
    import validator
except ImportError:
    print("Warning: could not import validator. Validation metrics will not be calculated.", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, help="Path to config.json")
    parser.add_argument("--runs", required=True, type=int, help="Number of runs expected")
    parser.add_argument("--bucket", required=True, help="S3 bucket name")
    parser.add_argument("--region", default="us-east-1", help="AWS region")
    args = parser.parse_args()

    s3 = boto3.client("s3", region_name=args.region)
    prefix = "resultados/"
    output_dir = Path("project/results/result_aws")
    output_dir.mkdir(parents=True, exist_ok=True)

    with open(args.config) as f:
        config = json.load(f)

    algo_cfg = config["algorithms"][0]
    algo_id = algo_cfg["id"]

    tasks = []
    for dataset, instances in config["datasets"].items():
        for inst in instances:
            for run_id in range(args.runs):
                inst_stem = inst.replace(".txt", "")
                key = f"{prefix}{dataset}/{inst_stem}/run_{run_id}.json"
                tasks.append((dataset, inst, run_id, key))

    results = {}
    missing = []

    def fetch_result(task):
        dataset, inst, run_id, key = task
        try:
            obj = s3.get_object(Bucket=args.bucket, Key=key)
            sol = json.loads(obj["Body"].read().decode('utf-8'))
            sol["algo_id"] = algo_id
            sol["run_id"] = run_id
            if "status" not in sol:
                sol["status"] = "success"
            
            # Validate to compute objective, items, and aisles
            if 'validator' in sys.modules:
                inst_path = Path("datasets") / dataset / inst
                if inst_path.exists():
                    with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.json') as tmp:
                        json.dump(sol, tmp)
                        tmp_path = tmp.name
                    
                    try:
                        val_res = validator.validate(str(inst_path), tmp_path)
                        val_res.pop('message', None)
                        sol.update(val_res)
                    finally:
                        os.remove(tmp_path)
                        
            return (dataset, inst, sol, None)
        except s3.exceptions.NoSuchKey:
            return (dataset, inst, None, key)
        except Exception as e:
            print(f"Erro ao ler {key}: {e}", file=sys.stderr)
            return (dataset, inst, None, key)

    print(f"Buscando {len(tasks)} resultados no S3 (bucket: {args.bucket})...")
    
    with ThreadPoolExecutor(max_workers=20) as executor:
        futures = [executor.submit(fetch_result, t) for t in tasks]
        for future in as_completed(futures):
            dataset, inst, sol, err_key = future.result()
            if err_key:
                missing.append(err_key)
            else:
                results.setdefault((dataset, inst), []).append(sol)

    headers = ["algo_id", "run_id", "status", "objective", "items", "aisles", "exec_time"]

    for (dataset, inst), metrics_list in results.items():
        out_dir = output_dir / dataset
        out_dir.mkdir(exist_ok=True, parents=True)
        csv_path = out_dir / inst.replace(".txt", ".csv")
        with open(csv_path, "w") as f:
            f.write(",".join(headers) + "\n")
            for m in metrics_list:
                f.write(",".join(str(m.get(h, "")) for h in headers) + "\n")

    if missing:
        print(f"\n[AVISO] {len(missing)} resultados estao faltando no S3 (jobs falharam ou ainda rodando).")
        print("Exemplos de chaves faltantes:")
        for m in missing[:5]:
            print(f"  - {m}")
        if len(missing) > 5:
            print("  ...")
    else:
        print(f"\n[OK] Todos os {len(tasks)} resultados foram coletados!")
        
    print(f"CSVs salvos em {output_dir}")

if __name__ == "__main__":
    main()
