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
 *   s₀ ← InitialSolution (AisleFirst)
 *   s* ← LocalSearch(s₀)
 *   while stopping criterion not met:
 *       s' ← Perturb(s*)
 *       s'' ← LocalSearch(s')
 *       s* ← AcceptanceCriterion(s*, s'')
 *   return s*
 *
 * @author Generated for PFC2
 */
public class ILS extends Heuristic {

    /** Maximum iterations without improvement in local search phase. */
    private final int maxLocalIters;

    /** Fraction of aisles to perturb (k = strength × |aisles|, min 1). */
    private final double perturbationStrength;

    /** Acceptance threshold: accepts s'' if obj(s'') >= obj(s*) * (1 - threshold). */
    private final double acceptanceThreshold;

    /**
     * Instantiates a new ILS.
     *
     * @param problem               the problem reference.
     * @param random                the random number generator.
     * @param maxLocalIters         max iterations without improvement in local search.
     * @param perturbationStrength  fraction of aisles to perturb.
     * @param acceptanceThreshold   acceptance threshold (0.0 = only improvements, 0.02 = up to 2% worse).
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
     * @param maxIters        the maximum number of ILS iterations without global improvement.
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

        if (output != null) {
            output.printf("ILS initial local search: obj = %.6f%n", bestSolution.getObj());
        }

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

        // Print move statistics
        if (output != null) {
            output.printf("ILS completed: %d iterations%n", nIters);
            for (Move move : moves) {
                output.printf("Move: %s, Iters: %d, Improvements: %d, Sideways: %d, Worsens: %d, Rejects: %d%n",
                        move.name, move.getNIters(), move.getNImprovements(),
                        move.getNSideways(), move.getNWorsens(), move.getNRejects());
            }
        }

        return bestSolution;
    }

    /**
     * First-improvement local search.
     * Applies random moves until maxLocalIters iterations without improvement.
     *
     * @param solution       the solution to improve (modified in place).
     * @param finalTimeMillis absolute deadline in millis.
     * @return the improved solution.
     */
    private Solution localSearch(Solution solution, long finalTimeMillis) {
        long localItersWithoutImprovement = 0;
        long localIters = 0;

        while (localItersWithoutImprovement < maxLocalIters) {
            // Amortize system call: check time every 1024 iterations
            if ((localIters & 0x3FF) == 0 && System.currentTimeMillis() >= finalTimeMillis)
                break;

            Move move = selectMove(solution);
            if (move == null) break;

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
     * Perturbation: randomly removes and adds aisles, then rebuilds orders.
     * The number of aisles affected is controlled by perturbationStrength.
     *
     * @param solution the solution to perturb (modified in place).
     */
    private void perturb(Solution solution) {
        int k = Math.max(1, (int) (solution.aisles.size() * perturbationStrength));

        // Remove k random aisles
        for (int i = 0; i < k; i++) {
            if (solution.aisles.size() > 0) {
                int idx = random.nextInt(solution.aisles.size());
                solution.removeAisle(solution.aisles.get(idx));
            }
        }

        // Add k random aisles (not already present)
        for (int i = 0; i < k; i++) {
            List<Integer> available = new ArrayList<>();
            for (int j = 0; j < problem.nAisles; j++) {
                if (!solution.aislePresent[j]) available.add(j);
            }
            if (!available.isEmpty()) {
                int a = available.get(random.nextInt(available.size()));
                solution.addAisle(a);
            }
        }

        // Rebuild orders with the new aisle configuration
        solution.randomizedGreedyRebuildOrders(random);
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
