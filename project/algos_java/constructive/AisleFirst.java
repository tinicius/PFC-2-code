package constructive;

import model.Problem;
import model.Solution;

import java.util.*;

public class AisleFirst {

    private final String score;

    public AisleFirst(Map<String, String> params) {
        this.score = params.getOrDefault("score", "useful");

        Set<String> validScore = new HashSet<>(Arrays.asList("useful", "units", "variety", "mixed"));
        if (!validScore.contains(this.score)) {
            throw new IllegalArgumentException("AisleFirstDesc: invalid 'score'=" + this.score);
        }
    }

    private double aisleScore(int idx, Problem problem, Map<Integer, Integer> totalDemand) {
        Map<Integer, Integer> aisle = problem.aisles.get(idx);
        if ("useful".equals(score)) {
            double val = 0.0;
            for (Map.Entry<Integer, Integer> entry : aisle.entrySet()) {
                int item = entry.getKey();
                int qty = entry.getValue();
                int demand = totalDemand.getOrDefault(item, 0);
                if (demand > 0) {
                    val += Math.min(qty, demand);
                }
            }
            return val;
        }
        if ("units".equals(score)) {
            double val = 0.0;
            for (int qty : aisle.values()) {
                val += qty;
            }
            return val;
        }
        if ("variety".equals(score)) {
            return aisle.size();
        }
        if ("mixed".equals(score)) {
            double units = 0.0;
            for (int qty : aisle.values()) {
                units += qty;
            }
            return units * aisle.size();
        }
        return 0.0;
    }

    private List<Integer> rankAisles(Problem problem, Map<Integer, Integer> totalDemand) {
        List<Integer> indices = new ArrayList<>(problem.nAisles);
        for (int i = 0; i < problem.nAisles; i++) {
            indices.add(i);
        }

        double[] scores = new double[problem.nAisles];
        for (int i = 0; i < problem.nAisles; i++) {
            scores[i] = aisleScore(i, problem, totalDemand);
        }

        indices.sort((a, b) -> Double.compare(scores[b], scores[a]));

        return indices;
    }

    private List<Integer> buildSequence(Problem problem) {
        List<Integer> seq = new ArrayList<>(problem.nOrders);
        for (int i = 0; i < problem.nOrders; i++) {
            seq.add(i);
        }

        seq.sort((a, b) -> Integer.compare(problem.orderUnits[b], problem.orderUnits[a]));

        return seq;
    }

    private void packOrders(
            List<Integer> sequence,
            Problem problem,
            Map<Integer, Integer> inventory,
            List<Integer> selected,
            int[] total) {

        selected.clear();
        total[0] = 0;

        for (int idx : sequence) {
            int size = problem.orderUnits[idx];
            if (total[0] + size > problem.ub) continue;

            Map<Integer, Integer> order = problem.orders.get(idx);
            boolean canFulfill = true;
            for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
                int item = entry.getKey();
                int qty = entry.getValue();
                if (inventory.getOrDefault(item, 0) < qty) {
                    canFulfill = false;
                    break;
                }
            }
            if (!canFulfill) continue;

            selected.add(idx);
            total[0] += size;
            for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
                int item = entry.getKey();
                int qty = entry.getValue();
                inventory.put(item, inventory.get(item) - qty);
            }
        }
    }

    public Solution solve(Problem problem) {
        if (problem.nOrders == 0 || problem.nAisles == 0) {
            return new Solution(problem);
        }

        // Total demand across all orders
        Map<Integer, Integer> totalDemand = new HashMap<>();
        for (Map<Integer, Integer> order : problem.orders) {
            for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
                totalDemand.put(entry.getKey(), totalDemand.getOrDefault(entry.getKey(), 0) + entry.getValue());
            }
        }

        // Rank aisles descending by score
        List<Integer> rankedAisles = rankAisles(problem, totalDemand);

        // Order sequence: descending by size
        List<Integer> sequence = buildSequence(problem);

        List<Integer> bestOrders = new ArrayList<>();
        List<Integer> bestAisles = new ArrayList<>();
        double bestObj = 0.0;

        Map<Integer, Integer> inventory = new HashMap<>();
        int k = 0;
        for (int aisleIdx : rankedAisles) {
            k++;

            // Accumulate inventory
            for (Map.Entry<Integer, Integer> entry : problem.aisles.get(aisleIdx).entrySet()) {
                inventory.put(entry.getKey(), inventory.getOrDefault(entry.getKey(), 0) + entry.getValue());
            }

            // Early stop: ub/k can't beat bestObj
            if ((double) problem.ub / k <= bestObj) break;

            // Pack orders against current inventory
            List<Integer> selected = new ArrayList<>();
            int[] totalUnits = new int[]{0};
            packOrders(sequence, problem, new HashMap<>(inventory), selected, totalUnits);

            if (totalUnits[0] < problem.lb) continue;

            double obj = (double) totalUnits[0] / k;
            if (obj > bestObj) {
                bestObj = obj;
                bestOrders = new ArrayList<>(selected);
                bestAisles = new ArrayList<>(rankedAisles.subList(0, k));
            }
        }

        Solution sol = new Solution(problem);
        if (!bestOrders.isEmpty()) {
            for (int a : bestAisles) {
                sol.addAisle(a);
            }
            for (int o : bestOrders) {
                sol.addOrder(o);
            }
        }
        return sol;
    }
}
