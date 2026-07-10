package constructive;

import model.Problem;
import model.Solution;

import java.util.*;

/**
 * Java port of the constructive SimpleHeuristic from pfc1/algorithms/simple/.
 *
 * <p>{@link #solve(Problem)} runs ALL variant combinations internally (order-
 * sequencing mode × greedy aisle-selection mode × first-order reference mode)
 * and returns the best solution found — analogous to how {@link AisleFirst}
 * sweeps multiple scoring strategies and keeps the best.
 *
 * <h3>Order-sequencing modes</h3>
 * <ul>
 *   <li>{@code NONE} / random — random shuffle (run once per call)</li>
 *   <li>{@code ASC}  — ascending total units</li>
 *   <li>{@code DESC} — descending total units</li>
 *   <li>{@code SIMILAR} / {@code SIMILAR_WEIGHTED} — most similar to reference order first</li>
 *   <li>{@code DIFF}    / {@code DIFF_WEIGHTED}    — most different from reference order first</li>
 * </ul>
 *
 * <h3>First-order (reference) selection — used by similarity modes</h3>
 * <ul>
 *   <li>{@code RANDOM}      — randomly chosen reference</li>
 *   <li>{@code SMALLER}     — order with fewest total units</li>
 *   <li>{@code BIGGER}      — order with most total units</li>
 *   <li>{@code MOST_SHARED} — order whose items appear in the most aisles</li>
 * </ul>
 *
 * <h3>Greedy aisle-selection modes</h3>
 * <ul>
 *   <li>{@code SIMPLE} — one-shot rank by score, sweep until demand satisfied</li>
 *   <li>{@code MULTI}  — iterative best-pick until demand satisfied</li>
 * </ul>
 */
public class SimpleHeuristic {

    // -------------------------------------------------------------------------
    // Configuration enums
    // -------------------------------------------------------------------------

    public enum OrderMode {
        NONE, ASC, DESC, SIMILAR, SIMILAR_WEIGHTED, DIFF, DIFF_WEIGHTED
    }

    public enum GreedyMode {
        SIMPLE, MULTI
    }

    public enum FirstOrderMode {
        RANDOM, SMALLER, BIGGER, MOST_SHARED
    }

    /** Order modes that require a first-order reference. */
    private static final EnumSet<OrderMode> SIMILARITY_MODES = EnumSet.of(
            OrderMode.SIMILAR, OrderMode.SIMILAR_WEIGHTED,
            OrderMode.DIFF,    OrderMode.DIFF_WEIGHTED
    );

    private final Random random;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SimpleHeuristic(Random random) {
        this.random = random;
    }

    // -------------------------------------------------------------------------
    // Public API — sweeps all variants, returns best
    // -------------------------------------------------------------------------

    /**
     * Runs every combination of {OrderMode} × {GreedyMode} × {FirstOrderMode}
     * and returns the solution with the highest objective value.
     */
    public Solution solve(Problem problem) {
        if (problem.nOrders == 0 || problem.nAisles == 0) {
            return new Solution(problem);
        }

        // Aggregate total stock once — reused across all variants
        int[] stockTotal = aggregateStock(problem);

        // Pre-compute item-aisle counts once (needed by MOST_SHARED)
        int[] itemAisleCount = computeItemAisleCount(problem);

        double bestObj = -1.0;
        Solution bestSol = new Solution(problem);

        for (GreedyMode gm : GreedyMode.values()) {
            for (OrderMode om : OrderMode.values()) {
                if (SIMILARITY_MODES.contains(om)) {
                    // Run once per first-order strategy
                    for (FirstOrderMode fm : FirstOrderMode.values()) {
                        Solution candidate = runVariant(problem, om, gm, fm,
                                stockTotal, itemAisleCount);
                        double obj = candidate.getObj();
                        if (obj > bestObj) {
                            bestObj = obj;
                            bestSol = candidate;
                        }
                    }
                } else {
                    // No reference needed (NONE, ASC, DESC)
                    Solution candidate = runVariant(problem, om, gm, FirstOrderMode.RANDOM,
                            stockTotal, itemAisleCount);
                    double obj = candidate.getObj();
                    if (obj > bestObj) {
                        bestObj = obj;
                        bestSol = candidate;
                    }
                }
            }
        }

        return bestSol;
    }

    // -------------------------------------------------------------------------
    // Single-variant execution
    // -------------------------------------------------------------------------

