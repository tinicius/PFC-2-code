package heuristic;

import java.io.*;
import java.util.*;

import model.Problem;
import model.Solution;
import neighborhood.Move;

/**
 * Iterated Local Search (ILS) with General Variable Neighborhood Search (GVNS).
 *
 * GVNS Cycle:
 * s₀ ← InitialSolution (AisleBased)
 * s* ← VND(s₀)
 * while stopping criterion not met:
 * s' ← Shake(s*, k) ← guided: remove worst aisles, add best candidates
 * (strength k)
 * s'' ← VND(s')
 * s* ← AcceptanceCriterion(s*, s'')
 * Update k (reset to 1 on strict improvement, else increment/wrap)
 * return s*
 *
 * Shaking strategy:
 * - Removes the k*base aisles with the lowest per-item stock contribution.
 * - Adds the k*base unused aisles with the highest total stock.
 *
 * @author Generated for PFC2
 */
public class GVNS extends Heuristic {

    /** Maximum iterations without improvement in local search phase per tier. */
    private final int maxLocalIters;

    /** Fraction of aisles to perturb (baseK = strength × |aisles|, min 1). */
    private final double perturbationStrength;

    /**
     * Acceptance threshold: accepts s'' if obj(s'') >= obj(s*) * (1 - threshold).
     */
    private final double acceptanceThreshold;

    /**
     * Maximum VNS shaking level before wrapping back to 1.
     */
    private final int kMax;

    /**
     * Instantiates a new GVNS/ILS.
     *
     * @param problem              the problem reference.
     * @param random               the random number generator.
     * @param maxLocalIters        max iterations without improvement in local
     *                             search.
     * @param perturbationStrength fraction of aisles to perturb for k=1.
     * @param acceptanceThreshold  acceptance threshold.
     */
    public GVNS(Problem problem, Random random, int maxLocalIters,
            double perturbationStrength, double acceptanceThreshold) {
        this(problem, random, maxLocalIters, perturbationStrength, acceptanceThreshold, 5);
    }

    /**
     * Instantiates a new GVNS/ILS with explicit shaking limit.
     *
     * @param problem              the problem reference.
     * @param random               the random number generator.
     * @param maxLocalIters        max iterations without improvement in local
     *                             search.
     * @param perturbationStrength fraction of aisles to perturb for k=1.
     * @param acceptanceThreshold  acceptance threshold.
     * @param kMax                 maximum shaking level before wrap-reset.
     */
    public GVNS(Problem problem, Random random, int maxLocalIters,
            double perturbationStrength, double acceptanceThreshold, int kMax) {
        super(problem, random, "GVNS");
        this.maxLocalIters = maxLocalIters;
        this.perturbationStrength = perturbationStrength;
        this.acceptanceThreshold = acceptanceThreshold;
        this.kMax = kMax;
    }

    @Override
    public Solution run(Solution initialSolution, long timeLimitMillis, long maxIters, PrintStream output) {
        long finalTimeMillis = System.currentTimeMillis() + timeLimitMillis;

        Solution current = initialSolution.clone();
        current = vnd(current, finalTimeMillis);

        bestSolution = current.clone();

        int k = 1; // VNS shaking level

        while (System.currentTimeMillis() < finalTimeMillis) {

            Solution shaken = current.clone();
            shake(shaken, k);

            Solution candidate = vnd(shaken, finalTimeMillis);

            double candidateObj = candidate.getObj();
            double bestObj = bestSolution.getObj();

            if (candidateObj > bestObj && candidate.getTotalItemsPicked() >= problem.lb) {
                // Strict global improvement
                bestSolution = candidate.clone();
                current = candidate;
                k = 1; // strict reset
                if (output != null) {
                    output.printf("GVNS iter %d (k=%d): New best = %.6f%n", nIters, k, candidateObj);
                }
            } else if (candidateObj >= bestObj * (1.0 - acceptanceThreshold)
                    && candidate.getTotalItemsPicked() >= problem.lb) {
                // Acceptable lateral/worse move
                current = candidate;
                k = (k < kMax) ? k + 1 : 1; // escalate since we didn't improve on best
            } else {
                // Reject move
                k = (k < kMax) ? k + 1 : 1; // escalate
            }

            nIters++;
        }

        return bestSolution;
    }

