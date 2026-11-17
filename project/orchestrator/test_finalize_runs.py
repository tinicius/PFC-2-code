"""Tests for finalize_runs.py, the recovery/merge path of the pipeline.

The rules under test are the ones that decide *which numbers get reported*: a
partial sample must never reach a CSV, and an instance whose complete sample
exists in two directories must not be silently resolved in favour of one of
them. Both failures would be invisible in the output, hence the tests.

Run with::

    python -m unittest discover -s project/orchestrator -p 'test_*.py'
"""

import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "validator"))

import finalize_runs  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
RESULTS_BASE = REPO_ROOT / "project" / "results"

BASE_CONFIG = {
    "datasets": {"b": ["instance_0001.txt"]},
    "algorithms": [{"id": "andre_feijo", "binary": "andre_feijo"}],
    "runs_per_instance": 3,
    "time_limit": 650,
    "n_workers": 1,
}


def record(dataset="b", instance="instance_0001.txt", algo="andre_feijo", run_id=1,
           status="feasible", **extra):
    """One audit record shaped like the ones worker() writes."""
    rec = {
        "dataset": dataset,
        "instance": instance,
        "algo_id": algo,
        "run_id": run_id,
        "seed": 1000 + run_id,
        "cmd": ["java", "-jar", "solver.jar"],
        "time_limit": 650,
        "violations": [],
        "stdout_tail": "noise",
        "stderr_tail": "",
        "status": status,
        "reason": "" if status == "feasible" else "no_output_file",
        "objective": 12.5,
        "items": 100,
        "aisles": 8,
        "exec_time": 1.5,
        "wall_time": 1.6,
        "time_ok": True,
        "returncode": 0,
    }
    rec.update(extra)
    return rec


def make_result_dir(base: Path, name: str, records, config=None, provenance=None,
                    raw_log=None) -> Path:
    """Build a result directory as an interrupted run would leave it."""
    result_dir = base / name
    result_dir.mkdir(parents=True)

    cfg = json.loads(json.dumps(BASE_CONFIG)) if config is None else config
    (result_dir / "config.json").write_text(json.dumps(cfg))

    if provenance is not None:
        (result_dir / "provenance.json").write_text(json.dumps(provenance))

    log = result_dir / "validation_log.jsonl"
    if raw_log is not None:
        log.write_text(raw_log)
    else:
        log.write_text("".join(json.dumps(r) + "\n" for r in records))

    # The interrupted runner never cleans temp/; its presence is part of the
    # scenario and must survive the merge untouched.
    (result_dir / "temp").mkdir()
    (result_dir / "temp" / "leftover.json").write_text("{}")
    return result_dir


