import json
import sys
from collections import defaultdict


def parse_instance(instance_path):
    """Parse a warehouse instance file.

    File format:
        Line 1: nOrders nItems nAisles
        Next nOrders blocks: n_order_items  item_idx item_qty  ...
        Next nAisles blocks: n_aisle_items  item_idx item_qty  ...
        Last line: lb ub
    """
    with open(instance_path, "r") as f:
        tokens = f.read().split()

    idx = 0

    def next_int():
        nonlocal idx
        val = int(tokens[idx])
        idx += 1
        return val

    n_orders = next_int()
    _n_items = next_int()  # not used directly but consumed
    n_aisles = next_int()

    orders = []
    for _ in range(n_orders):
        n_order_items = next_int()
        order = {}
        for _ in range(n_order_items):
            item_idx = next_int()
            item_qty = next_int()
            order[item_idx] = item_qty
        orders.append(order)

    aisles = []
    for _ in range(n_aisles):
        n_aisle_items = next_int()
        aisle = {}
        for _ in range(n_aisle_items):
            item_idx = next_int()
            item_qty = next_int()
            aisle[item_idx] = item_qty
        aisles.append(aisle)

    lb = next_int()
    ub = next_int()

    return {
        "n_orders": n_orders,
        "n_aisles": n_aisles,
        "orders": orders,
        "aisles": aisles,
        "lb": lb,
        "ub": ub,
    }


def parse_solution(solution_path):
    """Parse a solution JSON file produced by algo_runner.

    Expected keys: selected_orders, visited_aisles
    """
    with open(solution_path, "r") as f:
        sol = json.load(f)
    return sol


def validate(instance_path, solution_path):
    """Validate a solution against its instance.

    Returns a dict with:
        status:    "feasible" | "infeasible" | "error"
        objective: float  (total_items / num_visited_aisles, or 0.0)
        items:     int    (total items in selected orders)
        aisles:    int    (number of visited aisles)
        message:   str    (human-readable explanation, present on infeasible/error)
    """
    try:
        instance = parse_instance(instance_path)
    except Exception as e:
        return {
            "status": "error",
            "objective": 0.0,
            "items": 0,
            "aisles": 0,
            "message": f"Failed to parse instance: {e}",
        }

    try:
        solution = parse_solution(solution_path)
    except Exception as e:
        return {
            "status": "error",
            "objective": 0.0,
            "items": 0,
            "aisles": 0,
            "message": f"Failed to parse solution: {e}",
        }

    orders = instance["orders"]
    aisles = instance["aisles"]
    lb = instance["lb"]
    ub = instance["ub"]
    n_orders = instance["n_orders"]
    n_aisles = instance["n_aisles"]

    selected_orders = solution.get("selected_orders", [])
    visited_aisles = solution.get("visited_aisles", [])

    # --- Empty solution (algorithm returned no selection) ---
    if not selected_orders and not visited_aisles:
        return {
            "status": "infeasible",
            "objective": 0.0,
            "items": 0,
            "aisles": 0,
            "message": "Empty solution: no orders selected.",
        }

    # --- Index bounds ---
    for o in selected_orders:
        if o < 0 or o >= n_orders:
            return {
                "status": "infeasible",
                "objective": 0.0,
                "items": 0,
                "aisles": 0,
                "message": f"Order index {o} out of range [0, {n_orders - 1}].",
            }

    for a in visited_aisles:
        if a < 0 or a >= n_aisles:
            return {
                "status": "infeasible",
                "objective": 0.0,
                "items": 0,
                "aisles": 0,
                "message": f"Aisle index {a} out of range [0, {n_aisles - 1}].",
            }

    # --- Duplicate check ---
    if len(selected_orders) != len(set(selected_orders)):
        return {
            "status": "infeasible",
            "objective": 0.0,
            "items": 0,
            "aisles": 0,
            "message": "Duplicate order indices in selected_orders.",
        }

    if len(visited_aisles) != len(set(visited_aisles)):
        return {
            "status": "infeasible",
            "objective": 0.0,
            "items": 0,
            "aisles": 0,
            "message": "Duplicate aisle indices in visited_aisles.",
        }

    # --- Aggregate demand from selected orders ---
    total_items = 0
    demand = defaultdict(int)
    for o in selected_orders:
        order = orders[o]
        for item_idx, qty in order.items():
            demand[item_idx] += qty
            total_items += qty

    # --- Wave size bounds (lb ≤ total_items ≤ ub) ---
    if total_items < lb:
        return {
            "status": "infeasible",
            "objective": 0.0,
            "items": total_items,
            "aisles": len(visited_aisles),
            "message": f"Wave too small: {total_items} items < lb={lb}.",
        }

    if total_items > ub:
        return {
            "status": "infeasible",
            "objective": 0.0,
            "items": total_items,
            "aisles": len(visited_aisles),
            "message": f"Wave too large: {total_items} items > ub={ub}.",
        }

    # --- Stock coverage: visited aisles must cover all demand ---
    available_stock = defaultdict(int)
    for a in visited_aisles:
        for item_idx, qty in aisles[a].items():
            available_stock[item_idx] += qty

    for item_idx, needed in demand.items():
        have = available_stock.get(item_idx, 0)
        if have < needed:
            return {
                "status": "infeasible",
                "objective": 0.0,
                "items": total_items,
                "aisles": len(visited_aisles),
                "message": (
                    f"Insufficient stock for item {item_idx}: "
                    f"need {needed}, visited aisles provide {have}."
                ),
            }

    # --- Objective ---
    num_aisles = len(visited_aisles)
    objective = total_items / num_aisles if num_aisles > 0 else 0.0

    return {
        "status": "feasible",
        "objective": objective,
        "items": total_items,
        "aisles": num_aisles,
    }


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python validator.py <instance_path> <solution_path>", file=sys.stderr)
        sys.exit(1)

    result = validate(sys.argv[1], sys.argv[2])
    print(json.dumps(result))