    /**
     * Variable Neighborhood Descent (VND) local search.
     *
     * Neighborhoods are arranged in tiers by structural cost:
     * T1: Order-level moves (AddOrder, RemoveOrder, SwapOrder)
     * T2: Single-aisle moves (AddAisle, RemoveAisle)
     * T3: Aisle swap (SwapAisle)
     *
     * Rule: on any improvement → restart from T1.
     * on tier exhaustion → escalate to next tier.
     * when all tiers exhausted → return.
     */
    private Solution vnd(Solution solution, long finalTimeMillis) {
        List<List<Move>> tiers = buildTiers();
        if (tiers.isEmpty())
            return solution;

        int currentTier = 0;
        int tierTrials = 0;
        int maxTrialsPerTier = Math.max(maxLocalIters / Math.max(tiers.size(), 1), 50);
        int sidewaysInTier = 0;
        int maxSidewaysPerTier = maxTrialsPerTier / 4;

        while (currentTier < tiers.size()) {
            if (System.currentTimeMillis() >= finalTimeMillis)
                break;

            Move move = selectFromTier(tiers.get(currentTier), solution);

            if (move == null) {
                // No applicable move in tier → escalate immediately
                currentTier++;
                tierTrials = 0;
                sidewaysInTier = 0;
                continue;
            }

            double delta = move.doMove(solution);

            if (delta > 0) {
                // Improvement → accept and restart from T1
                acceptMove(move);
                currentTier = 0;
                tierTrials = 0;
                sidewaysInTier = 0;
            } else if (delta == 0) {
                // Sideways move → accept but count as non-improving
                acceptMove(move);
                tierTrials++;
                sidewaysInTier++;
                if (sidewaysInTier >= maxSidewaysPerTier) {
                    currentTier++; // too many sideways → escalate
                    tierTrials = 0;
                    sidewaysInTier = 0;
                }
            } else {
                // Worsening → reject
                rejectMove(move);
                tierTrials++;
                if (tierTrials >= maxTrialsPerTier) {
                    currentTier++; // patience exhausted → escalate
                    tierTrials = 0;
                    sidewaysInTier = 0;
                }
            }
        }

        return solution;
    }

    private List<List<Move>> buildTiers() {
        List<Move> t1 = new ArrayList<>(); // order-level
        List<Move> t2 = new ArrayList<>(); // single-aisle
        List<Move> t3 = new ArrayList<>(); // aisle swap

        for (Move m : moves) {
            String n = m.name;
            if (n.contains("Order")) {
                t1.add(m);
            } else if (n.equals("SwapAisle")) {
                t3.add(m);
            } else {
                t2.add(m); // AddAisle, RemoveAisle
            }
        }

        List<List<Move>> tiers = new ArrayList<>();
        if (!t1.isEmpty())
            tiers.add(t1);
        if (!t2.isEmpty())
            tiers.add(t2);
        if (!t3.isEmpty())
            tiers.add(t3);
        return tiers;
    }

    private Move selectFromTier(List<Move> tier, Solution solution) {
        List<Move> applicable = new ArrayList<>();
        for (Move m : tier) {
            if (m.hasMove(solution))
                applicable.add(m);
        }
        if (applicable.isEmpty())
            return null;
        return applicable.get(random.nextInt(applicable.size()));
    }

