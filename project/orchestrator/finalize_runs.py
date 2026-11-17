"""Rebuild the final CSV artefacts of interrupted experiment runs.

``run_experiment.py`` only writes the per-instance CSVs after its process pool
has drained. An experiment killed before that point leaves a results directory
with ``config.json``, ``provenance.json``, ``temp/`` and ``validation_log.jsonl``
but *no* CSV, which is the only artefact the analysis notebooks read
(``dataset_dir.glob('*.csv')``). The computation is done but unusable.

This script reconstructs those CSVs from ``validation_log.jsonl``, which is a
sound source of truth: the worker copies every metric into the audit record
(``record.update(metrics)``) and the log is flushed per record, so at most the
final line of an interrupted run can be truncated.

It also merges two or more such directories -- the usual case being a run
resumed into a new directory (``result_0008`` continuing ``result_0007``) --
under three deliberately conservative rules:

1. Only instances whose runs are *all* present are kept. A partial sample would
   silently change the statistics computed downstream.
2. The completeness filter runs *before* conflict resolution, so a partial
   attempt in one directory does not veto a complete one elsewhere.
3. An instance that is complete in more than one source directory is dropped:
   picking a winner would be an arbitrary choice about which sample is reported.

The source directories are never modified: ``temp/`` is left in place, no
``failed_solutions/`` is created, and the output goes to a new, auto-incremented
``result_XXXX/`` directory.

Usage::

    python3 project/orchestrator/finalize_runs.py \
        project/results/result_0007 project/results/result_0008
"""

import argparse
import csv
import json
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_experiment  # noqa: E402

# Single source of truth for the CSV schema: duplicating the field list here
# would let the recovered files silently drift from the orchestrator's.
CSV_FIELDS = run_experiment.CSV_FIELDS

# Config defaults, applied before comparing two configs so that an omitted key
# and an explicitly-written default are not reported as a divergence.
PARAM_DEFAULTS = {
    "runs_per_instance": 1,
    "time_limit": 10,
    "time_grace": run_experiment.DEFAULT_TIME_GRACE,
    "n_workers": 4,
}

# Fields a log record must carry to be attributable to an instance/run.
REQUIRED_RECORD_FIELDS = ("dataset", "instance", "algo_id", "run_id")


class FinalizeError(Exception):
    """Unrecoverable problem: the merge must not proceed."""


# --------------------------------------------------------------------------
# Config handling
# --------------------------------------------------------------------------

def load_config(result_dir: Path) -> dict:
    """Load ``config.json`` from a result directory."""
    path = result_dir / "config.json"
    if not path.exists():
        raise FinalizeError(f"{path} not found: cannot tell how many runs were expected.")
    try:
        with open(path, "r") as f:
            config = json.load(f)
    except (OSError, ValueError) as e:
        raise FinalizeError(f"{path} is not readable as JSON: {e}")
    if not isinstance(config, dict):
        raise FinalizeError(f"{path} does not contain a JSON object.")
    return config


def experiment_params(config: dict) -> dict:
    """Return the experiment parameters, i.e. the config minus ``datasets``.

    ``datasets`` is excluded on purpose: a resumed run legitimately lists only
    the instances left to do, and requiring it to match would reject exactly the
    directories this script exists to merge.
    """
    params = {k: v for k, v in config.items() if k != "datasets"}
    for key, default in PARAM_DEFAULTS.items():
        params.setdefault(key, default)
    return params


def check_compatible(params_by_dir: dict) -> list:
    """Compare every directory's parameters against the first one.

    Returns a list of divergence descriptions; empty means the runs can be
    merged. Merging runs produced under different parameters would pool
    observations that are not comparable.
    """
    problems = []
    names = list(params_by_dir)
    reference_name = names[0]
    reference = params_by_dir[reference_name]

    for name in names[1:]:
        other = params_by_dir[name]
        for key in sorted(set(reference) | set(other)):
            if key not in reference:
                problems.append(f"'{key}' present in {name} but absent in {reference_name}")
            elif key not in other:
                problems.append(f"'{key}' present in {reference_name} but absent in {name}")
            elif reference[key] != other[key]:
                problems.append(
                    f"'{key}' differs: {reference_name}={reference[key]!r} "
                    f"vs {name}={other[key]!r}"
                )
    return problems


def algo_ids(config: dict) -> list:
    """Algorithm ids declared in a config."""
    return [algo.get("id") for algo in config.get("algorithms", [])]


# --------------------------------------------------------------------------
# Audit log parsing
# --------------------------------------------------------------------------

