package heuristic;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

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

        // Phase 1: Local search on the initial solution
        Solution current = initialSolution.clone();
        current = localSearch(current, finalTimeMillis);

        bestSolution = current.clone();
        long ilsItersWithoutImprovement = 0;

        // Phase 2: ILS main loop
        while (ilsItersWithoutImprovement < maxIters) {
            // Check time limit every iteration
            if (System.currentTimeMillis() >= finalTimeMillis)
                break;

            // Perturb the current solution
            Solution perturbed = current.clone();
            perturb(perturbed);

            // Apply local search to the perturbed solution
            Solution candidate = localSearch(perturbed, finalTimeMillis);

            // Acceptance criterion
            double candidateObj = candidate.getObj();
            double currentObj = current.getObj();
            double bestObj = bestSolution.getObj();

            // Always accept improvements over the global best
            if (candidateObj > bestObj && candidate.getTotalItemsPicked() >= problem.lb) {
                bestSolution = candidate.clone();
                current = candidate;
                ilsItersWithoutImprovement = 0;
                if (output != null) {
                    output.printf("ILS iter %d: New best = %.6f%n", nIters, bestObj);
                }
            }
            // Threshold acceptance: accept if within threshold of current
            else if (candidateObj >= currentObj * (1.0 - acceptanceThreshold)) {
                current = candidate;
                ilsItersWithoutImprovement++;
            }
            // Reject
            else {
                ilsItersWithoutImprovement++;
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
        long localIters = 0;

        while (localItersWithoutImprovement < maxLocalIters) {
            // Check time limit
            if (System.currentTimeMillis() >= finalTimeMillis)
                break;

            Move move = selectMove(solution);
            if (move == null)
                break;

            double delta = move.doMove(solution);

            if (delta > 0) {
                // Improvement: accept and reset counter
                acceptMove(move);
                localItersWithoutImprovement = 0;
            } else if (delta == 0) {
                // Sideways move: accept but count as no improvement
                acceptMove(move);
                localItersWithoutImprovement++;
            } else {
                // Worsening move: reject
                rejectMove(move);
                localItersWithoutImprovement++;
            }

            localIters++;
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

        // --- Remove the k least-efficient aisles ---
        // Pre-compute jittered scores so the comparator is stable (TimSort requires
        // it).
        List<Integer> presentAisles = new ArrayList<>(solution.aisles);
        double[] presentScores = new double[presentAisles.size()];
        for (int i = 0; i < presentAisles.size(); i++) {
            presentScores[i] = aisleEfficiency(presentAisles.get(i)) + random.nextDouble() * 0.01;
        }
        // Sort indices by ascending score (lowest efficiency first)
        Integer[] presentIdx = new Integer[presentAisles.size()];
        for (int i = 0; i < presentIdx.length; i++)
            presentIdx[i] = i;
        Arrays.sort(presentIdx, Comparator.comparingDouble(i -> presentScores[i]));

        int toRemove = Math.min(k, presentAisles.size());
        for (int i = 0; i < toRemove; i++) {
            solution.removeAisle(presentAisles.get(presentIdx[i]));
        }

        // --- Add the k most-promising unused aisles ---
        // Pre-compute jittered scores for absent aisles.
        List<Integer> absent = new ArrayList<>();
        for (int j = 0; j < problem.nAisles; j++) {
            if (!solution.aislePresent[j])
                absent.add(j);
        }
        double[] absentScores = new double[absent.size()];
        for (int i = 0; i < absent.size(); i++) {
            absentScores[i] = aisleEfficiency(absent.get(i)) + random.nextDouble() * 0.01;
        }
        // Sort indices by descending score (highest efficiency first)
        Integer[] absentIdx = new Integer[absent.size()];
        for (int i = 0; i < absentIdx.length; i++)
            absentIdx[i] = i;
        Arrays.sort(absentIdx, Comparator.comparingDouble(i -> -absentScores[i]));

        int toAdd = Math.min(k, absent.size());
        for (int i = 0; i < toAdd; i++) {
            solution.addAisle(absent.get(absentIdx[i]));
        }

        // Rebuild orders with the new aisle configuration
        solution.randomizedGreedyRebuildOrders(random);
    }

    /**
     * Computes the efficiency score of an aisle as its total stock
     * (sum of all item quantities stored in it).
     *
     * @param aisleIdx the aisle index.
     * @return total stock quantity in the aisle.
     */
    private double aisleEfficiency(int aisleIdx) {
        int total = 0;
        for (int qty : problem.aisles.get(aisleIdx).values()) {
            total += qty;
        }
        return total;
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
