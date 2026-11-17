"""Test suite for the feasibility gate (``validator.py``).

Two independent sources of truth are used, neither of which shares code with the
validator:

1. The worked example of the problem statement
   (docs/pt_problem_description.pdf, section 3): its instance tables, its three
   documented feasible solutions with their objective values, and its claim that
   the optimum is 5.0.
2. A brute-force oracle written from the dense (order x item) and (aisle x item)
   matrices, enumerating all 2^5 x 2^5 candidate solutions. The oracle decides
   feasibility with plain matrix arithmetic, so agreement with the validator on
   all 1024 candidates exercises both directions of the gate: every feasible
   solution is accepted and every infeasible one is rejected.

Run with::

    python -m unittest discover -s project/validator -p 'test_*.py'
"""

import contextlib
import io
import itertools
import json
import os
import tempfile
import unittest
from pathlib import Path

import validator
from validator import InstanceFormatError

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
DATASETS_DIR = REPO_ROOT / "datasets"

# --------------------------------------------------------------------------- #
# Ground truth transcribed from the problem statement (Tables 1 and 2)
# --------------------------------------------------------------------------- #

# Table 1: units of each item requested by each order.
ORDER_MATRIX = [
    [3, 0, 1, 0, 0],
    [0, 1, 0, 1, 0],
    [0, 0, 1, 0, 2],
    [1, 0, 2, 1, 1],
    [0, 1, 0, 0, 0],
]

# Table 2: units of each item available in each aisle.
AISLE_MATRIX = [
    [2, 1, 1, 0, 1],
    [2, 1, 2, 0, 1],
    [0, 2, 0, 1, 2],
    [2, 1, 0, 1, 1],
    [0, 1, 2, 1, 2],
]

LB, UB = 5, 12


def render_instance(order_matrix, aisle_matrix, lb, ub):
    """Render dense matrices into the instance file format.

    Written independently of ``validator.parse_instance`` so that a parsing bug
    cannot cancel itself out.
    """
    n_orders = len(order_matrix)
    n_items = len(order_matrix[0])
    n_aisles = len(aisle_matrix)

    lines = [f"{n_orders} {n_items} {n_aisles}"]
    for row in list(order_matrix) + list(aisle_matrix):
        entries = [(i, q) for i, q in enumerate(row) if q > 0]
        parts = [str(len(entries))]
        for i, q in entries:
            parts += [str(i), str(q)]
        lines.append(" ".join(parts))
    lines.append(f"{lb} {ub}")
    return "\n".join(lines) + "\n"


def oracle(orders, aisles):
    """Independent feasibility oracle for the example instance.

    :returns: ``(feasible, units, objective)``.
    """
    n_items = len(ORDER_MATRIX[0])
    demand = [0] * n_items
    for o in orders:
        for i in range(n_items):
            demand[i] += ORDER_MATRIX[o][i]

    stock = [0] * n_items
    for a in aisles:
        for i in range(n_items):
            stock[i] += AISLE_MATRIX[a][i]

    units = sum(demand)
    if not orders or not aisles:
        return False, units, 0.0
    if units < LB or units > UB:
        return False, units, 0.0
    if any(demand[i] > stock[i] for i in range(n_items)):
        return False, units, 0.0
    return True, units, units / len(aisles)


class ValidatorTestBase(unittest.TestCase):
    """Provides the example instance on disk plus solution-writing helpers."""

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.tmp_path = Path(cls._tmp.name)
        cls.instance_path = cls.tmp_path / "example.txt"
        cls.instance_path.write_text(
            render_instance(ORDER_MATRIX, AISLE_MATRIX, LB, UB)
        )

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def write_solution(self, payload, name="solution.json"):
        """Write ``payload`` (dict or raw string) and return its path."""
        path = self.tmp_path / name
        if isinstance(payload, str):
            path.write_text(payload)
        else:
            path.write_text(json.dumps(payload))
        return str(path)

    def check(self, orders, aisles, extra=None, **kwargs):
        payload = {"selected_orders": list(orders), "visited_aisles": list(aisles)}
        if extra:
            payload.update(extra)
        return validator.validate(
            str(self.instance_path), self.write_solution(payload), **kwargs
        )