class TempDirTestCase(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.base = Path(self._tmp.name) / "results"
        self.base.mkdir(parents=True)

    def tearDown(self):
        self._tmp.cleanup()


class ConfigCompatibilityTest(unittest.TestCase):

    def test_datasets_may_differ(self):
        a = dict(BASE_CONFIG, datasets={"b": ["instance_0001.txt"]})
        b = dict(BASE_CONFIG, datasets={"b": ["instance_0009.txt"]})
        problems = finalize_runs.check_compatible({
            "a": finalize_runs.experiment_params(a),
            "b": finalize_runs.experiment_params(b),
        })
        self.assertEqual(problems, [])

    def test_omitted_default_equals_explicit_default(self):
        a = dict(BASE_CONFIG)
        b = dict(BASE_CONFIG, time_grace=finalize_runs.PARAM_DEFAULTS["time_grace"])
        problems = finalize_runs.check_compatible({
            "a": finalize_runs.experiment_params(a),
            "b": finalize_runs.experiment_params(b),
        })
        self.assertEqual(problems, [])

    def test_runs_per_instance_mismatch_is_reported(self):
        a = dict(BASE_CONFIG)
        b = dict(BASE_CONFIG, runs_per_instance=30)
        problems = finalize_runs.check_compatible({
            "a": finalize_runs.experiment_params(a),
            "b": finalize_runs.experiment_params(b),
        })
        self.assertEqual(len(problems), 1)
        self.assertIn("runs_per_instance", problems[0])

    def test_algorithm_params_mismatch_is_reported(self):
        a = dict(BASE_CONFIG, algorithms=[{"id": "ils_vnd", "binary": "ils_vnd",
                                           "params": {"perturbationStrength": 0.05}}])
        b = dict(BASE_CONFIG, algorithms=[{"id": "ils_vnd", "binary": "ils_vnd",
                                           "params": {"perturbationStrength": 0.1}}])
        problems = finalize_runs.check_compatible({
            "a": finalize_runs.experiment_params(a),
            "b": finalize_runs.experiment_params(b),
        })
        self.assertEqual(len(problems), 1)
        self.assertIn("algorithms", problems[0])

    def test_n_workers_mismatch_is_reported(self):
        a = dict(BASE_CONFIG)
        b = dict(BASE_CONFIG, n_workers=8)
        problems = finalize_runs.check_compatible({
            "a": finalize_runs.experiment_params(a),
            "b": finalize_runs.experiment_params(b),
        })
        self.assertEqual(len(problems), 1)
        self.assertIn("n_workers", problems[0])


class LoadRecordsTest(TempDirTestCase):

    def _log(self, text) -> Path:
        path = self.base / "validation_log.jsonl"
        path.write_text(text)
        return path

    def test_well_formed_log(self):
        text = "".join(json.dumps(record(run_id=i)) + "\n" for i in (1, 2, 3))
        records, warnings = finalize_runs.load_records(self._log(text))
        self.assertEqual(len(records), 3)
        self.assertEqual(warnings, [])

    def test_truncated_final_line_is_tolerated(self):
        text = json.dumps(record(run_id=1)) + "\n" + json.dumps(record(run_id=2))[:40]
        records, warnings = finalize_runs.load_records(self._log(text))
        self.assertEqual(len(records), 1)
        self.assertEqual(len(warnings), 1)
        self.assertIn("final line truncated", warnings[0])

    def test_corrupt_middle_line_is_skipped(self):
        text = (json.dumps(record(run_id=1)) + "\n"
                + "{not json\n"
                + json.dumps(record(run_id=3)) + "\n")
        records, warnings = finalize_runs.load_records(self._log(text))
        self.assertEqual([r["run_id"] for r in records], [1, 3])
        self.assertIn("line 2 corrupt", warnings[0])

    def test_blank_trailing_line_is_not_a_warning(self):
        text = json.dumps(record(run_id=1)) + "\n\n"
        records, warnings = finalize_runs.load_records(self._log(text))
        self.assertEqual(len(records), 1)
        self.assertEqual(warnings, [])

    def test_record_missing_key_fields_is_skipped(self):
        broken = record(run_id=2)
        del broken["instance"]
        text = json.dumps(record(run_id=1)) + "\n" + json.dumps(broken) + "\n"
        records, warnings = finalize_runs.load_records(self._log(text))
        self.assertEqual(len(records), 1)
        self.assertIn("instance", warnings[0])

    def test_missing_log_aborts(self):
        with self.assertRaises(finalize_runs.FinalizeError):
            finalize_runs.load_records(self.base / "absent.jsonl")


class CompletenessTest(unittest.TestCase):

    def test_full_sample_is_complete(self):
        records = [record(run_id=i) for i in (1, 2, 3)]
        complete, incomplete, warnings = finalize_runs.group_and_filter(
            records, ["andre_feijo"], 3)
        self.assertEqual(list(complete), [("b", "instance_0001.txt")])
        self.assertEqual(incomplete, {})
        self.assertEqual(warnings, [])

    def test_partial_sample_is_excluded(self):
        records = [record(run_id=i) for i in (1, 2)]
        complete, incomplete, _ = finalize_runs.group_and_filter(
            records, ["andre_feijo"], 3)
        self.assertEqual(complete, {})
        self.assertEqual(incomplete[("b", "instance_0001.txt")], {"andre_feijo": 2})

    def test_instance_needs_every_algorithm_complete(self):
        records = [record(run_id=i) for i in (1, 2, 3)]
        records += [record(algo="ils_vnd", run_id=i) for i in (1, 2)]
        complete, incomplete, _ = finalize_runs.group_and_filter(
            records, ["andre_feijo", "ils_vnd"], 3)
        self.assertEqual(complete, {})
        self.assertEqual(incomplete[("b", "instance_0001.txt")],
                         {"andre_feijo": 3, "ils_vnd": 2})

    def test_run_ids_need_not_start_at_one(self):
        records = [record(run_id=i) for i in (31, 32, 33)]
        complete, incomplete, _ = finalize_runs.group_and_filter(
            records, ["andre_feijo"], 3)
        self.assertEqual(list(complete), [("b", "instance_0001.txt")])
        self.assertEqual(incomplete, {})

    def test_duplicate_run_id_keeps_last_and_warns(self):
        records = [record(run_id=1, objective=1.0), record(run_id=1, objective=2.0),
                   record(run_id=2), record(run_id=3)]
        complete, incomplete, warnings = finalize_runs.group_and_filter(
            records, ["andre_feijo"], 3)
        kept = complete[("b", "instance_0001.txt")]
        self.assertEqual(len(kept), 3)
        self.assertEqual(kept[0]["objective"], 2.0)
        self.assertIn("duplicate record", warnings[0])

    def test_extra_algorithm_in_log_blocks_the_instance(self):
        records = [record(run_id=i) for i in (1, 2, 3)]
        records.append(record(algo="ghost", run_id=1))
        complete, incomplete, _ = finalize_runs.group_and_filter(
            records, ["andre_feijo"], 3)
        self.assertEqual(complete, {})
        self.assertIn("ghost", incomplete[("b", "instance_0001.txt")])

    def test_more_runs_than_expected_is_excluded_with_warning(self):
        records = [record(run_id=i) for i in (1, 2, 3, 4)]
        complete, incomplete, warnings = finalize_runs.group_and_filter(
            records, ["andre_feijo"], 3)
        self.assertEqual(complete, {})
        self.assertTrue(incomplete)
        self.assertIn("4 runs but 3 were expected", warnings[0])


class ConflictResolutionTest(unittest.TestCase):

    def test_unique_owner_is_included(self):
        included, conflicts = finalize_runs.resolve_conflicts({
            "dir_a": {("b", "i1.txt"): ["ra"]},
            "dir_b": {("b", "i2.txt"): ["rb"]},
        })
        self.assertEqual(included[("b", "i1.txt")], ("dir_a", ["ra"]))
        self.assertEqual(included[("b", "i2.txt")], ("dir_b", ["rb"]))
        self.assertEqual(conflicts, {})

    def test_complete_in_two_dirs_is_dropped(self):
        included, conflicts = finalize_runs.resolve_conflicts({
            "dir_a": {("b", "i1.txt"): ["ra"]},
            "dir_b": {("b", "i1.txt"): ["rb"]},
        })
        self.assertEqual(included, {})
        self.assertEqual(conflicts[("b", "i1.txt")], ["dir_a", "dir_b"])

    def test_partial_elsewhere_does_not_veto_the_complete_sample(self):
        # instance_0012 of result_0007 (5/30) vs result_0008 (30/30): the partial
        # attempt was already removed by the completeness filter, so no conflict.
        included, conflicts = finalize_runs.resolve_conflicts({
            "dir_a": {},
            "dir_b": {("b", "i12.txt"): ["rb"]},
        })
        self.assertEqual(included[("b", "i12.txt")], ("dir_b", ["rb"]))
        self.assertEqual(conflicts, {})

    def test_three_dirs_conflict_lists_all(self):
        included, conflicts = finalize_runs.resolve_conflicts({
            "a": {("b", "i1.txt"): ["1"]},
            "b": {("b", "i1.txt"): ["2"]},
            "c": {("b", "i1.txt"): ["3"]},
        })
        self.assertEqual(included, {})
        self.assertEqual(conflicts[("b", "i1.txt")], ["a", "b", "c"])


class OutputTest(TempDirTestCase):

    def test_next_result_dir_increments(self):
        (self.base / "result_0007").mkdir()
        (self.base / "result_0008").mkdir()
        (self.base / "not_a_result").mkdir()
        self.assertEqual(finalize_runs.next_result_dir(self.base).name, "result_0009")

    def test_next_result_dir_on_empty_base(self):
        self.assertEqual(finalize_runs.next_result_dir(self.base).name, "result_0001")

    def test_csv_schema_order_and_no_extra_columns(self):
        records = [record(algo="ils_vnd", run_id=2), record(algo="ils_vnd", run_id=1),
                   record(algo="andre_feijo", run_id=2)]
        out_dir = self.base / "out"
        finalize_runs.write_csvs(out_dir, {("b", "instance_0001.txt"): ("d", records)})

        path = out_dir / "b" / "instance_0001.csv"
        with open(path, newline="") as f:
            reader = csv.DictReader(f)
            self.assertEqual(reader.fieldnames, finalize_runs.CSV_FIELDS)
            rows = list(reader)
        self.assertEqual([(r["algo_id"], r["run_id"]) for r in rows],
                         [("andre_feijo", "2"), ("ils_vnd", "1"), ("ils_vnd", "2")])
        for dropped in ("cmd", "stdout_tail", "violations"):
            self.assertNotIn(dropped, rows[0])

    def test_dry_run_writes_nothing(self):
        out_dir = self.base / "out"
        included = {("b", "instance_0001.txt"): ("d", [record()])}
        paths = finalize_runs.write_csvs(out_dir, included, dry_run=True)
        self.assertEqual(len(paths), 1)
        self.assertFalse(out_dir.exists())

    def test_merged_config_lists_only_written_instances(self):
        included = {
            ("b", "instance_0002.txt"): ("d", [record()]),
            ("b", "instance_0001.txt"): ("d", [record()]),
            ("a", "instance_0005.txt"): ("d", [record()]),
        }
        out_dir = self.base / "out"
        config = finalize_runs.write_merged_config(
            out_dir, finalize_runs.experiment_params(BASE_CONFIG), included)
        self.assertEqual(config["datasets"], {
            "a": ["instance_0005.txt"],
            "b": ["instance_0001.txt", "instance_0002.txt"],
        })
        self.assertEqual(config["runs_per_instance"], 3)
        self.assertEqual(json.loads((out_dir / "config.json").read_text()), config)

    def test_divergent_source_commits_warn(self):
        dir_a = make_result_dir(self.base, "result_0001", [],
                               provenance={"git_commit": "aaa", "started_at": "t1"})
        dir_b = make_result_dir(self.base, "result_0002", [],
                               provenance={"git_commit": "bbb", "started_at": "t2"})
        _prov, warnings = finalize_runs.write_merged_provenance(
            self.base / "out", [dir_a, dir_b],
            finalize_runs.experiment_params(BASE_CONFIG), {}, dry_run=True)
        self.assertEqual(len(warnings), 1)
        self.assertIn("different git commits", warnings[0])


class VerdictCountingTest(unittest.TestCase):

    def test_counts_cover_status_overrun_and_nonzero_exit(self):
        included = {
            ("b", "i1.txt"): ("d", [
                record(run_id=1),
                record(run_id=2, status="infeasible"),
                record(run_id=3, time_ok=False),
                record(run_id=4, returncode=1),
            ]),
        }
        status, reason = finalize_runs.count_verdicts(included)
        self.assertEqual(status["feasible"], 3)
        self.assertEqual(status["infeasible"], 1)
        self.assertEqual(reason["no_output_file"], 1)
        self.assertEqual(reason["time_overrun"], 1)
        self.assertEqual(reason["nonzero_exit_on_feasible_run"], 1)


class FinalizeEndToEndTest(TempDirTestCase):

    def _two_dirs(self):
        """result_0007-like pair: A complete for i1, partial for i2; B completes i2."""
        a_records = [record(instance="instance_0001.txt", run_id=i) for i in (1, 2, 3)]
        a_records += [record(instance="instance_0002.txt", run_id=1)]
        b_records = [record(instance="instance_0002.txt", run_id=i) for i in (1, 2, 3)]
        b_records += [record(instance="instance_0003.txt", run_id=1)]

        dir_a = make_result_dir(
            self.base, "result_0007", a_records,
            config=dict(BASE_CONFIG,
                        datasets={"b": ["instance_0001.txt", "instance_0002.txt"]}),
            provenance={"git_commit": "abc", "started_at": "t1", "hostname": "h",
                        "n_workers": 1, "git_dirty": True})
        dir_b = make_result_dir(
            self.base, "result_0008", b_records,
            config=dict(BASE_CONFIG,
                        datasets={"b": ["instance_0002.txt", "instance_0003.txt"]}),
            provenance={"git_commit": "abc", "started_at": "t2", "hostname": "h",
                        "n_workers": 1, "git_dirty": True})
        return dir_a, dir_b

    def test_merge_writes_expected_artefacts(self):
        dir_a, dir_b = self._two_dirs()
        result = finalize_runs.finalize([dir_a, dir_b])

        out_dir = result["out_dir"]
        self.assertEqual(out_dir.name, "result_0009")
        self.assertEqual(sorted(result["included"]), [
            ("b", "instance_0001.txt"), ("b", "instance_0002.txt")])
        # instance_0002 comes from the directory where it is complete.
        self.assertEqual(result["included"][("b", "instance_0002.txt")][0], str(dir_b))
        self.assertEqual(result["conflicts"], {})

        self.assertTrue((out_dir / "b" / "instance_0001.csv").exists())
        self.assertTrue((out_dir / "b" / "instance_0002.csv").exists())
        self.assertFalse((out_dir / "b" / "instance_0003.csv").exists())

        provenance = json.loads((out_dir / "provenance.json").read_text())
        self.assertEqual(provenance["merged_from"], [str(dir_a), str(dir_b)])
        self.assertEqual(provenance["instances"], 2)

        # Sources untouched: temp/ still there, no CSV and no failed_solutions.
        for source in (dir_a, dir_b):
            self.assertTrue((source / "temp" / "leftover.json").exists())
            self.assertEqual(list(source.glob("*/*.csv")), [])
            self.assertFalse((source / "failed_solutions").exists())

    def test_directory_order_does_not_change_result(self):
        dir_a, dir_b = self._two_dirs()
        forward = finalize_runs.finalize([dir_a, dir_b], dry_run=True)
        backward = finalize_runs.finalize([dir_b, dir_a], dry_run=True)
        self.assertEqual(sorted(forward["included"]), sorted(backward["included"]))

    def test_conflicting_instance_is_dropped(self):
        records = [record(run_id=i) for i in (1, 2, 3)]
        dir_a = make_result_dir(self.base, "result_0001", records)
        dir_b = make_result_dir(self.base, "result_0002", records)
        result = finalize_runs.finalize([dir_a, dir_b], dry_run=True)
        self.assertEqual(result["included"], {})
        self.assertEqual(result["conflicts"][("b", "instance_0001.txt")],
                         [str(dir_a), str(dir_b)])

    def test_single_directory_is_just_recovery(self):
        records = [record(run_id=i) for i in (1, 2, 3)]
        dir_a = make_result_dir(self.base, "result_0007", records)
        result = finalize_runs.finalize([dir_a])
        self.assertEqual(len(result["csv_paths"]), 1)
        self.assertTrue((result["out_dir"] / "b" / "instance_0001.csv").exists())

    def test_runs_per_instance_override(self):
        records = [record(run_id=i) for i in (1, 2)]
        dir_a = make_result_dir(self.base, "result_0007", records)
        self.assertEqual(finalize_runs.finalize([dir_a], dry_run=True)["included"], {})
        overridden = finalize_runs.finalize([dir_a], runs_per_instance_override=2,
                                           dry_run=True)
        self.assertEqual(len(overridden["included"]), 1)

    def test_incompatible_configs_abort(self):
        records = [record(run_id=i) for i in (1, 2, 3)]
        dir_a = make_result_dir(self.base, "result_0001", records)
        dir_b = make_result_dir(self.base, "result_0002", records,
                                config=dict(BASE_CONFIG, time_limit=60))
        with self.assertRaises(finalize_runs.FinalizeError) as ctx:
            finalize_runs.finalize([dir_a, dir_b], dry_run=True)
        self.assertIn("time_limit", str(ctx.exception))

    def test_same_directory_twice_aborts(self):
        dir_a = make_result_dir(self.base, "result_0001", [record()])
        with self.assertRaises(finalize_runs.FinalizeError):
            finalize_runs.finalize([dir_a, dir_a], dry_run=True)

    def test_missing_directory_aborts(self):
        with self.assertRaises(finalize_runs.FinalizeError):
            finalize_runs.finalize([self.base / "nope"], dry_run=True)

    def test_missing_config_aborts(self):
        dir_a = make_result_dir(self.base, "result_0001", [record()])
        (dir_a / "config.json").unlink()
        with self.assertRaises(finalize_runs.FinalizeError):
            finalize_runs.finalize([dir_a], dry_run=True)

    def test_cli_returns_zero_and_reports(self):
        dir_a, dir_b = self._two_dirs()
        code = finalize_runs.main([str(dir_a), str(dir_b), "--dry-run"])
        self.assertEqual(code, 0)

    def test_cli_rejects_bad_override(self):
        dir_a = make_result_dir(self.base, "result_0001", [record()])
        self.assertEqual(finalize_runs.main([str(dir_a), "--runs-per-instance", "0"]), 1)


@unittest.skipUnless((RESULTS_BASE / "result_0007").is_dir()
                     and (RESULTS_BASE / "result_0008").is_dir(),
                     "result_0007/result_0008 not available")
class RealResultDirsTest(unittest.TestCase):
    """Read-only check against the actual interrupted runs in the repository."""

    def test_dry_run_merge_of_0007_and_0008(self):
        result = finalize_runs.finalize(
            [RESULTS_BASE / "result_0007", RESULTS_BASE / "result_0008"],
            dry_run=True)

        included = sorted(instance for _ds, instance in result["included"])
        expected = [f"instance_{i:04d}.txt" for i in range(1, 14)]
        self.assertEqual(included, expected)
        self.assertEqual(result["conflicts"], {})
        self.assertEqual(result["runs_per_instance"], 30)

        # instance_0012 is partial in result_0007 (5/30) and complete in 0008.
        owner, records = result["included"][("b", "instance_0012.txt")]
        self.assertEqual(owner, str(RESULTS_BASE / "result_0008"))
        self.assertEqual(len(records), 30)
        self.assertEqual(
            result["per_dir"][str(RESULTS_BASE / "result_0007")]
            ["incomplete"][("b", "instance_0012.txt")],
            {"andre_feijo": 5})
        self.assertEqual(
            result["per_dir"][str(RESULTS_BASE / "result_0008")]
            ["incomplete"][("b", "instance_0014.txt")],
            {"andre_feijo": 7})
        self.assertEqual(sum(result["status_counter"].values()), 13 * 30)


if __name__ == "__main__":
    unittest.main()