def load_records(log_path: Path):
    """Parse ``validation_log.jsonl``.

    Returns ``(records, warnings)``. A truncated final line is expected when a
    run is killed mid-write and is reported but tolerated; a broken line
    anywhere else is also skipped, with its line number, rather than aborting
    the recovery of every other run.
    """
    if not log_path.exists():
        raise FinalizeError(f"{log_path} not found: there is nothing to rebuild from.")

    with open(log_path, "r") as f:
        lines = f.readlines()

    records, warnings = [], []
    last_index = len(lines) - 1
    for i, line in enumerate(lines):
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except ValueError as e:
            where = "final line truncated" if i == last_index else f"line {i + 1} corrupt"
            warnings.append(f"{log_path}: {where}, discarded ({e})")
            continue
        missing = [k for k in REQUIRED_RECORD_FIELDS if k not in record]
        if missing:
            warnings.append(
                f"{log_path}: line {i + 1} lacks {missing}, discarded"
            )
            continue
        records.append(record)

    return records, warnings


# --------------------------------------------------------------------------
# Completeness filter
# --------------------------------------------------------------------------

def group_and_filter(records, expected_algos, runs_per_instance):
    """Split instances into complete and incomplete ones.

    An instance is complete when *every* expected algorithm has exactly
    ``runs_per_instance`` distinct run_ids. The check counts run_ids instead of
    matching them against ``1..N`` because ``config.json`` does not record
    ``--first-run-id``: a run resumed at 31 is just as complete.

    Returns ``(complete, incomplete, warnings)`` where ``complete`` maps
    ``(dataset, instance)`` to the list of records and ``incomplete`` maps it to
    ``{algo_id: observed_count}``.
    """
    grouped = defaultdict(lambda: defaultdict(dict))
    warnings = []

    for record in records:
        key = (record["dataset"], record["instance"])
        algo = record["algo_id"]
        run_id = record["run_id"]
        bucket = grouped[key][algo]
        if run_id in bucket:
            warnings.append(
                f"{key[0]}/{key[1]}: duplicate record for {algo} run {run_id}; "
                f"keeping the last one"
            )
        bucket[run_id] = record

    complete, incomplete = {}, {}
    for key in sorted(grouped):
        by_algo = grouped[key]
        counts = {algo: len(by_algo.get(algo, {})) for algo in expected_algos}
        # Records of an algorithm not declared in the config still matter for the
        # verdict: they mean the directory does not hold what the config says.
        for algo in by_algo:
            counts.setdefault(algo, len(by_algo[algo]))

        if all(count == runs_per_instance for count in counts.values()) and counts:
            records_for_key = [r for algo in by_algo for r in by_algo[algo].values()]
            records_for_key.sort(key=lambda r: (r["algo_id"], r["run_id"]))
            complete[key] = records_for_key
        else:
            incomplete[key] = counts
            for algo, count in counts.items():
                if count > runs_per_instance:
                    warnings.append(
                        f"{key[0]}/{key[1]}: {algo} has {count} runs but "
                        f"{runs_per_instance} were expected; instance excluded"
                    )

    return complete, incomplete, warnings


# --------------------------------------------------------------------------
# Conflict resolution across directories
# --------------------------------------------------------------------------

def resolve_conflicts(complete_by_dir):
    """Merge the per-directory complete instances.

    ``complete_by_dir`` maps a directory name to its ``complete`` mapping.
    Returns ``(included, conflicts)``: ``included`` maps ``(dataset, instance)``
    to ``(dir_name, records)``; ``conflicts`` maps the key to the list of
    directories that hold it, for the instances dropped as ambiguous. The rule
    is symmetric, so the order of the directories on the command line does not
    change the outcome.
    """
    owners = defaultdict(list)
    for dir_name, complete in complete_by_dir.items():
        for key in complete:
            owners[key].append(dir_name)

    included, conflicts = {}, {}
    for key in sorted(owners):
        dirs = owners[key]
        if len(dirs) == 1:
            dir_name = dirs[0]
            included[key] = (dir_name, complete_by_dir[dir_name][key])
        else:
            conflicts[key] = dirs
    return included, conflicts


# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------

def next_result_dir(results_base: Path) -> Path:
    """Next free ``result_XXXX`` directory, following the orchestrator's scheme."""
    next_id = 1
    if results_base.exists():
        ids = [
            int(d.name.split("_")[1])
            for d in results_base.iterdir()
            if d.is_dir() and d.name.startswith("result_") and d.name.split("_")[1].isdigit()
        ]
        if ids:
            next_id = max(ids) + 1
    return results_base / f"result_{next_id:04d}"


