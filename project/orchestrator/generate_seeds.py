"""
generate_seeds.py
-----------------
Generates seeds.json — a deterministic mapping of
    (dataset, instance, run_id) -> seed
for every dataset/instance present under datasets/ and for run_ids 1..MAX_RUN_ID.

Run once from the project root:
    python project/orchestrator/generate_seeds.py

The file is written to: project/orchestrator/seeds.json
"""

import json
import os
import random
from pathlib import Path

MAX_RUN_ID = 30
SEED_FOR_RNG = 42          # Makes the generated table itself reproducible
DATASETS_DIR = Path(__file__).resolve().parent.parent.parent / "datasets"
OUTPUT_PATH = Path(__file__).resolve().parent / "seeds.json"

rng = random.Random(SEED_FOR_RNG)

seeds: dict = {}

dataset_groups = sorted(d.name for d in DATASETS_DIR.iterdir() if d.is_dir())

for dataset in dataset_groups:
    dataset_dir = DATASETS_DIR / dataset
    instances = sorted(f.name for f in dataset_dir.iterdir() if f.is_file() and f.suffix == ".txt")
    seeds[dataset] = {}
    for instance in instances:
        seeds[dataset][instance] = {}
        for run_id in range(1, MAX_RUN_ID + 1):
            seeds[dataset][instance][str(run_id)] = rng.randint(1, 100_000)

with open(OUTPUT_PATH, "w") as f:
    json.dump(seeds, f, indent=2)

total = sum(
    len(run_ids)
    for ds in seeds.values()
    for run_ids in ds.values()
)
print(f"Generated {total} seeds → {OUTPUT_PATH}")