    private Solution runVariant(Problem problem,
                                OrderMode om, GreedyMode gm, FirstOrderMode fm,
                                int[] stockTotal, int[] itemAisleCount) {

        // Stock is consumed per-variant — take a fresh copy each time
        int[] stockRem = stockTotal.clone();

        List<Integer> sequence = buildSequence(problem, om, fm, itemAisleCount);

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
    // Step 1: aggregate stock
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

    private static int[] computeItemAisleCount(Problem problem) {
        int[] cnt = new int[problem.nItems];
        for (Map<Integer, Integer> aisle : problem.aisles) {
            for (int item : aisle.keySet()) {
                cnt[item]++;
            }
        }
        return cnt;
    }

    // -------------------------------------------------------------------------
    // Step 2: build order traversal sequence
    // -------------------------------------------------------------------------

    private List<Integer> buildSequence(Problem problem, OrderMode om, FirstOrderMode fm,
                                        int[] itemAisleCount) {
        Integer[] indices = new Integer[problem.nOrders];
        for (int i = 0; i < problem.nOrders; i++) indices[i] = i;

        switch (om) {
            case ASC:
                Arrays.sort(indices, Comparator.comparingInt(i -> problem.orderUnits[i]));
                return Arrays.asList(indices);

            case DESC:
                Arrays.sort(indices, (a, b) -> Integer.compare(problem.orderUnits[b], problem.orderUnits[a]));
                return Arrays.asList(indices);

            case SIMILAR:
            case SIMILAR_WEIGHTED:
            case DIFF:
            case DIFF_WEIGHTED: {
                boolean weighted = (om == OrderMode.SIMILAR_WEIGHTED || om == OrderMode.DIFF_WEIGHTED);
                boolean ascending = (om == OrderMode.DIFF || om == OrderMode.DIFF_WEIGHTED);

                int ref = pickFirstOrderIndex(problem, fm, itemAisleCount);
                double[] sims = computeSimilarities(problem, ref, weighted);

                if (ascending) {
                    Arrays.sort(indices, Comparator.comparingDouble(i -> sims[i]));
                } else {
                    Arrays.sort(indices, (a, b) -> Double.compare(sims[b], sims[a]));
                }
                return Arrays.asList(indices);
            }

            case NONE:
            default: {
                List<Integer> list = new ArrayList<>(Arrays.asList(indices));
                Collections.shuffle(list, random);
                return list;
            }
        }
    }

    private int pickFirstOrderIndex(Problem problem, FirstOrderMode fm, int[] itemAisleCount) {
        switch (fm) {
            case BIGGER: {
                int best = 0;
                for (int i = 1; i < problem.nOrders; i++) {
                    if (problem.orderUnits[i] > problem.orderUnits[best]) best = i;
                }
                return best;
            }
            case SMALLER: {
                int best = 0;
                for (int i = 1; i < problem.nOrders; i++) {
                    if (problem.orderUnits[i] < problem.orderUnits[best]) best = i;
                }
                return best;
            }
            case MOST_SHARED: {
                int best = 0;
                long bestScore = Long.MIN_VALUE;
                for (int i = 0; i < problem.nOrders; i++) {
                    long score = 0;
                    for (int item : problem.orders.get(i).keySet()) {
                        score += itemAisleCount[item];
                    }
                    // Tie-break by order size (matches Python implementation)
                    long composite = score * (long) Integer.MAX_VALUE + problem.orderUnits[i];
                    if (composite > bestScore) {
                        bestScore = composite;
                        best = i;
                    }
                }
                return best;
            }
            case RANDOM:
            default:
                return random.nextInt(problem.nOrders);
        }
    }

    // -------------------------------------------------------------------------
    // Similarity computation
    // -------------------------------------------------------------------------

    private double[] computeSimilarities(Problem problem, int ref, boolean weighted) {
        Map<Integer, Integer> refOrder = problem.orders.get(ref);
        double[] sims = new double[problem.nOrders];
        for (int i = 0; i < problem.nOrders; i++) {
            sims[i] = jaccardSimilarity(refOrder, problem.orders.get(i), weighted);
        }
        return sims;
    }

    /**
     * Jaccard similarity between two orders.
     * <ul>
     *   <li>Unweighted: |items_A ∩ items_B| / |items_A ∪ items_B|</li>
     *   <li>Weighted:   Σ min(qa,qb) / Σ max(qa,qb)</li>
     * </ul>
     */
    private static double jaccardSimilarity(Map<Integer, Integer> a,
                                             Map<Integer, Integer> b,
                                             boolean weighted) {
        if (!weighted) {
            int inter = 0;
            for (int item : a.keySet()) {
                if (b.containsKey(item)) inter++;
            }
            int union = a.size() + b.size() - inter;
            return union == 0 ? 0.0 : (double) inter / union;
        } else {
            long num = 0, den = 0;
            for (Map.Entry<Integer, Integer> e : a.entrySet()) {
                int qa = e.getValue();
                Integer qbObj = b.get(e.getKey());
                if (qbObj != null) {
                    num += Math.min(qa, qbObj);
                    den += Math.max(qa, qbObj);
                } else {
                    den += qa;
                }
            }
            for (Map.Entry<Integer, Integer> e : b.entrySet()) {
                if (!a.containsKey(e.getKey())) den += e.getValue();
            }
            return den == 0 ? 0.0 : (double) num / den;
        }
    }

    // -------------------------------------------------------------------------
    // Step 3: pick orders greedily
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
    // Step 4: select aisles to cover demand
    // -------------------------------------------------------------------------

    private static List<Integer> selectAisles(Problem problem, GreedyMode gm, int[] demand) {
        return gm == GreedyMode.MULTI
                ? multiGreedyAisles(problem, demand)
                : simpleGreedyAisles(problem, demand);
    }

    /**
     * Simple greedy: score all aisles once, sort descending, sweep until demand
     * is fully covered (recomputes real contribution before adding each aisle).
     */
    private static List<Integer> simpleGreedyAisles(Problem problem, int[] demandIn) {
        int[] demand = demandIn.clone();

        int[] score = new int[problem.nAisles];
        for (int a = 0; a < problem.nAisles; a++) {
            int s = 0;
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(a).entrySet()) {
                int d = demand[e.getKey()];
                if (d > 0) s += Math.min(d, e.getValue());
            }
            score[a] = s;
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
                remaining -= take;
            }
        }
        return selected;
    }

    /**
     * Multi greedy: iteratively pick the aisle that covers the most remaining
     * demand until satisfied or no aisle contributes.
     */
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
                remaining -= take;
            }
        }
        return selected;
    }
}
