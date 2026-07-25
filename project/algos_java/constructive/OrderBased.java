package constructive;

import model.Problem;
import model.Solution;

import java.util.*;

/**
 * OrderBased constructive heuristic — Java port of the three best-performing
 * variants from pfc1 {@code simple_best_*.yaml}:
 *
 * <ol>
 *   <li>{@code simple_desc_simple} — orders sorted DESC by units, simple greedy aisle selection</li>
 *   <li>{@code simple_desc_multi}  — orders sorted DESC by units, iterative greedy aisle selection</li>
 *   <li>{@code simple_desc_exact}  — orders sorted DESC by units, exact B&B minimum aisle cover</li>
 * </ol>
 *
 * <p>{@link #solve(Problem)} runs all three variants and returns the solution
 * with the highest objective value (units / aisles).
 *
 * <h3>Exact solver</h3>
 * The EXACT mode solves a Set Multicover ILP via branch-and-bound:
 * <pre>
 *   min  Σ x_a
 *   s.t. Σ_a supply[a][i] · x_a ≥ demand[i]   ∀ item i with demand[i] > 0
 * </pre>
 * The multi-greedy solution is used as the initial upper bound. A suffix-supply
 * feasibility check prunes infeasible subtrees early. If the time budget
 * ({@value #EXACT_TIME_LIMIT_MS} ms) is exceeded, the best solution found so
 * far (at least as good as multi-greedy) is returned.
 */
public class OrderBased {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    private enum GreedyMode { SIMPLE, MULTI, EXACT }

    /** Wall-clock budget for the B&B exact solver (milliseconds). */
    private static final long EXACT_TIME_LIMIT_MS = 30_000L;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public OrderBased(@SuppressWarnings("unused") Random random) {
        // random not needed: the only stochastic variant (NONE/random order)
        // was removed; all three variants are fully deterministic.
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the three fixed DESC variants and returns the solution with the
     * highest objective value.
     */
    public Solution solve(Problem problem) {
        if (problem.nOrders == 0 || problem.nAisles == 0) {
            return new Solution(problem);
        }

        int[] stockTotal = aggregateStock(problem);

        double bestObj = -1.0;
        Solution bestSol = new Solution(problem);

        for (GreedyMode gm : GreedyMode.values()) {
            Solution candidate = runVariant(problem, gm, stockTotal);
            double obj = candidate.getObj();
            if (obj > bestObj) {
                bestObj = obj;
                bestSol = candidate;
            }
        }

        return bestSol;
    }

    // -------------------------------------------------------------------------
    // Single-variant execution (always DESC order)
    // -------------------------------------------------------------------------

    private static Solution runVariant(Problem problem, GreedyMode gm, int[] stockTotal) {
        int[] stockRem = stockTotal.clone();
        List<Integer> sequence = buildDescSequence(problem);

        int[] demand = new int[problem.nItems];
        List<Integer> selectedOrders = new ArrayList<>();
        int totalUnits = pickOrders(sequence, problem, stockRem, demand, selectedOrders);

        if (totalUnits < problem.lb) {
            return new Solution(problem);
        }

        List<Integer> visitedAisles = selectAisles(problem, gm, demand);
        if (visitedAisles.isEmpty()) {
            return new Solution(problem);
        }

        Solution sol = new Solution(problem);
        for (int a : visitedAisles) sol.addAisle(a);
        for (int o : selectedOrders) sol.addOrder(o);
        return sol;
    }

    // -------------------------------------------------------------------------
    // Step 1: aggregate stock across all aisles
    // -------------------------------------------------------------------------

    private static int[] aggregateStock(Problem problem) {
        int[] stock = new int[problem.nItems];
        for (Map<Integer, Integer> aisle : problem.aisles) {
            for (Map.Entry<Integer, Integer> e : aisle.entrySet()) {
                stock[e.getKey()] += e.getValue();
            }
        }
        return stock;
    }

    // -------------------------------------------------------------------------
    // Step 2: build DESC sequence (orders sorted by total units, descending)
    // -------------------------------------------------------------------------

    private static List<Integer> buildDescSequence(Problem problem) {
        Integer[] indices = new Integer[problem.nOrders];
        for (int i = 0; i < problem.nOrders; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Integer.compare(problem.orderUnits[b], problem.orderUnits[a]));
        return Arrays.asList(indices);
    }

    // -------------------------------------------------------------------------
    // Step 3: pick orders greedily along the sequence
    // -------------------------------------------------------------------------

    private static int pickOrders(List<Integer> sequence,
                                   Problem problem,
                                   int[] stockRem,
                                   int[] demand,
                                   List<Integer> selected) {
        int total = 0;
        for (int idx : sequence) {
            int size = problem.orderUnits[idx];
            if (total + size > problem.ub) continue;

            Map<Integer, Integer> order = problem.orders.get(idx);
            boolean feasible = true;
            for (Map.Entry<Integer, Integer> e : order.entrySet()) {
                if (stockRem[e.getKey()] < e.getValue()) { feasible = false; break; }
            }
            if (!feasible) continue;

            selected.add(idx);
            total += size;
            for (Map.Entry<Integer, Integer> e : order.entrySet()) {
                stockRem[e.getKey()] -= e.getValue();
                demand[e.getKey()]   += e.getValue();
            }
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Step 4: aisle selection dispatch
    // -------------------------------------------------------------------------

    private static List<Integer> selectAisles(Problem problem, GreedyMode gm, int[] demand) {
        switch (gm) {
            case EXACT: return exactAisles(problem, demand);
            case MULTI: return multiGreedyAisles(problem, demand);
            default:    return simpleGreedyAisles(problem, demand);
        }
    }

    // -------------------------------------------------------------------------
    // SIMPLE greedy: score all aisles once, sort DESC, sweep until demand met
    // -------------------------------------------------------------------------

    private static List<Integer> simpleGreedyAisles(Problem problem, int[] demandIn) {
        int[] demand = demandIn.clone();

        int[] score = new int[problem.nAisles];
        for (int a = 0; a < problem.nAisles; a++) {
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(a).entrySet()) {
                int d = demand[e.getKey()];
                if (d > 0) score[a] += Math.min(d, e.getValue());
            }
        }

        Integer[] order = new Integer[problem.nAisles];
        for (int i = 0; i < problem.nAisles; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Integer.compare(score[y], score[x]));

        int remaining = 0;
        for (int d : demand) if (d > 0) remaining += d;

        List<Integer> selected = new ArrayList<>();
        for (int a : order) {
            if (remaining == 0) break;
            int real = 0;
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(a).entrySet()) {
                int d = demand[e.getKey()];
                if (d > 0) real += Math.min(d, e.getValue());
            }
            if (real == 0) continue;

            selected.add(a);
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(a).entrySet()) {
                int item = e.getKey();
                int d = demand[item];
                if (d <= 0) continue;
                int take = Math.min(d, e.getValue());
                demand[item] -= take;
                remaining   -= take;
            }
        }
        return selected;
    }