# --------------------------------------------------------------------------- #
# Instance parsing
# --------------------------------------------------------------------------- #

class TestInstanceParsing(ValidatorTestBase):

    def test_parser_reproduces_pdf_tables(self):
        inst = validator.parse_instance(str(self.instance_path))

        self.assertEqual(inst["n_orders"], 5)
        self.assertEqual(inst["n_items"], 5)
        self.assertEqual(inst["n_aisles"], 5)
        self.assertEqual((inst["lb"], inst["ub"]), (LB, UB))

        for o, row in enumerate(ORDER_MATRIX):
            expected = {i: q for i, q in enumerate(row) if q > 0}
            self.assertEqual(inst["orders"][o], expected, f"order {o}")
            self.assertEqual(inst["order_units"][o], sum(row), f"order {o} units")

        for a, row in enumerate(AISLE_MATRIX):
            expected = {i: q for i, q in enumerate(row) if q > 0}
            self.assertEqual(inst["aisles"][a], expected, f"aisle {a}")

    def test_rejects_truncated_instance(self):
        path = self.tmp_path / "truncated.txt"
        text = render_instance(ORDER_MATRIX, AISLE_MATRIX, LB, UB)
        path.write_text("\n".join(text.splitlines()[:-1]) + "\n")
        with self.assertRaises(InstanceFormatError):
            validator.parse_instance(str(path))

    def test_rejects_trailing_tokens(self):
        path = self.tmp_path / "trailing.txt"
        path.write_text(render_instance(ORDER_MATRIX, AISLE_MATRIX, LB, UB) + "7 7\n")
        with self.assertRaises(InstanceFormatError):
            validator.parse_instance(str(path))

    def test_rejects_non_integer_token(self):
        path = self.tmp_path / "float.txt"
        path.write_text(
            render_instance(ORDER_MATRIX, AISLE_MATRIX, LB, UB).replace("3", "3.5", 1)
        )
        with self.assertRaises(InstanceFormatError):
            validator.parse_instance(str(path))

    def test_rejects_item_index_out_of_range(self):
        path = self.tmp_path / "bad_item.txt"
        path.write_text("1 2 1\n1 5 1\n1 0 1\n1 1\n")
        with self.assertRaises(InstanceFormatError):
            validator.parse_instance(str(path))

    def test_rejects_duplicated_item_in_block(self):
        path = self.tmp_path / "dup_item.txt"
        path.write_text("1 2 1\n2 0 1 0 2\n1 0 3\n1 3\n")
        with self.assertRaises(InstanceFormatError):
            validator.parse_instance(str(path))

    def test_rejects_lb_greater_than_ub(self):
        path = self.tmp_path / "bad_bounds.txt"
        path.write_text(render_instance(ORDER_MATRIX, AISLE_MATRIX, 12, 5))
        with self.assertRaises(InstanceFormatError):
            validator.parse_instance(str(path))

    def test_missing_instance_is_error_not_infeasible(self):
        res = validator.validate(
            str(self.tmp_path / "does_not_exist.txt"),
            self.write_solution({"selected_orders": [0], "visited_aisles": [0]}),
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["reason"], "instance_format")


class TestRealInstances(unittest.TestCase):
    """The strict parser must accept every instance actually used in the study."""

    def test_all_shipped_instances_parse(self):
        files = sorted(DATASETS_DIR.glob("*/*.txt"))
        self.assertGreater(len(files), 0, "no instance files found")
        for path in files:
            with self.subTest(instance=str(path.relative_to(DATASETS_DIR))):
                inst = validator.parse_instance(str(path))
                self.assertEqual(len(inst["orders"]), inst["n_orders"])
                self.assertEqual(len(inst["aisles"]), inst["n_aisles"])
                self.assertLessEqual(inst["lb"], inst["ub"])


# --------------------------------------------------------------------------- #
# Documented example solutions
# --------------------------------------------------------------------------- #