    /**
     * VNS shaking at intensity level k.
     * Removes level*baseK least-efficient aisles and adds level*baseK
     * most-promising ones.
     */
    private void shake(Solution solution, int level) {
        int baseK = Math.max(1, (int) (solution.aisles.size() * perturbationStrength));
        int toSwap = Math.min(level * baseK, solution.aisles.size());

        if (toSwap == 0)
            return;

        // 1. Calculate demand of the current orders
        int[] residualDemand = new int[problem.nItems];
        for (int o : solution.orders) {
            for (Map.Entry<Integer, Integer> e : problem.orders.get(o).entrySet()) {
                residualDemand[e.getKey()] += e.getValue();
            }
        }

        // --- Remove the least-useful aisles (using RCL) ---
        List<Integer> presentAisles = new ArrayList<>(solution.aisles);
        double[] presentScores = new double[presentAisles.size()];
        for (int i = 0; i < presentAisles.size(); i++) {
            presentScores[i] = aisleEfficiency(presentAisles.get(i), residualDemand);
        }

        Integer[] presentIdx = new Integer[presentAisles.size()];
        for (int i = 0; i < presentIdx.length; i++)
            presentIdx[i] = i;
        Arrays.sort(presentIdx, Comparator.comparingDouble(i -> presentScores[i]));

        int toRemove = Math.min(toSwap, presentAisles.size());
        int rclRemoveSize = Math.min(presentAisles.size(), Math.max(toRemove * 2, (int) (presentAisles.size() * 0.2)));

        List<Integer> rclRemove = new ArrayList<>();
        for (int i = 0; i < rclRemoveSize; i++) {
            rclRemove.add(presentAisles.get(presentIdx[i]));
        }
        Collections.shuffle(rclRemove, random);

        for (int i = 0; i < toRemove; i++) {
            solution.removeAisle(rclRemove.get(i));
        }

        // 2. Calculate remaining unmet demand
        int[] inventory = new int[problem.nItems];
        for (int a : solution.aisles) {
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(a).entrySet()) {
                inventory[e.getKey()] += e.getValue();
            }
        }
        int[] unmetDemand = new int[problem.nItems];
        for (int i = 0; i < problem.nItems; i++) {
            unmetDemand[i] = Math.max(0, residualDemand[i] - inventory[i]);
        }

        // --- Add the most-useful unused aisles (using RCL) ---
        List<Integer> absent = new ArrayList<>();
        for (int j = 0; j < problem.nAisles; j++) {
            if (!solution.aislePresent[j])
                absent.add(j);
        }
        double[] absentScores = new double[absent.size()];
        for (int i = 0; i < absent.size(); i++) {
            absentScores[i] = aisleEfficiency(absent.get(i), unmetDemand);
        }

        Integer[] absentIdx = new Integer[absent.size()];
        for (int i = 0; i < absentIdx.length; i++)
            absentIdx[i] = i;
        Arrays.sort(absentIdx, Comparator.comparingDouble(i -> -absentScores[i]));

        int toAdd = Math.min(toSwap, absent.size());
        if (toAdd > 0 && absent.size() > 0) {
            int rclAddSize = Math.min(absent.size(), Math.max(toAdd * 2, (int) (absent.size() * 0.2)));
            List<Integer> rclAdd = new ArrayList<>();
            for (int i = 0; i < rclAddSize; i++) {
                rclAdd.add(absent.get(absentIdx[i]));
            }
            Collections.shuffle(rclAdd, random);

            for (int i = 0; i < toAdd; i++) {
                solution.addAisle(rclAdd.get(i));
            }
        }

        // Rebuild orders with the new aisle configuration
        solution.randomizedGreedyRebuildOrders(random);
    }

    private double aisleEfficiency(int aisleIdx, int[] demand) {
        double val = 0.0;
        for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisleIdx).entrySet()) {
            int d = demand[e.getKey()];
            if (d > 0) {
                val += Math.min(e.getValue(), d);
            }
        }
        return val;
    }

    @Override
    public String toString() {
        return String.format("GVNS (maxLocalIters=%d, perturbationStrength=%.2f, acceptanceThreshold=%.3f, kMax=%d)",
                maxLocalIters, perturbationStrength, acceptanceThreshold, kMax);
    }
}