    // -------------------------------------------------------------------------
    // MULTI greedy: iteratively pick the highest-scoring remaining aisle
    // -------------------------------------------------------------------------

    private static List<Integer> multiGreedyAisles(Problem problem, int[] demandIn) {
        int[] demand = demandIn.clone();

        int remaining = 0;
        for (int d : demand) if (d > 0) remaining += d;

        boolean[] used = new boolean[problem.nAisles];
        List<Integer> selected = new ArrayList<>();

        while (remaining > 0) {
            int best = -1, bestScore = 0;
            for (int a = 0; a < problem.nAisles; a++) {
                if (used[a]) continue;
                int s = 0;
                for (Map.Entry<Integer, Integer> e : problem.aisles.get(a).entrySet()) {
                    int d = demand[e.getKey()];
                    if (d > 0) s += Math.min(d, e.getValue());
                }
                if (s > bestScore) { best = a; bestScore = s; }
            }
            if (bestScore == 0) break;

            used[best] = true;
            selected.add(best);
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(best).entrySet()) {
                int item = e.getKey();
                int d = demand[item];
                if (d <= 0) continue;
                int take = Math.min(d, e.getValue());
                demand[item] -= take;
                remaining   -= take;
            }
        }
        return selected;
    }

    // -------------------------------------------------------------------------
    // EXACT: branch-and-bound minimum set multicover
    //
    //   min  Σ x_a
    //   s.t. Σ_a supply[a][i] * x_a >= demand[i]   for all i with demand[i] > 0
    //
    // Strategy:
    //   - Sort aisles by initial coverage score (best-first ordering for early pruning)
    //   - Precompute suffix-supply sums over demand items for feasibility checks
    //   - Use multi-greedy solution as initial upper bound
    //   - Time limit: falls back to best found (>= greedy quality) if exceeded
    // -------------------------------------------------------------------------

    private static List<Integer> exactAisles(Problem problem, int[] demandIn) {
        // Collect items with positive demand
        int[] demandItems = collectDemandItems(demandIn);
        int nD = demandItems.length;
        if (nD == 0) return new ArrayList<>();

        // Extract demand values for those items only
        int[] demandVals = new int[nD];
        int totalDemand = 0;
        for (int j = 0; j < nD; j++) {
            demandVals[j] = demandIn[demandItems[j]];
            totalDemand  += demandVals[j];
        }

        // Initial upper bound: multi-greedy solution
        List<Integer> greedySol = multiGreedyAisles(problem, demandIn);

        // Sort aisles by initial coverage score (most useful first)
        Integer[] aisleOrder = sortAislesByScore(problem, demandIn, demandItems, nD);

        // Precompute suffix supply for feasibility pruning:
        // suffSupply[k][j] = total supply of demandItems[j] from aisleOrder[k..end]
        int n = problem.nAisles;
        int[][] suffSupply = buildSuffixSupply(problem, aisleOrder, demandItems, nD);

        // Shared mutable state for B&B
        int[]        bestCount   = { greedySol.size() };
        List<Integer> bestSol    = new ArrayList<>(greedySol);
        long[]       deadline    = { System.currentTimeMillis() + EXACT_TIME_LIMIT_MS };

        bbSearch(problem, aisleOrder, suffSupply, demandItems, nD,
                 0, demandVals.clone(), totalDemand,
                 new ArrayList<>(), bestCount, bestSol, deadline);

        return bestSol;
    }

    /** Collect indices of items where demand > 0. */
    private static int[] collectDemandItems(int[] demand) {
        int count = 0;
        for (int d : demand) if (d > 0) count++;
        int[] result = new int[count];
        int pos = 0;
        for (int i = 0; i < demand.length; i++) if (demand[i] > 0) result[pos++] = i;
        return result;
    }

    /** Sort aisles descending by their initial contribution to demandItems. */
    private static Integer[] sortAislesByScore(Problem problem, int[] demandIn,
                                                int[] demandItems, int nD) {
        int[] score = new int[problem.nAisles];
        for (int a = 0; a < problem.nAisles; a++) {
            Map<Integer, Integer> aisle = problem.aisles.get(a);
            for (int j = 0; j < nD; j++) {
                Integer qty = aisle.get(demandItems[j]);
                if (qty != null) {
                    int d = demandIn[demandItems[j]];
                    score[a] += Math.min(d, qty);
                }
            }
        }
        Integer[] order = new Integer[problem.nAisles];
        for (int i = 0; i < problem.nAisles; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Integer.compare(score[y], score[x]));
        return order;
    }

    /**
     * Precompute suffix supply sums for feasibility pruning.
     * suffSupply[k][j] = supply of demandItems[j] from aisleOrder[k], ..., aisleOrder[n-1].
     */
    private static int[][] buildSuffixSupply(Problem problem, Integer[] aisleOrder,
                                              int[] demandItems, int nD) {
        int n = aisleOrder.length;
        int[][] suff = new int[n + 1][nD];
        for (int k = n - 1; k >= 0; k--) {
            Map<Integer, Integer> aisle = problem.aisles.get(aisleOrder[k]);
            for (int j = 0; j < nD; j++) {
                suff[k][j] = suff[k + 1][j];
                Integer qty = aisle.get(demandItems[j]);
                if (qty != null) suff[k][j] += qty;
            }
        }
        return suff;
    }

    /**
     * Recursive branch-and-bound for minimum set multicover.
     *
     * @param idx       current position in aisleOrder
     * @param demand    remaining demand for each of the nD demand-items (mutable copy)
     * @param remaining total remaining demand units
     * @param current   aisles selected so far (in-place updated)
     */
    private static void bbSearch(Problem problem,
                                  Integer[] aisleOrder,
                                  int[][] suffSupply,
                                  int[] demandItems,
                                  int nD,
                                  int idx,
                                  int[] demand,
                                  int remaining,
                                  List<Integer> current,
                                  int[] bestCount,
                                  List<Integer> bestSol,
                                  long[] deadline) {

        // ── Base case ──────────────────────────────────────────────────────────
        if (remaining <= 0) {
            if (current.size() < bestCount[0]) {
                bestCount[0] = current.size();
                bestSol.clear();
                bestSol.addAll(current);
            }
            return;
        }

        // ── Pruning ────────────────────────────────────────────────────────────
        if (idx >= aisleOrder.length) return;

        // Count bound: adding at least 1 more aisle can't beat current best
        if (current.size() + 1 >= bestCount[0]) return;

        // Time limit: keep the best found so far (>= greedy)
        if (System.currentTimeMillis() > deadline[0]) return;

        // Feasibility: remaining aisles cannot cover remaining demand
        for (int j = 0; j < nD; j++) {
            if (demand[j] > 0 && suffSupply[idx][j] < demand[j]) return;
        }

        int a = aisleOrder[idx];

        // ── Branch 1: INCLUDE aisle a ──────────────────────────────────────────
        int[] newDemand   = demand.clone();
        int   newRemaining = remaining;
        Map<Integer, Integer> aisleMap = problem.aisles.get(a);
        for (int j = 0; j < nD; j++) {
            if (newDemand[j] <= 0) continue;
            Integer qty = aisleMap.get(demandItems[j]);
            if (qty == null) continue;
            int take = Math.min(newDemand[j], qty);
            newDemand[j]  -= take;
            newRemaining  -= take;
        }
        current.add(a);
        bbSearch(problem, aisleOrder, suffSupply, demandItems, nD,
                 idx + 1, newDemand, newRemaining, current, bestCount, bestSol, deadline);
        current.remove(current.size() - 1);

        // ── Branch 2: EXCLUDE aisle a ──────────────────────────────────────────
        bbSearch(problem, aisleOrder, suffSupply, demandItems, nD,
                 idx + 1, demand, remaining, current, bestCount, bestSol, deadline);
    }
}
