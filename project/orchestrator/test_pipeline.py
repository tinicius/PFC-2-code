"""Tests for the experiment pipeline's validation gate.

These tests replace the solvers with stub processes whose output is fully
controlled, which is the only way to prove that the orchestrator reacts
correctly to a *misbehaving* solver: real solvers (hopefully) never emit an
infeasible solution, so the failure paths would otherwise stay untested.

Run with::

    python -m unittest discover -s project/orchestrator -p 'test_*.py'
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "validator"))

import run_experiment  # noqa: E402

# Worked example of the problem statement (docs/pt_problem_description.pdf):
# LB=5, UB=12, optimum 5.0 with orders {0,1,2,4} and aisles {1,3}.
EXAMPLE_INSTANCE = """5 5 5
2 0 3 2 1
2 1 1 3 1
2 2 1 4 2
4 0 1 2 2 3 1 4 1
1 1 1
4 0 2 1 1 2 1 4 1
4 0 2 1 1 2 2 4 1
3 1 2 3 1 4 2
4 0 2 1 1 3 1 4 1
4 1 1 2 2 3 1 4 2
5 12
"""

FEASIBLE = {"selected_orders": [0, 1, 2, 4], "visited_aisles": [1, 3]}


def stub(payload=None, exit_code=0, sleep=0.0, raw=None):
    """Return a factory of stub solver commands writing a chosen output."""
    def build(algo, instance_path, solution_path, time_limit, seed):
        script = "import sys, time, json\n"
        if sleep:
            script += f"time.sleep({sleep})\n"
        if raw is not None:
            script += f"open(sys.argv[1], 'w').write({raw!r})\n"
        elif payload is not None:
            script += (
                f"json.dump({payload!r}, open(sys.argv[1], 'w'))\n"
            )
        script += f"sys.exit({exit_code})\n"
        return [sys.executable, "-c", script, solution_path]
    return build


class PipelineTestBase(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        tmp = Path(self._tmp.name)

        datasets = tmp / "datasets" / "ex"
        datasets.mkdir(parents=True)
        (datasets / "example.txt").write_text(EXAMPLE_INSTANCE)

        self.result_dir = tmp / "result_0001"
        (self.result_dir / "temp").mkdir(parents=True)

        self._orig_datasets = run_experiment.DATASETS_DIR
        self._orig_build = run_experiment.build_command
        run_experiment.DATASETS_DIR = tmp / "datasets"

    def tearDown(self):
        run_experiment.DATASETS_DIR = self._orig_datasets
        run_experiment.build_command = self._orig_build
        self._tmp.cleanup()

    def run_task(self, builder, time_limit=10, time_grace=15.0):
        run_experiment.build_command = builder
        task = {
            'dataset': 'ex',
            'instance': 'example.txt',
            'run_id': 1,
            'algo': {'id': 'stub', 'binary': 'ils_vnd'},
            'time_limit': time_limit,
            'time_grace': time_grace,
            'result_dir': str(self.result_dir),
            'seed': 12345,
        }
        seed, dataset, instance, metrics, record = run_experiment.worker(task)
        return metrics, record


class TestGateAcceptsValidRuns(PipelineTestBase):

    def test_feasible_run_is_recorded_with_recomputed_objective(self):
        metrics, record = self.run_task(stub({**FEASIBLE, "exec_time": 1.5}))
        self.assertEqual(metrics['status'], 'feasible')
        self.assertEqual(metrics['reason'], '')
        self.assertAlmostEqual(metrics['objective'], 5.0)
        self.assertEqual(metrics['items'], 10)
        self.assertEqual(metrics['aisles'], 2)
        self.assertAlmostEqual(metrics['exec_time'], 1.5)
        self.assertGreater(metrics['wall_time'], 0.0)
        self.assertTrue(metrics['time_ok'])
        self.assertEqual(record['violations'], [])
        self.assertEqual(record['returncode'], 0)

    def test_objective_is_recomputed_not_trusted(self):
        # Solver understates its own objective: the stored value is the one
        # recomputed from the instance.
        metrics, _ = self.run_task(stub(FEASIBLE))
        self.assertAlmostEqual(metrics['objective'], 5.0)

    def test_wall_time_is_measured_independently_of_the_solver(self):
        # Solver claims 0.01s but actually took ~0.6s of wall clock.
        metrics, _ = self.run_task(stub({**FEASIBLE, "exec_time": 0.01}, sleep=0.6))
        self.assertAlmostEqual(metrics['exec_time'], 0.01)
        self.assertGreaterEqual(metrics['wall_time'], 0.5)

    def test_time_overrun_is_flagged_without_voiding_feasibility(self):
        metrics, _ = self.run_task(
            stub(FEASIBLE, sleep=0.8), time_limit=0, time_grace=0.2
        )
        self.assertEqual(metrics['status'], 'feasible')
        self.assertFalse(metrics['time_ok'])


class TestGateRejectsBadRuns(PipelineTestBase):

    def failed_copies(self):
        failed = self.result_dir / "failed_solutions"
        return sorted(p.name for p in failed.iterdir()) if failed.exists() else []

    def test_infeasible_solution_is_caught(self):
        # Order 4 alone picks 1 unit, below LB=5.
        metrics, record = self.run_task(
            stub({"selected_orders": [4], "visited_aisles": [0]})
        )
        self.assertEqual(metrics['status'], 'infeasible')
        self.assertEqual(metrics['reason'], 'lower_bound')
        self.assertEqual(metrics['objective'], 0.0)
        self.assertTrue(record['violations'])

    def test_infeasible_solution_file_is_preserved_for_audit(self):
        self.run_task(stub({"selected_orders": [4], "visited_aisles": [0]}))
        self.assertEqual(len(self.failed_copies()), 1)

    def test_stock_violation_is_caught(self):
        metrics, _ = self.run_task(
            stub({"selected_orders": [0, 3], "visited_aisles": [2]})
        )
        self.assertEqual(metrics['status'], 'infeasible')
        self.assertEqual(metrics['reason'], 'stock')

    def test_solver_lying_about_its_objective_is_caught(self):
        metrics, _ = self.run_task(stub({**FEASIBLE, "objective": 999.0}))
        self.assertEqual(metrics['status'], 'infeasible')
        self.assertEqual(metrics['reason'], 'objective_mismatch')

    def test_missing_output_file_is_error_not_infeasible(self):
        metrics, record = self.run_task(stub())
        self.assertEqual(metrics['status'], 'error')
        self.assertEqual(metrics['reason'], 'no_output_file')
        self.assertEqual(metrics['objective'], 0.0)

    def test_crashed_solver_records_its_exit_code(self):
        metrics, record = self.run_task(stub(exit_code=3))
        self.assertEqual(metrics['status'], 'error')
        self.assertEqual(record['returncode'], 3)
        self.assertEqual(metrics['returncode'], 3)

    def test_feasible_output_from_a_failing_process_keeps_the_exit_code(self):
        # The solution checks out but the process died afterwards: the CSV must
        # still carry the evidence.
        metrics, _ = self.run_task(stub(FEASIBLE, exit_code=1))
        self.assertEqual(metrics['status'], 'feasible')
        self.assertEqual(metrics['returncode'], 1)

    def test_truncated_output_is_error(self):
        metrics, _ = self.run_task(stub(raw='{"selected_orders":[0,1'))
        self.assertEqual(metrics['status'], 'error')
        self.assertEqual(metrics['reason'], 'solution_format')

    def test_renamed_keys_are_error_not_empty_solution(self):
        metrics, _ = self.run_task(stub({"orders": [0], "aisles": [1]}))
        self.assertEqual(metrics['status'], 'error')
        self.assertEqual(metrics['reason'], 'solution_format')

    def test_no_run_ever_reports_feasible_by_default(self):
        # Guards against the failure mode where a validation step is skipped and
        # the default metrics leak into the results as a valid observation.
        for builder in (stub(), stub(exit_code=1), stub(raw="")):
            metrics, _ = self.run_task(builder)
            self.assertNotEqual(metrics['status'], 'feasible')


class TestConfigValidation(unittest.TestCase):
    """A bad config must be rejected before any run is spent on it."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        tmp = Path(self._tmp.name)
        (tmp / "datasets" / "ex").mkdir(parents=True)
        (tmp / "datasets" / "ex" / "example.txt").write_text(EXAMPLE_INSTANCE)
        self._orig = run_experiment.DATASETS_DIR
        run_experiment.DATASETS_DIR = tmp / "datasets"

    def tearDown(self):
        run_experiment.DATASETS_DIR = self._orig
        self._tmp.cleanup()

    def base(self, **overrides):
        cfg = {
            "datasets": {"ex": ["example.txt"]},
            "algorithms": [{"id": "a1", "binary": "ils_vnd"}],
            "runs_per_instance": 1,
            "time_limit": 10,
            "n_workers": 1,
        }
        cfg.update(overrides)
        return cfg

    def test_valid_config_has_no_problems(self):
        self.assertEqual(run_experiment.validate_config(self.base()), [])

    def test_missing_instance_file_is_reported(self):
        problems = run_experiment.validate_config(
            self.base(datasets={"ex": ["nope.txt"]})
        )
        self.assertTrue(any("not found" in p for p in problems), problems)

    def test_duplicate_algorithm_ids_are_reported(self):
        problems = run_experiment.validate_config(self.base(algorithms=[
            {"id": "dup", "binary": "ils_vnd"},
            {"id": "dup", "binary": "aisle_based"},
        ]))
        self.assertTrue(any("duplicate algorithm id" in p for p in problems), problems)

    def test_duplicate_instances_are_reported(self):
        problems = run_experiment.validate_config(
            self.base(datasets={"ex": ["example.txt", "example.txt"]})
        )
        self.assertTrue(any("more than once" in p for p in problems), problems)

    def test_missing_required_keys_are_reported(self):
        self.assertTrue(run_experiment.validate_config({"datasets": {}}))
        self.assertTrue(run_experiment.validate_config({"algorithms": []}))

    def test_nonpositive_time_limit_is_reported(self):
        problems = run_experiment.validate_config(self.base(time_limit=0))
        self.assertTrue(any("time_limit" in p for p in problems), problems)