class TestDocumentedSolutions(ValidatorTestBase):

    def test_pdf_feasible_examples(self):
        cases = [
            ([0, 4], [0, 1], 5, 2, 2.5),
            ([0, 2, 3], [1, 3, 4], 12, 3, 4.0),
            ([0, 1, 2, 4], [1, 3], 10, 2, 5.0),
        ]
        for orders, aisles, units, n_aisles, obj in cases:
            with self.subTest(orders=orders, aisles=aisles):
                res = self.check(orders, aisles)
                self.assertEqual(res["status"], "feasible", res["message"])
                self.assertEqual(res["items"], units)
                self.assertEqual(res["aisles"], n_aisles)
                self.assertAlmostEqual(res["objective"], obj)

    def test_order_of_indices_is_irrelevant(self):
        a = self.check([0, 1, 2, 4], [1, 3])
        b = self.check([4, 2, 1, 0], [3, 1])
        self.assertEqual(a["status"], "feasible")
        self.assertEqual(b["status"], "feasible")
        self.assertAlmostEqual(a["objective"], b["objective"])


# --------------------------------------------------------------------------- #
# Exhaustive agreement with the brute-force oracle
# --------------------------------------------------------------------------- #

class TestExhaustiveAgreement(ValidatorTestBase):

    def _all_candidates(self):
        idx = range(5)
        order_subsets = [
            c for r in range(6) for c in itertools.combinations(idx, r)
        ]
        aisle_subsets = list(order_subsets)
        return itertools.product(order_subsets, aisle_subsets)

    def test_agrees_with_oracle_on_every_candidate(self):
        n_checked = n_feasible = 0
        best_obj = 0.0
        argmax = []

        for orders, aisles in self._all_candidates():
            expected_ok, expected_units, expected_obj = oracle(orders, aisles)
            res = self.check(orders, aisles)
            n_checked += 1

            self.assertNotEqual(
                res["status"], "error",
                f"well-formed candidate reported as error: {res['message']}",
            )
            self.assertEqual(
                res["status"] == "feasible", expected_ok,
                f"disagreement on orders={orders} aisles={aisles}: "
                f"validator={res['status']} ({res['message']}), "
                f"oracle={'feasible' if expected_ok else 'infeasible'}",
            )

            if expected_ok:
                n_feasible += 1
                self.assertEqual(res["items"], expected_units)
                self.assertEqual(res["aisles"], len(aisles))
                self.assertAlmostEqual(res["objective"], expected_obj)
                if expected_obj > best_obj + 1e-12:
                    best_obj, argmax = expected_obj, [(orders, aisles)]
                elif abs(expected_obj - best_obj) <= 1e-12:
                    argmax.append((orders, aisles))

        self.assertEqual(n_checked, 1024)
        self.assertGreater(n_feasible, 0)
        # The statement asserts the optimum of the example instance is 5.0,
        # attained by orders {0,1,2,4} with aisles {1,3}.
        self.assertAlmostEqual(best_obj, 5.0)
        self.assertIn(
            ({0, 1, 2, 4}, {1, 3}),
            [(set(o), set(a)) for o, a in argmax],
        )


# --------------------------------------------------------------------------- #
# Constraint violations must be caught, one reason code each
# --------------------------------------------------------------------------- #

