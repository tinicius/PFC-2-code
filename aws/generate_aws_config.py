#!/usr/bin/env python3
"""
Le um config.json do orquestrador e gera dois arquivos para o build Docker:
  aws/instances.txt   - lista flat: "dataset instance_name" por linha
  aws/job_config.env  - variaveis de shell: TIME_LIMIT, ALGO, PARAMS_JSON, etc.

Uso: python aws/generate_aws_config.py <path/to/config.json>
"""
import json
import sys
import os
from pathlib import Path

def main():
    if len(sys.argv) < 2:
        print("Uso: python aws/generate_aws_config.py <config.json>")
        sys.exit(1)

    config_path = Path(sys.argv[1])
    if not config_path.exists():
        print(f"Erro: arquivo não encontrado: {config_path}")
        sys.exit(1)

    aws_dir = Path(__file__).parent

    with open(config_path) as f:
        config = json.load(f)

    # Lista de instâncias
    instances = []
    for dataset, inst_list in config["datasets"].items():
        for inst in sorted(inst_list):
            instances.append(f"{dataset} {inst}")

    instances_path = aws_dir / "instances.txt"
    with open(instances_path, "w") as f:
        f.write("\n".join(instances) + "\n")
    print(f"[OK] {len(instances)} instâncias -> {instances_path}")

    # Configuração do job
    algo_cfg = config["algorithms"][0]
    algo_binary  = algo_cfg.get("binary", algo_cfg["id"])
    params_json  = json.dumps(algo_cfg.get("params", {}), separators=(',', ':'))
    time_limit   = config.get("time_limit", 600)
    runs_default = config.get("runs_per_instance", 2)
    algo_id      = algo_cfg["id"]

    env_path = aws_dir / "job_config.env"
    with open(env_path, "w") as f:
        f.write(f"TIME_LIMIT={time_limit}\n")
        f.write(f"ALGO={algo_binary}\n")
        f.write(f"ALGO_ID={algo_id}\n")
        f.write(f"PARAMS_JSON='{params_json}'\n")
        f.write(f"RUNS_DEFAULT={runs_default}\n")
        f.write(f"N_INSTANCES={len(instances)}\n")

    print(f"[OK] Configuracao do job -> {env_path}")

if __name__ == "__main__":
    main()
