"""Independent feasibility checker for the SBPO 2025 wave order picking problem.

This module is the *gate* of the experimental pipeline: it re-reads the instance
from disk and re-checks the solution written by a solver, sharing no code and no
data structure with any solver. A solution is only reported as ``feasible`` when
every constraint of the problem statement is verified here, from scratch.

Problem statement (docs/pt_problem_description.pdf), for a solution
(O', A') with O' a set of order indices and A' a set of aisle indices:

    maximize  sum_{o in O'} sum_{i in I_o} u_oi / |A'|                       (1)
    s.t.      sum_{o in O'} sum_{i in I_o} u_oi >= LB                        (2)
              sum_{o in O'} sum_{i in I_o} u_oi <= UB                        (3)
              sum_{o in O'} u_oi <= sum_{a in A'} u_ai   for every item i    (4)

plus the implicit requirement |A'| >= 1 (otherwise (1) is undefined).

Status semantics (deliberately three-valued):

    "feasible"    every constraint verified; ``objective`` is trustworthy.
    "infeasible"  the solver produced a well-formed answer that violates the
                  model. This is a solver bug and must never be silently
                  discarded from the analysis.
    "error"       the answer could not be checked at all (missing file, broken
                  JSON, wrong schema, unreadable instance). This is a pipeline
                  bug, NOT evidence about the solver's search quality.

Never collapse "error" into "infeasible": the first means "unknown", the second
means "wrong". Reporting one as the other silently biases the experiment.

Return contract of :func:`validate`::

    {
      "status":    "feasible" | "infeasible" | "error",
      "objective": float,   # recomputed here; 0.0 when not feasible
      "items":     int,     # total units of the selected orders
      "aisles":    int,     # number of visited aisles
      "reason":    str,     # machine-readable code, "" when feasible
      "message":   str,     # human-readable explanation, "" when feasible
      "violations": [str],  # every violation found, not just the first
    }

CLI::

    python validator.py <instance> <solution> [--time-limit=<seconds>]
    exit code 0 = feasible, 1 = infeasible, 2 = error
"""

import json
import sys
from collections import defaultdict

# Relative tolerance used when cross-checking a solver's self-reported objective
# against the objective recomputed here.
OBJECTIVE_TOLERANCE = 1e-6

# Slack, in seconds, allowed on top of the time limit before a run is reported
# as having overrun it (JVM shutdown, solution writing, clock granularity).
TIME_LIMIT_SLACK = 1.0


class InstanceFormatError(Exception):
    """Raised when an instance file does not conform to the expected format."""


class SolutionFormatError(Exception):
    """Raised when a solution file does not conform to the expected schema."""


# --------------------------------------------------------------------------- #
# Instance parsing
# --------------------------------------------------------------------------- #

