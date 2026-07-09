package constructive;

import model.Problem;
import model.Solution;

import java.util.*;

public class AisleFirst {

    public AisleFirst() {
    }

    // -------------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------------

    /**
     * Scores an aisle for the static strategies (units, variety, mixed).
     * The "useful" family uses int[] demand passed externally.
     */
    private double aisleScore(int idx, Problem problem, int[] demand, String score) {
        Map<Integer, Integer> aisle = problem.aisles.get(idx);
        switch (score) {
            case "useful": {
                double val = 0.0;
                for (Map.Entry<Integer, Integer> e : aisle.entrySet()) {
                    int d = demand[e.getKey()];
                    if (d > 0) val += Math.min(e.getValue(), d);
                }
                return val;
            }
            case "units": {
                double val = 0.0;
                for (int qty : aisle.values()) val += qty;
                return val;
            }
            case "variety":
                return aisle.size();
            case "mixed": {
                double units = 0.0;
                for (int qty : aisle.values()) units += qty;
                return units * aisle.size();
            }
            default:
                return 0.0;
        }
    }

    /**
     * Produces a static one-shot ranking of all aisles by the given score.
     */
    private List<Integer> rankAisles(Problem problem, int[] demand, String score) {
        double[] scores = new double[problem.nAisles];
        for (int i = 0; i < problem.nAisles; i++) {
            scores[i] = aisleScore(i, problem, demand, score);
        }

        Integer[] indices = new Integer[problem.nAisles];
        for (int i = 0; i < problem.nAisles; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Double.compare(scores[b], scores[a]));

        return Arrays.asList(indices);
    }

    // -------------------------------------------------------------------------
    // Order sequence
    // -------------------------------------------------------------------------

    private List<Integer> buildSequence(Problem problem) {
        Integer[] seq = new Integer[problem.nOrders];
        for (int i = 0; i < problem.nOrders; i++) seq[i] = i;
        Arrays.sort(seq, (a, b) -> Integer.compare(problem.orderUnits[b], problem.orderUnits[a]));
        return Arrays.asList(seq);
    }

    // -------------------------------------------------------------------------
    // Order packing — int[] inventory (no HashMap allocation)
    // -------------------------------------------------------------------------