class TestProvenance(unittest.TestCase):

    def test_provenance_records_code_revision_and_environment(self):
        prov = run_experiment.collect_provenance(
            {"algorithms": [{"id": "a1"}], "time_limit": 10}, "/tmp/x", 4
        )
        for key in ("git_commit", "git_dirty", "python_version", "cpu_count",
                    "n_workers", "time_limit", "started_at", "algorithms"):
            self.assertIn(key, prov)
        self.assertEqual(prov["n_workers"], 4)
        self.assertEqual(prov["algorithms"], ["a1"])

    def test_java_version_is_captured_from_stderr(self):
        prov = run_experiment.collect_provenance(
            {"algorithms": [], "time_limit": 1}, "/tmp/x", 1
        )
        if prov["java_version"] is not None:
            self.assertIn("version", prov["java_version"].lower())


class TestCommandConstruction(unittest.TestCase):

    def test_ils_vnd_receives_seed_and_time_limit(self):
        cmd = run_experiment.build_command(
            {'id': 'g', 'binary': 'ils_vnd', 'params': {'perturbationStrength': 0.05}},
            "/i.txt", "/o.json", 60, 7,
        )
        self.assertIn("--time-limit=60", cmd)
        self.assertIn("--seed=7", cmd)
        self.assertIn('--params={"perturbationStrength": 0.05}', cmd)

    def test_andre_feijo_gets_no_time_limit_argument(self):
        # Documents a real limitation: the baseline enforces its own hardcoded
        # budget, so time_limit in the config does not apply to it.
        cmd = run_experiment.build_command(
            {'id': 'af', 'binary': 'andre_feijo'}, "/i.txt", "/o.json", 60, 7,
        )
        self.assertFalse([a for a in cmd if a.startswith("--time-limit")])
        self.assertEqual(cmd[-1], "7")


if __name__ == "__main__":
    unittest.main(verbosity=2)