def parse_instance(instance_path):
    """Parse and *validate* a warehouse instance file.

    File format (whitespace separated, integers only)::

        nOrders nItems nAisles
        nOrderItems  (itemIdx itemQty) * nOrderItems      x nOrders
        nAisleItems  (itemIdx itemQty) * nAisleItems       x nAisles
        LB UB

    The parser is strict on purpose: a silently desynchronised read would make
    every downstream feasibility check meaningless. It therefore rejects
    non-integer tokens, out-of-range item indices, negative quantities,
    duplicated item entries inside the same order/aisle, LB > UB and any
    trailing or missing token.

    :raises InstanceFormatError: if the file is malformed.
    """
    try:
        with open(instance_path, "r") as f:
            raw = f.read()
    except OSError as e:
        raise InstanceFormatError(f"cannot read instance file: {e}") from e

    tokens = raw.split()
    pos = 0

    def next_int(what):
        nonlocal pos
        if pos >= len(tokens):
            raise InstanceFormatError(
                f"unexpected end of file while reading {what} "
                f"(consumed {pos} tokens)"
            )
        tok = tokens[pos]
        try:
            val = int(tok)
        except ValueError as e:
            raise InstanceFormatError(
                f"non-integer token {tok!r} at position {pos} while reading {what}"
            ) from e
        pos += 1
        return val

    n_orders = next_int("nOrders")
    n_items = next_int("nItems")
    n_aisles = next_int("nAisles")

    if n_orders < 1:
        raise InstanceFormatError(f"nOrders must be >= 1, got {n_orders}")
    if n_items < 1:
        raise InstanceFormatError(f"nItems must be >= 1, got {n_items}")
    if n_aisles < 1:
        raise InstanceFormatError(f"nAisles must be >= 1, got {n_aisles}")

    def read_blocks(count, kind):
        blocks = []
        for b in range(count):
            n_entries = next_int(f"{kind} {b} entry count")
            if n_entries < 0 or n_entries > n_items:
                raise InstanceFormatError(
                    f"{kind} {b} declares {n_entries} item entries, "
                    f"expected 0..{n_items}"
                )
            block = {}
            for _ in range(n_entries):
                item = next_int(f"{kind} {b} item index")
                qty = next_int(f"{kind} {b} item quantity")
                if item < 0 or item >= n_items:
                    raise InstanceFormatError(
                        f"{kind} {b} references item {item}, "
                        f"outside [0, {n_items - 1}]"
                    )
                if qty < 0:
                    raise InstanceFormatError(
                        f"{kind} {b} declares negative quantity {qty} "
                        f"for item {item}"
                    )
                if item in block:
                    raise InstanceFormatError(
                        f"{kind} {b} lists item {item} twice; the aggregation "
                        f"rule is ambiguous, refusing to guess"
                    )
                block[item] = qty
            blocks.append(block)
        return blocks

    orders = read_blocks(n_orders, "order")
    aisles = read_blocks(n_aisles, "aisle")

    lb = next_int("LB")
    ub = next_int("UB")

    if lb < 0:
        raise InstanceFormatError(f"LB must be >= 0, got {lb}")
    if ub < lb:
        raise InstanceFormatError(f"UB={ub} is smaller than LB={lb}")

    if pos != len(tokens):
        raise InstanceFormatError(
            f"{len(tokens) - pos} trailing token(s) after LB/UB; "
            f"the file does not match the expected layout"
        )

    order_units = [sum(o.values()) for o in orders]

    return {
        "n_orders": n_orders,
        "n_items": n_items,
        "n_aisles": n_aisles,
        "orders": orders,
        "aisles": aisles,
        "order_units": order_units,
        "lb": lb,
        "ub": ub,
    }


# --------------------------------------------------------------------------- #
# Solution parsing
# --------------------------------------------------------------------------- #

_REQUIRED_KEYS = ("selected_orders", "visited_aisles")
_OPTIONAL_KEYS = ("exec_time", "objective", "items", "aisles")


def _as_index_list(value, field):
    """Coerce/validate a JSON value into a list of plain non-negative ints."""
    if not isinstance(value, list):
        raise SolutionFormatError(
            f"'{field}' must be a JSON array, got {type(value).__name__}"
        )
    out = []
    for k, v in enumerate(value):
        # bool is a subclass of int in Python; reject it explicitly.
        if isinstance(v, bool) or not isinstance(v, int):
            raise SolutionFormatError(
                f"'{field}'[{k}] must be an integer, got {v!r} "
                f"({type(v).__name__})"
            )
        out.append(v)
    return out


def parse_solution_text(raw):
    """Parse the official challenge output format.

    Layout (whitespace separated integers)::

        |O'|
        order index      x |O'|
        |A'|
        aisle index      x |A'|

    Used by the reference solutions kept in ``best_solutions/``, which feed the
    RPD baseline and therefore must pass through the same gate as solver output.
    """
    tokens = raw.split()
    pos = 0

    def take(what):
        nonlocal pos
        if pos >= len(tokens):
            raise SolutionFormatError(f"unexpected end of file while reading {what}")
        try:
            val = int(tokens[pos])
        except ValueError as e:
            raise SolutionFormatError(
                f"non-integer token {tokens[pos]!r} while reading {what}"
            ) from e
        pos += 1
        return val

    def take_block(what):
        count = take(f"{what} count")
        if count < 0:
            raise SolutionFormatError(f"negative {what} count: {count}")
        return [take(f"{what} index") for _ in range(count)]

    orders = take_block("order")
    aisles = take_block("aisle")

    if pos != len(tokens):
        raise SolutionFormatError(
            f"{len(tokens) - pos} trailing token(s) in text solution"
        )

    return {"selected_orders": orders, "visited_aisles": aisles}


