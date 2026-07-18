package heuristic;

import java.io.*;
import java.util.*;

import model.Problem;
import model.Solution;
import neighborhood.Move;

/**
 * Iterated Local Search (ILS) metaheuristic.
 *
 * Cycle:
 * s₀ ← InitialSolution (AisleFirst)
 * s* ← LocalSearch(s₀)
 * while stopping criterion not met:
 * s' ← Perturb(s*) ← guided: remove worst aisles, add best candidates
 * s'' ← LocalSearch(s')
 * s* ← AcceptanceCriterion(s*, s'')
 * return s*
 *
 * Perturbation strategy:
 * - Removes the k aisles with the lowest per-item stock contribution (least
 * useful).
 * - Adds the k unused aisles with the highest total stock (most promising).
 * - Falls back to random selection when candidates are tied or unavailable.
 *
 * @author Generated for PFC2
 */
public class ILS extends Heuristic {

    /** Maximum iterations without improvement in local search phase. */
    private final int maxLocalIters;

    /** Fraction of aisles to perturb (k = strength × |aisles|, min 1). */
    private final double perturbationStrength;

    /**
     * Acceptance threshold: accepts s'' if obj(s'') >= obj(s*) * (1 - threshold).
     */
    private final double acceptanceThreshold;

    /**
     * Instantiates a new ILS.
     *
     * @param problem              the problem reference.
     * @param random               the random number generator.
     * @param maxLocalIters        max iterations without improvement in local
     *                             search.
     * @param perturbationStrength fraction of aisles to perturb.
     * @param acceptanceThreshold  acceptance threshold (0.0 = only improvements,
     *                             0.02 = up to 2% worse).
     */
    public ILS(Problem problem, Random random, int maxLocalIters,
            double perturbationStrength, double acceptanceThreshold) {
        super(problem, random, "ILS");
        this.maxLocalIters = maxLocalIters;
        this.perturbationStrength = perturbationStrength;
        this.acceptanceThreshold = acceptanceThreshold;
    }

    /**
     * Executes the Iterated Local Search.
     *
     * @param initialSolution the initial (input) solution.
     * @param timeLimitMillis the time limit (in milliseconds).
     * @param maxIters        the maximum number of ILS iterations without global
     *                        improvement.
     * @param output          output PrintStream for logging purposes.
     * @return the best solution encountered by the ILS.
     */
    @Override
    public Solution run(Solution initialSolution, long timeLimitMillis, long maxIters, PrintStream output) {
        long finalTimeMillis = System.currentTimeMillis() + timeLimitMillis;

        Solution current = initialSolution.clone();
        current = localSearch(current, finalTimeMillis);

        bestSolution = current.clone();
        long ilsItersWithoutImprovement = 0;

        while (ilsItersWithoutImprovement < maxIters) {

            if (System.currentTimeMillis() >= finalTimeMillis)
                break;

            Solution perturbed = current.clone();
            perturb(perturbed);

            Solution candidate = localSearch(perturbed, finalTimeMillis);

            double candidateObj = candidate.getObj();
            double bestObj = bestSolution.getObj();

            if (candidateObj > bestObj && candidate.getTotalItemsPicked() >= problem.lb) {
                bestSolution = candidate.clone();
                current = candidate;
                ilsItersWithoutImprovement = 0;
                if (output != null) {
                    output.printf("ILS iter %d: New best = %.6f%n", nIters, bestObj);
                }
            } else if (candidateObj >= bestObj * (1.0 - acceptanceThreshold)
                    && candidate.getTotalItemsPicked() >= problem.lb) {
                current = candidate;
                ilsItersWithoutImprovement++;
            } else {
                ilsItersWithoutImprovement++;
            }

            if (ilsItersWithoutImprovement > 0 && ilsItersWithoutImprovement % (maxIters / 2) == 0) {
                current = bestSolution.clone();
            }

            nIters++;
        }

        return bestSolution;
    }