    /**
     * Greedy pack: fills orders (descending-size order) against the given
     * inventory array. The array is mutated in-place (caller must snapshot).
     *
     * @return total units packed
     */
    private int packOrders(
            List<Integer> sequence,
            Problem problem,
            int[] inventory,
            List<Integer> selected) {

        selected.clear();
        int total = 0;

        for (int idx : sequence) {
            int size = problem.orderUnits[idx];
            if (total + size > problem.ub) continue;

            Map<Integer, Integer> order = problem.orders.get(idx);
            boolean canFulfill = true;
            for (Map.Entry<Integer, Integer> e : order.entrySet()) {
                if (inventory[e.getKey()] < e.getValue()) {
                    canFulfill = false;
                    break;
                }
            }
            if (!canFulfill) continue;

            selected.add(idx);
            total += size;
            for (Map.Entry<Integer, Integer> e : order.entrySet()) {
                inventory[e.getKey()] -= e.getValue();
            }
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Aisle pruning — incremental int[] (no HashMap reconstruction per attempt)
    // -------------------------------------------------------------------------

    /**
     * Tries to remove aisles that are redundant given the selected orders.
     * Uses an incremental int[] inventory: subtract the aisle under test,
     * check feasibility, restore if needed — avoids full rebuild per candidate.
     *
     * @param invFull pre-built inventory covering all selectedAisles (will be
     *                mutated; caller should not reuse it afterwards)
     */
    private List<Integer> pruneAisles(
            Problem problem,
            List<Integer> selectedAisles,
            List<Integer> selectedOrders,
            int[] invFull) {

        // Pre-sort orders (same order as packing) — done once
        Integer[] orderSeqArr = selectedOrders.toArray(new Integer[0]);
        Arrays.sort(orderSeqArr, (a, b) -> Integer.compare(problem.orderUnits[b], problem.orderUnits[a]));

        // Reusable temp buffer — avoids allocation inside the loop
        int[] temp = new int[problem.nItems];

        Set<Integer> remaining = new LinkedHashSet<>(selectedAisles);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int aisle : new ArrayList<>(remaining)) {
                // Subtract this aisle from incremental inventory
                for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisle).entrySet()) {
                    invFull[e.getKey()] -= e.getValue();
                }

                // Snapshot the reduced inventory into temp and check feasibility
                System.arraycopy(invFull, 0, temp, 0, problem.nItems);
                boolean ok = true;
                outer:
                for (int idx : orderSeqArr) {
                    Map<Integer, Integer> order = problem.orders.get(idx);
                    for (Map.Entry<Integer, Integer> e : order.entrySet()) {
                        if (temp[e.getKey()] < e.getValue()) {
                            ok = false;
                            break outer;
                        }
                    }
                    for (Map.Entry<Integer, Integer> e : order.entrySet()) {
                        temp[e.getKey()] -= e.getValue();
                    }
                }

                if (ok) {
                    // Aisle is redundant: keep invFull as-is (already subtracted)
                    remaining.remove(aisle);
                    changed = true;
                    break; // restart scan — set changed
                } else {
                    // Restore the aisle
                    for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisle).entrySet()) {
                        invFull[e.getKey()] += e.getValue();
                    }
                }
            }
        }
        return new ArrayList<>(remaining);
    }

    // -------------------------------------------------------------------------
    // Static strategy: one-shot rank then sweep k=1..nAisles
    // -------------------------------------------------------------------------

    /**
     * Runs one greedy sweep for a fixed aisle ranking.
     * Returns true if a new best was found.
     */
    private boolean runStaticStrategy(
            Problem problem,
            List<Integer> rankedAisles,
            List<Integer> sequence,
            int[] inventory,      // working buffer [nItems], pre-zeroed by caller
            int[] snapshot,       // working buffer [nItems]
            double[] bestObj,
            List<Integer>[] bestOrders,
            List<Integer>[] bestAisles) {

        boolean improved = false;
        Arrays.fill(inventory, 0);
        int k = 0;

        for (int aisleIdx : rankedAisles) {
            k++;

            // Accumulate inventory incrementally
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisleIdx).entrySet()) {
                inventory[e.getKey()] += e.getValue();
            }

            // Tight upper bound: ub / k ≤ bestObj → can't improve
            if ((double) problem.ub / k <= bestObj[0]) break;

            // Snapshot for packOrders (preserves accumulated inventory)
            System.arraycopy(inventory, 0, snapshot, 0, problem.nItems);

            List<Integer> selected = new ArrayList<>();
            int totalUnits = packOrders(sequence, problem, snapshot, selected);

            if (totalUnits < problem.lb) continue;

            double obj = (double) totalUnits / k;
            if (obj > bestObj[0]) {
                bestObj[0] = obj;
                bestOrders[0] = new ArrayList<>(selected);
                bestAisles[0] = new ArrayList<>(rankedAisles.subList(0, k));
                improved = true;
            }
        }
        return improved;
    }

    // -------------------------------------------------------------------------
    // Dynamic-useful strategy: greedy one-at-a-time with residual demand
    // -------------------------------------------------------------------------

    /**
     * Greedy aisle selection with dynamic re-ranking based on residual demand.
     * At each step picks the aisle that maximises min(qty, residualDemand).
     * Returns true if a new best was found.
     */
    @SuppressWarnings("unchecked")
    private boolean runDynamicUseful(
            Problem problem,
            List<Integer> sequence,
            int[] totalDemand,
            int[] inventory,    // working buffer [nItems]
            int[] snapshot,     // working buffer [nItems]
            double[] bestObj,
            List<Integer>[] bestOrders,
            List<Integer>[] bestAisles) {

        int[] residual = totalDemand.clone();   // residual demand, updated each step
        Arrays.fill(inventory, 0);

        boolean[] used = new boolean[problem.nAisles];
        List<Integer> aisleOrder = new ArrayList<>(problem.nAisles);

        boolean improved = false;
        int k = 0;

        for (int step = 0; step < problem.nAisles; step++) {
            // Pick best remaining aisle by "useful" score against current residual
            int bestAisle = -1;
            double bestScore = -1.0;
            for (int i = 0; i < problem.nAisles; i++) {
                if (used[i]) continue;
                double sc = aisleScore(i, problem, residual, "useful");
                if (sc > bestScore) {
                    bestScore = sc;
                    bestAisle = i;
                }
            }
            if (bestAisle < 0) break;

            used[bestAisle] = true;
            aisleOrder.add(bestAisle);
            k++;

            // Add to inventory
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(bestAisle).entrySet()) {
                int item = e.getKey();
                inventory[item] += e.getValue();
                // Update residual: subtract what this aisle covers
                residual[item] = Math.max(0, residual[item] - e.getValue());
            }

            // Early stop
            if ((double) problem.ub / k <= bestObj[0]) break;

            // Pack orders
            System.arraycopy(inventory, 0, snapshot, 0, problem.nItems);
            List<Integer> selected = new ArrayList<>();
            int totalUnits = packOrders(sequence, problem, snapshot, selected);

            if (totalUnits < problem.lb) continue;

            double obj = (double) totalUnits / k;
            if (obj > bestObj[0]) {
                bestObj[0] = obj;
                bestOrders[0] = new ArrayList<>(selected);
                bestAisles[0] = new ArrayList<>(aisleOrder);
                improved = true;
            }
        }
        return improved;
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Solution solve(Problem problem) {
        if (problem.nOrders == 0 || problem.nAisles == 0) {
            return new Solution(problem);
        }

        // Total demand as int[] — no boxing/unboxing
        int[] totalDemand = new int[problem.nItems];
        for (Map<Integer, Integer> order : problem.orders) {
            for (Map.Entry<Integer, Integer> e : order.entrySet()) {
                totalDemand[e.getKey()] += e.getValue();
            }
        }

        // Order sequence: descending by size, computed once
        List<Integer> sequence = buildSequence(problem);

        // Shared work buffers — reused across strategies to avoid repeated allocation
        int[] inventory = new int[problem.nItems];
        int[] snapshot  = new int[problem.nItems];

        // Best-solution holders (boxed for mutability inside helpers)
        double[]        bestObj    = {0.0};
        List<Integer>[] bestOrders = new List[]{new ArrayList<>()};
        List<Integer>[] bestAisles = new List[]{new ArrayList<>()};

        // --- Strategy 1-3: static rankings (units, variety, mixed) ---
        for (String score : new String[]{"units", "variety", "mixed"}) {
            List<Integer> ranked = rankAisles(problem, totalDemand, score);
            runStaticStrategy(problem, ranked, sequence,
                    inventory, snapshot, bestObj, bestOrders, bestAisles);
        }

        // --- Strategy 4: static "useful" (fixed total demand) ---
        {
            List<Integer> ranked = rankAisles(problem, totalDemand, "useful");
            runStaticStrategy(problem, ranked, sequence,
                    inventory, snapshot, bestObj, bestOrders, bestAisles);
        }

        // --- Strategy 5: dynamic "useful" (residual demand re-ranking) ---
        runDynamicUseful(problem, sequence, totalDemand,
                inventory, snapshot, bestObj, bestOrders, bestAisles);

        // --- Prune redundant aisles from best solution ---
        if (!bestOrders[0].isEmpty()) {
            // Build full inventory for the best aisle set (needed by pruneAisles)
            Arrays.fill(inventory, 0);
            for (int a : bestAisles[0]) {
                for (Map.Entry<Integer, Integer> e : problem.aisles.get(a).entrySet()) {
                    inventory[e.getKey()] += e.getValue();
                }
            }
            bestAisles[0] = pruneAisles(problem, bestAisles[0], bestOrders[0], inventory);
        }

        Solution sol = new Solution(problem);
        for (int a : bestAisles[0]) sol.addAisle(a);
        for (int o : bestOrders[0]) sol.addOrder(o);
        return sol;
    }
}