def parse_solution(solution_path):
    """Parse and schema-validate a solution file.

    Accepts the pipeline's JSON schema and, for reference solutions, the
    official challenge text format. The format is detected from the content.

    :raises SolutionFormatError: if the file is missing, unparseable, or does
        not carry exactly the expected schema.
    """
    try:
        with open(solution_path, "r") as f:
            raw = f.read()
    except OSError as e:
        raise SolutionFormatError(f"cannot read solution file: {e}") from e

    if not raw.strip():
        raise SolutionFormatError("solution file is empty (truncated write?)")

    if not raw.lstrip().startswith(("{", "[")):
        return parse_solution_text(raw)

    try:
        sol = json.loads(raw)
    except ValueError as e:
        raise SolutionFormatError(f"invalid JSON: {e}") from e

    if not isinstance(sol, dict):
        raise SolutionFormatError(
            f"solution must be a JSON object, got {type(sol).__name__}"
        )

    missing = [k for k in _REQUIRED_KEYS if k not in sol]
    if missing:
        raise SolutionFormatError(
            f"missing required key(s): {', '.join(missing)}. "
            f"A missing key is a schema error, not an empty solution."
        )

    unknown = [k for k in sol if k not in _REQUIRED_KEYS + _OPTIONAL_KEYS]
    if unknown:
        raise SolutionFormatError(
            f"unknown key(s) in solution: {', '.join(sorted(unknown))}"
        )

    parsed = {
        "selected_orders": _as_index_list(sol["selected_orders"], "selected_orders"),
        "visited_aisles": _as_index_list(sol["visited_aisles"], "visited_aisles"),
    }

    for key in ("exec_time", "objective"):
        if key in sol:
            value = sol[key]
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise SolutionFormatError(
                    f"'{key}' must be a number, got {value!r}"
                )
            parsed[key] = float(value)

    for key in ("items", "aisles"):
        if key in sol:
            value = sol[key]
            if isinstance(value, bool) or not isinstance(value, int):
                raise SolutionFormatError(
                    f"'{key}' must be an integer, got {value!r}"
                )
            parsed[key] = value

    return parsed


# --------------------------------------------------------------------------- #
# Validation
# --------------------------------------------------------------------------- #

def _result(status, reason, violations, objective=0.0, items=0, aisles=0):
    return {
        "status": status,
        "objective": objective,
        "items": items,
        "aisles": aisles,
        "reason": reason,
        "message": violations[0] if violations else "",
        "violations": violations,
    }


