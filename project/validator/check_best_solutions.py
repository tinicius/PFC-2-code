"""Certify the reference solutions used as the comparison baseline.

``best_solutions/<dataset>/<instance>.txt`` holds reference solutions in the
official challenge format and ``best_solutions/best_objectives.csv`` holds the
objective values used as the RPD/gap denominator in the analysis. If a reference
solution is infeasible, or if the tabulated objective does not match the
solution it claims to describe, then every relative deviation reported in the
study is wrong. This script checks both.

Usage::

    python project/validator/check_best_solutions.py [--csv-only] [--quiet]

Exit code 0 when every reference solution is feasible and every tabulated
objective matches; 1 otherwise.
"""

import argparse
import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import validator  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DATASETS_DIR = REPO_ROOT / "datasets"
BEST_DIR = REPO_ROOT / "best_solutions"
BEST_CSV = BEST_DIR / "best_objectives.csv"

TOLERANCE = 1e-6


def load_best_objectives(path):
    """Read ``best_objectives.csv`` into {(dataset, instance): objective}."""
    table = {}
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            table[(row["dataset"], row["instance"])] = float(row["best_objective"])
    return table


def check(quiet=False):
    """Validate every reference solution and cross-check the objectives table.

    :returns: (n_checked, list of problem descriptions).
    """
    problems = []
    table = load_best_objectives(BEST_CSV)
    seen = set()
    n_checked = 0

    for solution_path in sorted(BEST_DIR.glob("*/*.txt")):
        dataset = solution_path.parent.name
        instance = solution_path.name
        key = (dataset, instance)
        seen.add(key)

        instance_path = DATASETS_DIR / dataset / instance
        if not instance_path.exists():
            problems.append(f"{dataset}/{instance}: no matching instance file")
            continue

        result = validator.validate(str(instance_path), str(solution_path))
        n_checked += 1

        if result["status"] != "feasible":
            problems.append(
                f"{dataset}/{instance}: reference solution is "
                f"{result['status']} ({result['reason']}) -> {result['message']}"
            )
            continue

        if key not in table:
            problems.append(
                f"{dataset}/{instance}: feasible (objective "
                f"{result['objective']:.6g}) but absent from best_objectives.csv"
            )
            continue

        tabulated = table[key]
        if abs(tabulated - result["objective"]) > TOLERANCE * max(1.0, abs(tabulated)):
            problems.append(
                f"{dataset}/{instance}: best_objectives.csv says {tabulated:.6g} "
                f"but the reference solution evaluates to {result['objective']:.6g} "
                f"({result['items']} units / {result['aisles']} aisles)"
            )
        elif not quiet:
            print(f"OK  {dataset}/{instance}: {result['objective']:.6g} "
                  f"({result['items']} units / {result['aisles']} aisles)")

    for key in sorted(table):
        if key not in seen:
            problems.append(
                f"{key[0]}/{key[1]}: listed in best_objectives.csv with "
                f"{table[key]:.6g} but has no reference solution file to back it"
            )

    return n_checked, problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true",
                        help="only report problems")
    args = parser.parse_args()

    n_checked, problems = check(quiet=args.quiet)

    print(f"\nChecked {n_checked} reference solution(s) against "
          f"{BEST_CSV.relative_to(REPO_ROOT)}")
    if problems:
        print(f"{len(problems)} problem(s) found:")
        for p in problems:
            print(f"  - {p}")
        print("\nThe RPD/gap baseline is not trustworthy until these are fixed.")
        return 1

    print("All reference solutions are feasible and match the tabulated "
          "objectives.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