class TestInfeasibilityDetection(ValidatorTestBase):

    def assert_infeasible(self, res, reason):
        self.assertEqual(res["status"], "infeasible", res)
        self.assertEqual(res["reason"], reason, res["violations"])
        self.assertEqual(res["objective"], 0.0)
        self.assertTrue(res["message"])

    def test_lower_bound_violation(self):
        # Order 4 alone picks 1 unit, below LB=5.
        self.assert_infeasible(self.check([4], [0]), "lower_bound")

    def test_upper_bound_violation(self):
        # All five orders sum to 15 units, above UB=12.
        self.assert_infeasible(self.check([0, 1, 2, 3, 4], [0, 1, 2, 3, 4]),
                               "upper_bound")

    def test_stock_coverage_violation(self):
        # Orders 0 and 3 need 4 units of item 0; aisle 2 stocks none of it.
        self.assert_infeasible(self.check([0, 3], [2]), "stock")

    def test_stock_violation_reports_the_offending_item(self):
        res = self.check([0, 3], [2])
        self.assertIn("item 0", res["message"])

    def test_order_index_out_of_range(self):
        self.assert_infeasible(self.check([0, 5], [1, 3]), "order_index")

    def test_negative_order_index(self):
        self.assert_infeasible(self.check([-1], [1, 3]), "order_index")

    def test_aisle_index_out_of_range(self):
        self.assert_infeasible(self.check([0, 1, 2, 4], [1, 99]), "aisle_index")

    def test_duplicate_orders_rejected(self):
        # Duplicating order 3 would double-count its units if unnoticed.
        self.assert_infeasible(self.check([3, 3], [1, 3]), "duplicate_order")

    def test_duplicate_aisles_rejected(self):
        # Duplicating an aisle would both inflate stock and the denominator.
        self.assert_infeasible(self.check([0, 1, 2, 4], [1, 3, 3]),
                               "duplicate_aisle")

    def test_no_aisle_visited(self):
        self.assert_infeasible(self.check([0, 1, 2, 4], []), "no_aisle")

    def test_empty_solution(self):
        self.assert_infeasible(self.check([], []), "empty_wave")

    def test_all_violations_are_reported(self):
        res = self.check([3, 3], [])
        self.assertEqual(res["status"], "infeasible")
        self.assertGreaterEqual(len(res["violations"]), 2)


class TestSolverClaimCrossCheck(ValidatorTestBase):

    def test_matching_objective_accepted(self):
        res = self.check([0, 1, 2, 4], [1, 3], extra={"objective": 5.0})
        self.assertEqual(res["status"], "feasible", res["message"])

    def test_inflated_objective_rejected(self):
        res = self.check([0, 1, 2, 4], [1, 3], extra={"objective": 42.0})
        self.assertEqual(res["status"], "infeasible")
        self.assertEqual(res["reason"], "objective_mismatch")

    def test_wrong_unit_count_rejected(self):
        res = self.check([0, 1, 2, 4], [1, 3], extra={"items": 99})
        self.assertEqual(res["status"], "infeasible")
        self.assertEqual(res["reason"], "count_mismatch")

    def test_wrong_aisle_count_rejected(self):
        res = self.check([0, 1, 2, 4], [1, 3], extra={"aisles": 1})
        self.assertEqual(res["status"], "infeasible")
        self.assertEqual(res["reason"], "count_mismatch")

    def test_matching_counts_accepted(self):
        res = self.check([0, 1, 2, 4], [1, 3],
                         extra={"items": 10, "aisles": 2, "objective": 5.0})
        self.assertEqual(res["status"], "feasible", res["message"])

    def test_exec_time_within_limit_accepted(self):
        res = self.check([0, 1, 2, 4], [1, 3], extra={"exec_time": 9.5},
                         time_limit=10)
        self.assertEqual(res["status"], "feasible", res["message"])

    def test_exec_time_overrun_rejected(self):
        res = self.check([0, 1, 2, 4], [1, 3], extra={"exec_time": 45.0},
                         time_limit=10)
        self.assertEqual(res["status"], "infeasible")
        self.assertEqual(res["reason"], "time_limit")

    def test_exec_time_ignored_without_limit(self):
        res = self.check([0, 1, 2, 4], [1, 3], extra={"exec_time": 45.0})
        self.assertEqual(res["status"], "feasible", res["message"])


# --------------------------------------------------------------------------- #
# Schema failures must surface as "error", never as "infeasible"
# --------------------------------------------------------------------------- #