def validate(instance_path, solution_path, time_limit=None):
    """Validate a solution file against its instance file.

    All violations are collected, not only the first one, so that a failing run
    can be diagnosed from the log without re-running it.

    :param instance_path: path to the instance file.
    :param solution_path: path to the solution JSON produced by a solver.
    :param time_limit: optional time limit in seconds; when given and the
        solution reports ``exec_time``, an overrun is reported as a violation
        (status ``infeasible``, reason ``time_limit``), because a run that used
        more budget than its competitors is not a comparable observation.
    :returns: dict following the module's return contract.
    """
    try:
        instance = parse_instance(instance_path)
    except InstanceFormatError as e:
        return _result("error", "instance_format", [f"instance: {e}"])

    try:
        solution = parse_solution(solution_path)
    except SolutionFormatError as e:
        return _result("error", "solution_format", [f"solution: {e}"])

    orders = instance["orders"]
    aisles = instance["aisles"]
    order_units = instance["order_units"]
    lb, ub = instance["lb"], instance["ub"]
    n_orders, n_aisles = instance["n_orders"], instance["n_aisles"]

    selected_orders = solution["selected_orders"]
    visited_aisles = solution["visited_aisles"]

    violations = []
    reasons = []

    def fail(reason, message):
        reasons.append(reason)
        violations.append(message)

    # ---- Index domain -----------------------------------------------------
    bad_orders = [o for o in selected_orders if o < 0 or o >= n_orders]
    if bad_orders:
        fail("order_index", f"order index/indices out of range [0, {n_orders - 1}]: "
                            f"{sorted(set(bad_orders))[:10]}")

    bad_aisles = [a for a in visited_aisles if a < 0 or a >= n_aisles]
    if bad_aisles:
        fail("aisle_index", f"aisle index/indices out of range [0, {n_aisles - 1}]: "
                            f"{sorted(set(bad_aisles))[:10]}")

    # ---- Sets, not multisets ----------------------------------------------
    if len(selected_orders) != len(set(selected_orders)):
        dup = sorted({o for o in selected_orders if selected_orders.count(o) > 1})
        fail("duplicate_order", f"duplicate order indices: {dup[:10]}")

    if len(visited_aisles) != len(set(visited_aisles)):
        dup = sorted({a for a in visited_aisles if visited_aisles.count(a) > 1})
        fail("duplicate_aisle", f"duplicate aisle indices: {dup[:10]}")

    # Out-of-range indices make every remaining check meaningless.
    if bad_orders or bad_aisles:
        return _result("infeasible", reasons[0], violations,
                       items=0, aisles=len(set(visited_aisles)))

    unique_orders = set(selected_orders)
    unique_aisles = set(visited_aisles)

    # ---- Degenerate solutions --------------------------------------------
    if not unique_orders:
        fail("empty_wave", "no order selected: the wave is empty")
    if not unique_aisles:
        fail("no_aisle", "no aisle visited: objective (units / |A'|) is undefined")

    # ---- Units picked, constraints (2) and (3) ----------------------------
    total_items = sum(order_units[o] for o in unique_orders)

    if total_items < lb:
        fail("lower_bound", f"wave too small: {total_items} units < LB={lb}")
    if total_items > ub:
        fail("upper_bound", f"wave too large: {total_items} units > UB={ub}")

    # ---- Stock coverage, constraint (4) -----------------------------------
    demand = defaultdict(int)
    for o in unique_orders:
        for item, qty in orders[o].items():
            demand[item] += qty

    available = defaultdict(int)
    for a in unique_aisles:
        for item, qty in aisles[a].items():
            available[item] += qty

    shortages = []
    for item in sorted(demand):
        needed = demand[item]
        have = available.get(item, 0)
        if have < needed:
            shortages.append((item, needed, have))

    if shortages:
        detail = ", ".join(
            f"item {i}: need {n}, available {h}" for i, n, h in shortages[:5]
        )
        suffix = f" (+{len(shortages) - 5} more)" if len(shortages) > 5 else ""
        fail("stock", f"insufficient stock in visited aisles -> {detail}{suffix}")

    num_aisles = len(unique_aisles)
    objective = total_items / num_aisles if num_aisles > 0 else 0.0

    # ---- Cross-check the solver's own claims ------------------------------
    # A mismatch means the solver's internal evaluation disagrees with the
    # model, which corrupts its search even when the printed solution happens
    # to be feasible. Treat it as a hard failure of the run.
    if "objective" in solution:
        claimed = solution["objective"]
        if abs(claimed - objective) > OBJECTIVE_TOLERANCE * max(1.0, abs(objective)):
            fail("objective_mismatch",
                 f"solver reported objective {claimed!r} but the solution "
                 f"evaluates to {objective!r} ({total_items} units / "
                 f"{num_aisles} aisles)")

    for key, recomputed in (("items", total_items), ("aisles", num_aisles)):
        if key in solution and int(solution[key]) != recomputed:
            fail("count_mismatch",
                 f"solver reported {key}={solution[key]!r} but the solution "
                 f"has {recomputed}")

    if time_limit is not None and "exec_time" in solution:
        if solution["exec_time"] > time_limit + TIME_LIMIT_SLACK:
            fail("time_limit",
                 f"run reported exec_time={solution['exec_time']}s, "
                 f"above the {time_limit}s limit (+{TIME_LIMIT_SLACK}s slack)")

    if violations:
        return _result("infeasible", reasons[0], violations,
                       items=total_items, aisles=num_aisles)

    return _result("feasible", "", [], objective=objective,
                   items=total_items, aisles=num_aisles)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

_EXIT_CODES = {"feasible": 0, "infeasible": 1, "error": 2}


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    time_limit = None
    for a in argv:
        if a.startswith("--time-limit="):
            time_limit = float(a.split("=", 1)[1])

    if len(args) != 2:
        print(
            "Usage: python validator.py <instance_path> <solution_path> "
            "[--time-limit=<seconds>]",
            file=sys.stderr,
        )
        return 2

    result = validate(args[0], args[1], time_limit=time_limit)
    print(json.dumps(result))
    return _EXIT_CODES[result["status"]]


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