def write_csvs(out_dir: Path, included, dry_run=False):
    """Write one CSV per included instance, byte-compatible with the runner's."""
    written = []
    for (dataset, instance), (_dir_name, records) in sorted(included.items()):
        csv_path = out_dir / dataset / f"{instance.replace('.txt', '')}.csv"
        written.append(csv_path)
        if dry_run:
            continue
        csv_path.parent.mkdir(parents=True, exist_ok=True)
        rows = sorted(records, key=lambda r: (r["algo_id"], r["run_id"]))
        with open(csv_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=CSV_FIELDS, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(rows)
    return written


def merged_datasets(included) -> dict:
    """Dataset -> sorted instances actually written."""
    datasets = defaultdict(list)
    for dataset, instance in included:
        datasets[dataset].append(instance)
    return {ds: sorted(instances) for ds, instances in sorted(datasets.items())}


def write_merged_config(out_dir: Path, params: dict, included, dry_run=False) -> dict:
    """Config describing the merged directory: shared params + what was kept."""
    config = {"datasets": merged_datasets(included)}
    config.update(params)
    if not dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)
        with open(out_dir / "config.json", "w") as f:
            json.dump(config, f, indent=2)
    return config


def load_source_provenance(result_dir: Path):
    """Provenance of a source directory, or ``None`` when absent/unreadable."""
    path = result_dir / "provenance.json"
    if not path.exists():
        return None
    try:
        with open(path, "r") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def write_merged_provenance(out_dir: Path, source_dirs, params, included, dry_run=False):
    """Record which directories produced the merged numbers.

    Returns ``(provenance, warnings)``. Sources built from different commits are
    flagged: their runs came from different code, so pooling them weakens any
    claim made about "the" implementation.
    """
    sources, commits, warnings = [], set(), []
    for result_dir in source_dirs:
        prov = load_source_provenance(result_dir) or {}
        sources.append({
            "result_dir": str(result_dir),
            "started_at": prov.get("started_at"),
            "git_commit": prov.get("git_commit"),
            "git_dirty": prov.get("git_dirty"),
            "hostname": prov.get("hostname"),
            "n_workers": prov.get("n_workers"),
        })
        if prov.get("git_commit"):
            commits.add(prov["git_commit"])

    if len(commits) > 1:
        warnings.append(
            "source directories were produced by different git commits "
            f"({sorted(commits)}); the merged results do not describe a single "
            f"code revision"
        )

    provenance = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "created_by": "finalize_runs.py",
        "result_dir": str(out_dir),
        "merged_from": [str(d) for d in source_dirs],
        "sources": sources,
        "instances": len(included),
        "runs_per_instance": params.get("runs_per_instance"),
        "time_limit": params.get("time_limit"),
        "algorithms": [a.get("id") for a in params.get("algorithms", [])],
        "python_version": sys.version.split()[0],
    }
    if not dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)
        with open(out_dir / "provenance.json", "w") as f:
            json.dump(provenance, f, indent=2)
    return provenance, warnings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

def count_verdicts(included):
    """Status/reason tallies over the records that were actually kept."""
    status_counter, reason_counter = Counter(), Counter()
    for _key, (_dir_name, records) in included.items():
        for record in records:
            status_counter[record.get("status", "unknown")] += 1
            if record.get("status") != "feasible":
                reason_counter[record.get("reason", "")] += 1
            if not record.get("time_ok", True):
                reason_counter["time_overrun"] += 1
            if record.get("status") == "feasible" and record.get("returncode") not in (0, None):
                reason_counter["nonzero_exit_on_feasible_run"] += 1
    return status_counter, reason_counter


def format_counts(counts: dict, runs_per_instance: int) -> str:
    """Render ``{algo: observed}`` as ``algo obs/expected`` pairs."""
    return ", ".join(
        f"{algo} {observed}/{runs_per_instance}" for algo, observed in sorted(counts.items())
    )


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------

