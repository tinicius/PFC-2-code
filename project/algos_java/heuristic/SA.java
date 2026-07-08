package heuristic;

import java.io.*;
import java.util.*;

import model.Problem;
import model.Solution;
import neighborhood.Move;

/**
 * This class is a Simulated Annealing implementation.
 *
 * @author Tulio Toffolo
 */
public class SA extends Heuristic {

    /**
     * SA parameters.
     */
    private double alpha, t0;
    private int saMax = 10000;

    private final static double EPS = 1e-6;

    /**
     * Threshold below which Math.exp(ratio) is negligibly small (~2e-9).
     * Avoids expensive exp() call when acceptance is essentially impossible.
     */
    private final static double EXP_CUTOFF = -20.0;

    /**
     * Instantiates a new SA.
     *
     * @param problem problem reference
     * @param random  random number generator.
     * @param alpha   cooling rate for the simulated annealing
     * @param t0      initial temperature, T0
     * @param saMax   number of iterations before update the temperature
     */
    public SA(Problem problem, Random random, double alpha, double t0, int saMax) {
        super(problem, random, "SA");

        // initializing simulated annealing parameters
        this.alpha = alpha;
        this.t0 = t0;
        this.saMax = saMax;
    }

    /**
     * Executes the Simulated Annealing.
     *
     * @param initialSolution the initial (input) solution.
     * @param timeLimitMillis the time limit (in milliseconds).
     * @param maxIters        the maximum number of iterations without improvements
     *                        to execute.
     * @param output          output PrintStream for logging purposes.
     * @return the best solution encountered by the SA.
     */
    public Solution run(Solution initialSolution, long timeLimitMillis, long maxIters, PrintStream output) {
        long finalTimeMillis = System.currentTimeMillis() + timeLimitMillis;

        bestSolution = initialSolution;
        Solution solution = initialSolution.clone();

        double temperature = this.t0;
        long nItersWithoutImprovement = 0;
        int itersInTemperature = 0;

        while (nItersWithoutImprovement < maxIters) {

            // Amortize system call: check time every 1024 iterations
            if ((nIters & 0x3FF) == 0 && System.currentTimeMillis() >= finalTimeMillis)
                break;

            Move move = selectMove(solution);
            if (move == null) break;

            double delta = move.doMove(solution);

            // if solution is improved...
            if (delta > 0) {
                acceptMove(move);

                if (solution.getObj() > bestSolution.getObj()) {
                    bestSolution = solution.clone();
                    nItersWithoutImprovement = 0;
                    if (output != null) {
                        output.printf("Iter %d: New best = %.6f%n", nIters, bestSolution.getObj());
                    }
                } else {
                    nItersWithoutImprovement++;
                }
            }

            // if solution is not improved, but is accepted...
            else if (delta == 0) {
                acceptMove(move);
                nItersWithoutImprovement++;
            }

            // solution is not improved, but may be accepted with a probability...
            else {
                double ratio = delta / temperature;

                // Early reject: skip exp() when probability is negligible
                if (ratio > EXP_CUTOFF && random.nextDouble() < Math.exp(ratio)) {
                    acceptMove(move);
                } else {
                    rejectMove(move);
                }
                nItersWithoutImprovement++;
            }

            // if necessary, updates temperature
            if (++itersInTemperature >= saMax) {
                itersInTemperature = 0;
                temperature = alpha * temperature;
                if (temperature < EPS) {
                    temperature = t0;
                    if (output != null) {
                        output.println("Re-heating Simulated Annealing");
                    }
                }
            }

            nIters++;
        }

        // Print move statistics using built-in Move counters (no extra HashMap needed)
        if (output != null) {
            for (Move move : moves) {
                output.printf("Move: %s, Iters: %d, Improvements: %d, Sideways: %d, Worsens: %d, Rejects: %d%n",
                        move.name, move.getNIters(), move.getNImprovements(),
                        move.getNSideways(), move.getNWorsens(), move.getNRejects());
            }
        }

        return bestSolution;
    }

    /**
     * Returns the string representation of this heuristic.
     *
     * @return the string representation of this heuristic (with parameters values).
     */
    public String toString() {
        return String.format("Simulated Annealing (alpha=%.3f, saMax=%s, t0=%s)",
                alpha, saMax, t0);
    }
}