    /**
     * First-improvement local search.
     * Applies random moves until maxLocalIters iterations without improvement.
     *
     * @param solution        the solution to improve (modified in place).
     * @param finalTimeMillis absolute deadline in millis.
     * @return the improved solution.
     */
    private Solution localSearch(Solution solution, long finalTimeMillis) {
        long localItersWithoutImprovement = 0;
        int sidewaysMoves = 0;
        int maxSidewaysMoves = maxLocalIters / 4;

        while (localItersWithoutImprovement < maxLocalIters) {
            // Check time limit
            if (System.currentTimeMillis() >= finalTimeMillis)
                break;

            Move move = selectMove(solution);
            if (move == null)
                break;

            double delta = move.doMove(solution);

            if (delta > 0) {
                // Improvement: accept and reset counters
                acceptMove(move);
                localItersWithoutImprovement = 0;
                sidewaysMoves = 0;
            } else if (delta == 0) {
                // Sideways move: accept but track separately
                acceptMove(move);
                sidewaysMoves++;
                if (sidewaysMoves >= maxSidewaysMoves) {
                    break;
                }
            } else {
                // Worsening move: reject
                rejectMove(move);
                localItersWithoutImprovement++;
            }

        }

        return solution;
    }

    /**
     * Guided perturbation: removes the k least-efficient aisles and adds the
     * k most-promising unused aisles, then rebuilds orders.
     *
     * <p>
     * "Efficiency" of an aisle is its total stock (sum of all item quantities).
     * Aisles with lower stock contribute less to the objective and are removed
     * first.
     * Unused aisles with higher stock are added as replacements.
     *
     * <p>
     * A random tie-breaking jitter is applied so that repeated perturbations on
     * the same solution do not always produce the same result.
     *
     * @param solution the solution to perturb (modified in place).
     */
    private void perturb(Solution solution) {
        int k = Math.max(1, (int) (solution.aisles.size() * perturbationStrength));

        // 1. Calculate demand of the current orders
        int[] residualDemand = new int[problem.nItems];
        for (int o : solution.orders) {
            for (Map.Entry<Integer, Integer> e : problem.orders.get(o).entrySet()) {
                residualDemand[e.getKey()] += e.getValue();
            }
        }

        // --- Remove the k least-useful aisles (using RCL) ---
        List<Integer> presentAisles = new ArrayList<>(solution.aisles);
        double[] presentScores = new double[presentAisles.size()];
        for (int i = 0; i < presentAisles.size(); i++) {
            presentScores[i] = aisleEfficiency(presentAisles.get(i), residualDemand);
        }

        // Sort indices by ascending score (lowest usefulness first)
        Integer[] presentIdx = new Integer[presentAisles.size()];
        for (int i = 0; i < presentIdx.length; i++)
            presentIdx[i] = i;
        Arrays.sort(presentIdx, Comparator.comparingDouble(i -> presentScores[i]));

        int toRemove = Math.min(k, presentAisles.size());
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

        // --- Add the k most-useful unused aisles (using RCL) ---
        List<Integer> absent = new ArrayList<>();
        for (int j = 0; j < problem.nAisles; j++) {
            if (!solution.aislePresent[j])
                absent.add(j);
        }
        double[] absentScores = new double[absent.size()];
        for (int i = 0; i < absent.size(); i++) {
            absentScores[i] = aisleEfficiency(absent.get(i), unmetDemand);
        }

        // Sort indices by descending score (highest usefulness first)
        Integer[] absentIdx = new Integer[absent.size()];
        for (int i = 0; i < absentIdx.length; i++)
            absentIdx[i] = i;
        Arrays.sort(absentIdx, Comparator.comparingDouble(i -> -absentScores[i]));

        int toAdd = Math.min(k, absent.size());
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

    /**
     * Computes the efficiency score of an aisle as its useful stock
     * (sum of all item quantities that fulfill the unmet demand).
     *
     * @param aisleIdx the aisle index.
     * @param demand   the current unmet demand array.
     * @return useful stock quantity in the aisle.
     */
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

    /**
     * Returns the string representation of this heuristic.
     *
     * @return the string representation of this heuristic (with parameter values).
     */
    @Override
    public String toString() {
        return String.format("ILS (maxLocalIters=%d, perturbationStrength=%.2f, acceptanceThreshold=%.3f)",
                maxLocalIters, perturbationStrength, acceptanceThreshold);
    }
}