def finalize(source_dirs, runs_per_instance_override=None, dry_run=False):
    """Merge the source directories. Returns a dict describing what was done."""
    source_dirs = [Path(d) for d in source_dirs]

    resolved = [d.resolve() for d in source_dirs]
    if len(set(resolved)) != len(resolved):
        raise FinalizeError("the same result directory was given more than once.")
    for d in source_dirs:
        if not d.is_dir():
            raise FinalizeError(f"{d} is not a directory.")

    configs = {str(d): load_config(d) for d in source_dirs}
    params_by_dir = {name: experiment_params(c) for name, c in configs.items()}
    problems = check_compatible(params_by_dir)
    if problems:
        raise FinalizeError(
            "the result directories were produced with different experiment "
            "parameters and must not be merged:\n  - " + "\n  - ".join(problems)
        )

    params = params_by_dir[str(source_dirs[0])]
    runs_per_instance = runs_per_instance_override or params["runs_per_instance"]
    expected_algos = algo_ids(configs[str(source_dirs[0])])

    warnings = []
    per_dir = {}
    complete_by_dir = {}
    for d in source_dirs:
        name = str(d)
        records, log_warnings = load_records(d / "validation_log.jsonl")
        warnings.extend(log_warnings)
        complete, incomplete, group_warnings = group_and_filter(
            records, expected_algos, runs_per_instance
        )
        warnings.extend(group_warnings)
        complete_by_dir[name] = complete
        per_dir[name] = {
            "records": len(records),
            "discarded_lines": len(log_warnings),
            "complete": complete,
            "incomplete": incomplete,
        }

    included, conflicts = resolve_conflicts(complete_by_dir)

    results_base = source_dirs[0].parent
    out_dir = next_result_dir(results_base)
    csv_paths = write_csvs(out_dir, included, dry_run=dry_run)
    merged_config = write_merged_config(out_dir, params, included, dry_run=dry_run)
    provenance, prov_warnings = write_merged_provenance(
        out_dir, source_dirs, params, included, dry_run=dry_run
    )
    warnings.extend(prov_warnings)

    status_counter, reason_counter = count_verdicts(included)

    return {
        "out_dir": out_dir,
        "runs_per_instance": runs_per_instance,
        "expected_algos": expected_algos,
        "per_dir": per_dir,
        "included": included,
        "conflicts": conflicts,
        "csv_paths": csv_paths,
        "config": merged_config,
        "provenance": provenance,
        "warnings": warnings,
        "status_counter": status_counter,
        "reason_counter": reason_counter,
        "dry_run": dry_run,
    }


def report(result):
    """Print the terminal summary. Warnings and the gate go to stderr."""
    runs_per_instance = result["runs_per_instance"]

    print(f"Expected runs per instance: {runs_per_instance} "
          f"(algorithms: {', '.join(result['expected_algos'])})")

    for name, info in result["per_dir"].items():
        print(f"\n{name}")
        print(f"  records read: {info['records']} "
              f"(discarded lines: {info['discarded_lines']})")
        print(f"  complete instances: {len(info['complete'])}")
        for dataset, instance in sorted(info["complete"]):
            print(f"    + {dataset}/{instance}")
        if info["incomplete"]:
            print(f"  incomplete instances (excluded): {len(info['incomplete'])}")
            for key in sorted(info["incomplete"]):
                counts = format_counts(info["incomplete"][key], runs_per_instance)
                print(f"    - {key[0]}/{key[1]} ({counts})")

    if result["conflicts"]:
        print(f"\nInstances complete in more than one directory (excluded as "
              f"ambiguous): {len(result['conflicts'])}")
        for key, dirs in sorted(result["conflicts"].items()):
            print(f"  - {key[0]}/{key[1]} in {', '.join(dirs)}")

    print(f"\nMerged instances: {len(result['included'])}")
    for key, (dir_name, _records) in sorted(result["included"].items()):
        print(f"  {key[0]}/{key[1]} <- {dir_name}")

    prefix = "[dry-run] would write" if result["dry_run"] else "Wrote"
    print(f"\n{prefix} {len(result['csv_paths'])} CSV file(s) to {result['out_dir']}")
    if result["status_counter"]:
        print("  Status: " + ", ".join(
            f"{k}={v}" for k, v in sorted(result["status_counter"].items())))

    for warning in result["warnings"]:
        print(f"WARNING: {warning}", file=sys.stderr)

    if result["reason_counter"]:
        sep = "!" * 72
        print(f"\n{sep}", file=sys.stderr)
        print("VALIDATION GATE: kept runs that are not certified feasible "
              "(exclude them from any sound analysis):", file=sys.stderr)
        for reason, count in result["reason_counter"].most_common():
            print(f"  {reason}: {count}", file=sys.stderr)
        print(f"{sep}\n", file=sys.stderr)

    if not result["included"]:
        print("WARNING: no instance survived the completeness filter; the output "
              "directory holds no CSV.", file=sys.stderr)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Rebuild and merge the final CSVs of interrupted experiment "
                    "runs from their validation logs.",
    )
    parser.add_argument(
        "dirs",
        nargs="+",
        help="Result directories to consolidate (e.g. project/results/result_0007 "
             "project/results/result_0008). The output goes to a new "
             "result_XXXX/ next to the first one.",
    )
    parser.add_argument(
        "--runs-per-instance",
        type=int,
        default=None,
        dest="runs_per_instance",
        help="Override the runs_per_instance taken from config.json.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Report what would be merged without writing anything.",
    )
    args = parser.parse_args(argv)

    if args.runs_per_instance is not None and args.runs_per_instance < 1:
        print("ERROR: --runs-per-instance must be >= 1.", file=sys.stderr)
        return 1

    try:
        result = finalize(args.dirs, args.runs_per_instance, args.dry_run)
    except FinalizeError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1

    report(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