class TestSolutionSchema(ValidatorTestBase):

    def assert_schema_error(self, payload):
        res = validator.validate(
            str(self.instance_path), self.write_solution(payload)
        )
        self.assertEqual(res["status"], "error", res)
        self.assertEqual(res["reason"], "solution_format")
        return res

    def test_missing_required_key(self):
        self.assert_schema_error({"selected_orders": [0]})

    def test_renamed_keys_are_not_read_as_empty_solution(self):
        # The dangerous case: a contract drift that would otherwise be scored
        # as a merely bad (empty) solution instead of a broken pipeline.
        self.assert_schema_error({"orders": [0, 1], "aisles": [1, 3]})

    def test_unknown_key(self):
        self.assert_schema_error(
            {"selected_orders": [0], "visited_aisles": [0], "score": 1}
        )

    def test_non_list_field(self):
        self.assert_schema_error(
            {"selected_orders": "0,1", "visited_aisles": [0]}
        )

    def test_float_index(self):
        self.assert_schema_error(
            {"selected_orders": [0, 1.0], "visited_aisles": [1, 3]}
        )

    def test_string_index(self):
        self.assert_schema_error(
            {"selected_orders": ["0"], "visited_aisles": [1]}
        )

    def test_boolean_index(self):
        self.assert_schema_error(
            {"selected_orders": [True], "visited_aisles": [1]}
        )

    def test_nested_list_index(self):
        self.assert_schema_error(
            {"selected_orders": [[0]], "visited_aisles": [1]}
        )

    def test_non_numeric_exec_time(self):
        self.assert_schema_error(
            {"selected_orders": [0], "visited_aisles": [0], "exec_time": "fast"}
        )

    def test_malformed_json(self):
        self.assert_schema_error('{"selected_orders":[0,1],"visited_aisles":[1,')

    def test_empty_file(self):
        self.assert_schema_error("")

    def test_json_array_instead_of_object(self):
        self.assert_schema_error("[[0,1],[1,3]]")

    def test_missing_solution_file(self):
        res = validator.validate(
            str(self.instance_path), str(self.tmp_path / "nope.json")
        )
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["reason"], "solution_format")


class TestOfficialTextFormat(ValidatorTestBase):
    """Reference solutions come in the official challenge text format."""

    def test_feasible_text_solution(self):
        path = self.tmp_path / "ref.txt"
        path.write_text("4\n0\n1\n2\n4\n2\n1\n3\n")
        res = validator.validate(str(self.instance_path), str(path))
        self.assertEqual(res["status"], "feasible", res["message"])
        self.assertAlmostEqual(res["objective"], 5.0)

    def test_infeasible_text_solution_is_caught(self):
        path = self.tmp_path / "ref_bad.txt"
        path.write_text("1\n4\n1\n0\n")
        res = validator.validate(str(self.instance_path), str(path))
        self.assertEqual(res["status"], "infeasible")
        self.assertEqual(res["reason"], "lower_bound")

    def test_declared_count_mismatch_is_error(self):
        path = self.tmp_path / "ref_short.txt"
        path.write_text("4\n0\n1\n2\n4\n2\n1\n3\n99\n")
        res = validator.validate(str(self.instance_path), str(path))
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["reason"], "solution_format")

    def test_non_integer_text_token_is_error(self):
        path = self.tmp_path / "ref_float.txt"
        path.write_text("1\n0.5\n1\n0\n")
        res = validator.validate(str(self.instance_path), str(path))
        self.assertEqual(res["status"], "error")
        self.assertEqual(res["reason"], "solution_format")


class TestBaselineIntegrity(unittest.TestCase):
    """The RPD denominator must itself pass the gate."""

    def test_reference_solutions_are_feasible_and_match_the_table(self):
        import check_best_solutions

        n_checked, problems = check_best_solutions.check(quiet=True)
        self.assertGreater(n_checked, 0)
        self.assertEqual(problems, [], "\n".join(problems))


class TestCli(ValidatorTestBase):
    """Exit codes let shell scripts use the validator as a gate."""

    def test_exit_codes(self):
        feasible = self.write_solution(
            {"selected_orders": [0, 1, 2, 4], "visited_aisles": [1, 3]}, "ok.json"
        )
        infeasible = self.write_solution(
            {"selected_orders": [4], "visited_aisles": [0]}, "bad.json"
        )
        broken = self.write_solution("{", "broken.json")

        def run(argv):
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
                return validator.main(argv)

        self.assertEqual(run([str(self.instance_path), feasible]), 0)
        self.assertEqual(run([str(self.instance_path), infeasible]), 1)
        self.assertEqual(run([str(self.instance_path), broken]), 2)
        self.assertEqual(run([str(self.instance_path)]), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
