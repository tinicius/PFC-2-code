package heuristic;

import java.io.*;
import java.util.*;

import model.Problem;
import model.Solution;
import neighborhood.AddAisle;
import neighborhood.AddAisleBest;
import neighborhood.AddOrder;
import neighborhood.Move;
import neighborhood.PruneAisles;
import neighborhood.RemoveAisle;
import neighborhood.RemoveAisleBest;
import neighborhood.RemoveOrder;
import neighborhood.SwapOrder;
import util.Ranking;

/**
 * Iterated Local Search (ILS) with a Variable Neighborhood Descent (VND) local
 * search: ILS-VND.
 *
 * <pre>
 * s0  <- initial solution (built externally, e.g. AisleBased)
 * s   <- VND(s0)
 * s*  <- s (if feasible)
 * while time remains and iteration budget is not exhausted:
 *     s'  <- Shake(s)            guided aisle exchange + feasibility repair
 *     s'' <- VND(s')
 *     if s'' improves s      : s <- s'';  s* <- s'' if it beats s*
 *     else if s'' is within acceptanceThreshold of s : s <- s''
 * return s*
 * </pre>
 *
 * The perturbation intensity is constant, max(1, floor(|A_s| * perturbationStrength)),
 * and diversification comes from the randomized candidate lists of the shake.
 *
 * Acceptance follows the standard ILS scheme: the candidate is compared to
 * the <em>current</em> solution (which is what the next shake starts from), while
 * the global best is tracked separately and only ever updated with feasible
 * strict improvements.
 *
 * The local search is a first-improvement VND over four tiers ordered by their
 * effect on the objective U/A (units picked / aisles visited):
 *
 * <pre>
 * T1  PruneAisles, RemoveAisleBest   shrink A: guaranteed or screened gains
 * T2  SwapOrder, AddOrder            grow U at fixed A
 * T3  AddAisleBest                   grow A only when it pays above U/A
 * T4  SwapAisle                      most expensive structural exchange
 * </pre>
 *
 * RemoveOrder is deliberately not used here: removing an order strictly lowers U
 * without changing A, so it can never be accepted by a non-worsening descent.
 */
public class IlsVnd extends Heuristic {

    /** Tolerance used for objective comparisons. */
    private static final double EPS = 1e-9;

    /** Default number of aisle additions attempted to repair a broken shake. */
    private static final int DEFAULT_MAX_REPAIR_ATTEMPTS = 10;

    /** Default fraction of the candidate pool kept in a shake RCL. */
    private static final double DEFAULT_RCL_FRACTION = 0.2;

    /** Safety cap on move evaluations per VND call. */
    private final int maxLocalIters;

    /** Fraction of the solution's aisles perturbed per shake (intensity = strength × |aisles|, min 1). */
    private final double perturbationStrength;

    /** Accepts s'' as the new current solution if obj(s'') >= obj(current) × (1 − threshold). */
    private final double acceptanceThreshold;

    /** Number of attempts granted per pass to a stochastic neighborhood. */
    private final int stochasticAttempts;

    /** Maximum number of aisle additions attempted to repair a broken shake. */
    private final int maxRepairAttempts;

    /** Fraction of the present aisles kept as candidates when dropping aisles. */
    private final double rclRemoveFraction;

    /** Fraction of the absent aisles kept as candidates when adding aisles. */
    private final double rclAddFraction;

    /**
     * Consecutive outer iterations without a new best allowed before the search
     * stops; 0 disables the criterion (time limit only).
     *
     * <p>
     * The threshold is expressed in iterations, not seconds, on purpose: iteration
     * rates in this problem span four orders of magnitude across instances, so an
     * iteration budget can only be exhausted where iterations are cheap. Small
     * instances stop in seconds while large ones, which perform few iterations in
     * the whole budget, are never cut.
     */
    private long maxNoImprovementIters = 0;

    /** Neighborhood tiers, built once from the registered moves. */
    private List<List<Move>> tiers;

    /** Move evaluations consumed by the VND call in progress. */
    private int vndEvals;

    // run statistics
    private long nVndCalls = 0;
    private long nMovesApplied = 0;
    private long nShakes = 0;
    private long nShakeRepairs = 0;
    private long nShakeFailures = 0;

    /** Outer iterations executed since the last new best solution. */
    private long noImprovementIters = 0;

    /** Why the outer loop ended, reported in the summary line. */
    private String stopReason = "time_limit";

    /** Total demand of every order in the instance, used to guide shake repairs. */
    private final int[] instanceDemand;

    /** Total stock per aisle, used as a deterministic tie-break when ranking aisles. */
    private final double[] aisleStock;

    /** Reusable buffers. */
    private final int[] demandBuffer;
    private final int[] unmetBuffer;

    enum Acceptance {
        IMPROVED, ACCEPTED, REJECTED
    }

    /**
     * Instantiates a new ILS-VND with the legacy defaults for the shake knobs.
     *
     * <p>
     * {@code stochasticAttempts} keeps the historical derivation from
     * {@code maxLocalIters} so that existing callers behave exactly as before.
     */
    public IlsVnd(Problem problem, Random random, int maxLocalIters,
            double perturbationStrength, double acceptanceThreshold) {
        this(problem, random, maxLocalIters, perturbationStrength, acceptanceThreshold,
                defaultStochasticAttempts(maxLocalIters), DEFAULT_MAX_REPAIR_ATTEMPTS,
                DEFAULT_RCL_FRACTION, DEFAULT_RCL_FRACTION);
    }

    /**
     * Historical rule that tied the stochastic sampling effort to the VND cap.
     * Kept as the default so the shorter constructors are behavior-preserving.
     */
    public static int defaultStochasticAttempts(int maxLocalIters) {
        return Math.max(5, maxLocalIters / 100);
    }

    /**
     * Instantiates a new ILS-VND.
     *
     * @param problem              the problem reference.
     * @param random               the random number generator.
     * @param maxLocalIters        safety cap on move evaluations per VND call.
     * @param perturbationStrength fraction of the solution's aisles perturbed per shake, in (0, 1].
     * @param acceptanceThreshold  relative tolerance for accepting worse solutions.
     * @param stochasticAttempts   attempts granted per pass to a stochastic move, >= 1.
     * @param maxRepairAttempts    aisle additions allowed to repair a broken shake, >= 0.
     * @param rclRemoveFraction    fraction of present aisles kept as drop candidates, in (0, 1].
     * @param rclAddFraction       fraction of absent aisles kept as add candidates, in (0, 1].
     */
    public IlsVnd(Problem problem, Random random, int maxLocalIters,
            double perturbationStrength, double acceptanceThreshold,
            int stochasticAttempts, int maxRepairAttempts,
            double rclRemoveFraction, double rclAddFraction) {
        super(problem, random, "ILS-VND");

        if (maxLocalIters <= 0)
            throw new IllegalArgumentException("maxLocalIters must be positive (got " + maxLocalIters + ")");
        if (perturbationStrength <= 0.0 || perturbationStrength > 1.0)
            throw new IllegalArgumentException("perturbationStrength must be in (0, 1] (got " + perturbationStrength + ")");
        if (acceptanceThreshold < 0.0 || acceptanceThreshold >= 1.0)
            throw new IllegalArgumentException("acceptanceThreshold must be in [0, 1) (got " + acceptanceThreshold + ")");
        if (stochasticAttempts < 1)
            throw new IllegalArgumentException("stochasticAttempts must be at least 1 (got " + stochasticAttempts + ")");
        if (maxRepairAttempts < 0)
            throw new IllegalArgumentException("maxRepairAttempts must be non-negative (got " + maxRepairAttempts + ")");
        if (rclRemoveFraction <= 0.0 || rclRemoveFraction > 1.0)
            throw new IllegalArgumentException("rclRemoveFraction must be in (0, 1] (got " + rclRemoveFraction + ")");
        if (rclAddFraction <= 0.0 || rclAddFraction > 1.0)
            throw new IllegalArgumentException("rclAddFraction must be in (0, 1] (got " + rclAddFraction + ")");

        this.maxLocalIters = maxLocalIters;
        this.perturbationStrength = perturbationStrength;
        this.acceptanceThreshold = acceptanceThreshold;
        this.stochasticAttempts = stochasticAttempts;
        this.maxRepairAttempts = maxRepairAttempts;
        this.rclRemoveFraction = rclRemoveFraction;
        this.rclAddFraction = rclAddFraction;

        this.instanceDemand = new int[problem.nItems];
        for (Map<Integer, Integer> order : problem.orders) {
            for (Map.Entry<Integer, Integer> e : order.entrySet()) {
                instanceDemand[e.getKey()] += e.getValue();
            }
        }

        this.aisleStock = new double[problem.nAisles];
        for (int aisle = 0; aisle < problem.nAisles; aisle++) {
            double total = 0.0;
            for (int qty : problem.aisles.get(aisle).values()) {
                total += qty;
            }
            aisleStock[aisle] = total;
        }

        this.demandBuffer = new int[problem.nItems];
        this.unmetBuffer = new int[problem.nItems];
    }

    @Override
    public Solution run(Solution initialSolution, long timeLimitMillis, long maxIters, PrintStream output) {
        long startMillis = System.currentTimeMillis();
        long finalTimeMillis = startMillis + timeLimitMillis;

        tiers = buildTiers(output);

        Solution current = initialSolution.clone();
        vnd(current, finalTimeMillis);

        bestSolution = current.isFeasible() ? current.clone() : null;

        while (System.currentTimeMillis() < finalTimeMillis && (maxIters <= 0 || nIters < maxIters)) {
            if (maxNoImprovementIters > 0 && noImprovementIters >= maxNoImprovementIters) {
                stopReason = "no_improvement";
                break;
            }

            Solution candidate = current.clone();
            shake(candidate);
            vnd(candidate, finalTimeMillis);

            nIters++;
            noImprovementIters++;

            switch (accept(candidate, current)) {
                case IMPROVED:
                    current = candidate;
                    if (bestSolution == null || current.getObj() > bestSolution.getObj() + EPS) {
                        bestSolution = current.clone();
                        noImprovementIters = 0;
                        if (output != null) {
                            output.printf(Locale.US,
                                    "ILS-VND iter %d at %.3fs: New best = %.6f%n",
                                    nIters,
                                    (System.currentTimeMillis() - startMillis) / 1000.0,
                                    bestSolution.getObj());
                        }
                    }
                    break;

                case ACCEPTED:
                    current = candidate;
                    break;

                case REJECTED:
                default:
                    break;
            }
        }

        if (stopReason.equals("time_limit") && maxIters > 0 && nIters >= maxIters) {
            stopReason = "max_iters";
        }

        if (bestSolution == null) {
            if (output != null) {
                output.println("ILS-VND warning: no feasible solution found, returning the initial solution.");
            }
            bestSolution = initialSolution.clone();
        }

        logSummary(output, System.currentTimeMillis() - startMillis);

        return bestSolution;
    }

    /**
     * Standard ILS acceptance: the candidate is compared against the current
     * solution, not against the global best.
     *
     * Package-private for testing.
     */
    Acceptance accept(Solution candidate, Solution current) {
        if (!candidate.isFeasible()) {
            return Acceptance.REJECTED;
        }
        if (!current.isFeasible()) {
            return Acceptance.IMPROVED;
        }

        double candidateObj = candidate.getObj();
        double currentObj = current.getObj();

        if (candidateObj > currentObj + EPS) {
            return Acceptance.IMPROVED;
        }
        if (candidateObj >= currentObj * (1.0 - acceptanceThreshold) - EPS) {
            return Acceptance.ACCEPTED;
        }
        return Acceptance.REJECTED;
    }

    // region local search

    /**
     * First-improvement Variable Neighborhood Descent.
     *
     * Tiers are explored in order; the first strict improvement found restarts the
     * descent from T1. The search stops when a full sweep over every tier yields
     * no improvement, i.e. when the neighborhoods are genuinely exhausted rather
     * than when an arbitrary patience counter runs out. Plateau moves are rejected
     * so that the descent is strictly monotone and cannot cycle.
     *
     * Package-private for testing.
     */
    Solution vnd(Solution solution, long finalTimeMillis) {
        if (tiers == null) {
            tiers = buildTiers(null);
        }

        nVndCalls++;
        vndEvals = 0;

        boolean improved = true;
        while (improved && vndEvals < maxLocalIters && System.currentTimeMillis() < finalTimeMillis) {
            improved = false;
            for (List<Move> tier : tiers) {
                if (exploreTier(tier, solution, finalTimeMillis)) {
                    improved = true;
                    break; // restart from the cheapest tier
                }
                if (vndEvals >= maxLocalIters || System.currentTimeMillis() >= finalTimeMillis) {
                    break;
                }
            }
        }

        return solution;
    }

    /**
     * Attempts every move of a tier, returning true as soon as one strictly
     * improves the solution.
     */
    private boolean exploreTier(List<Move> tier, Solution solution, long finalTimeMillis) {
        for (Move move : tier) {
            int attempts = move.isDeterministic() ? 1 : stochasticAttempts;

            for (int attempt = 0; attempt < attempts; attempt++) {
                if (vndEvals >= maxLocalIters || System.currentTimeMillis() >= finalTimeMillis) {
                    return false;
                }
                if (!move.hasMove(solution)) {
                    break;
                }

                double delta = move.doMove(solution);
                vndEvals++;

                if (!move.wasApplied()) {
                    // Nothing to offer for this solution: stop retrying it.
                    rejectMove(move);
                    break;
                }

                if (delta > EPS) {
                    acceptMove(move);
                    nMovesApplied++;
                    return true;
                }

                rejectMove(move);
            }
        }

        return false;
    }

    /**
     * Groups the registered moves into the four tiers described in the class
     * documentation. Classification is by type, not by name, so renaming a move
     * cannot silently change the search behavior.
     */
    private List<List<Move>> buildTiers(PrintStream output) {
        List<Move> t1 = new ArrayList<>(); // shrink the denominator
        List<Move> t2 = new ArrayList<>(); // grow the numerator at fixed A
        List<Move> t3 = new ArrayList<>(); // grow the denominator when it pays
        List<Move> t4 = new ArrayList<>(); // structural exchange

        for (Move move : moves) {
            if (move instanceof PruneAisles || move instanceof RemoveAisleBest || move instanceof RemoveAisle) {
                t1.add(move);
            } else if (move instanceof SwapOrder || move instanceof AddOrder) {
                t2.add(move);
            } else if (move instanceof AddAisleBest || move instanceof AddAisle) {
                t3.add(move);
            } else if (move instanceof RemoveOrder) {
                if (output != null) {
                    output.printf("ILS-VND: ignoring move %s (it can never improve U/A)%n", move.name);
                }
            } else {
                t4.add(move); // SwapAisle and any future structural move
            }
        }

        List<List<Move>> result = new ArrayList<>();
        if (!t1.isEmpty())
            result.add(t1);
        if (!t2.isEmpty())
            result.add(t2);
        if (!t3.isEmpty())
            result.add(t3);
        if (!t4.isEmpty())
            result.add(t4);
        return result;
    }

    // endregion local search

    // region shaking

    /**
     * ILS perturbation with constant intensity, guaranteed to leave the solution feasible
     * or unchanged.
     *
     * Package-private for testing.
     */
    void shake(Solution solution) {
        Solution snapshot = solution.clone();
        nShakes++;

        if (shakeAttempt(solution)) {
            return;
        }

        // The perturbation broke the lower bound and could not be repaired: discard it.
        solution.restoreFrom(snapshot);
        nShakeFailures++;
    }

    /**
     * Single shaking attempt: drops the least useful aisles, adds the most
     * promising unused ones, rebuilds the wave and repairs the lower bound.
     *
     * @return true if the resulting solution is feasible.
     */
    private boolean shakeAttempt(Solution solution) {
        int presentCount = solution.aisles.size();
        int intensity = Math.max(1, (int) (presentCount * perturbationStrength));

        boolean bootstrap = solution.aisles.isEmpty() || solution.orders.isEmpty();

        // Demand used to score aisles: the selected wave, or the whole instance
        // when there is no wave yet to guide the perturbation.
        if (bootstrap) {
            System.arraycopy(instanceDemand, 0, demandBuffer, 0, demandBuffer.length);
        } else {
            solution.computeOrderDemand(demandBuffer);
        }

        // --- drop the least useful aisles, always keeping at least one ---
        int toRemove = Math.min(intensity, Math.max(0, presentCount - 1));
        int removed = 0;
        if (toRemove > 0) {
            int[] ids = new int[presentCount];
            double[] score = new double[presentCount];
            double[] tieBreak = new double[presentCount];
            for (int i = 0; i < presentCount; i++) {
                int aisle = solution.aisles.get(i);
                ids[i] = aisle;
                score[i] = coverage(aisle, demandBuffer);
                tieBreak[i] = -aisleStock[aisle]; // prefer dropping poorer aisles
            }

            int rclSize = Math.min(presentCount,
                    Math.max(2 * toRemove, (int) Math.ceil(presentCount * rclRemoveFraction)));
            int[] rcl = Ranking.select(score, tieBreak, ids, rclSize, true);

            List<Integer> shuffled = new ArrayList<>(rcl.length);
            for (int aisle : rcl) {
                shuffled.add(aisle);
            }
            Collections.shuffle(shuffled, random);

            for (int i = 0; i < toRemove && i < shuffled.size(); i++) {
                solution.removeAisle(shuffled.get(i));
                removed++;
            }
        }

        // --- add the most promising unused aisles ---
        boolean anyUnmet = false;
        for (int item = 0; item < problem.nItems; item++) {
            unmetBuffer[item] = Math.max(0, demandBuffer[item] - solution.stock[item]);
            if (unmetBuffer[item] > 0) {
                anyUnmet = true;
            }
        }

        // With nothing left unmet, extra aisles would only inflate the denominator.
        int toAdd = (bootstrap || anyUnmet) ? intensity : 0;
        int added = 0;
        if (toAdd > 0) {
            int[] absent = absentAisles(solution);
            toAdd = Math.min(toAdd, absent.length);
            if (toAdd > 0) {
                double[] score = new double[absent.length];
                double[] tieBreak = new double[absent.length];
                for (int i = 0; i < absent.length; i++) {
                    score[i] = coverage(absent[i], unmetBuffer);
                    tieBreak[i] = aisleStock[absent[i]];
                }

                int rclSize = Math.min(absent.length,
                        Math.max(2 * toAdd, (int) Math.ceil(absent.length * rclAddFraction)));
                int[] rcl = Ranking.select(score, tieBreak, absent, rclSize, false);

                List<Integer> shuffled = new ArrayList<>(rcl.length);
                for (int aisle : rcl) {
                    shuffled.add(aisle);
                }
                Collections.shuffle(shuffled, random);

                for (int i = 0; i < toAdd && i < shuffled.size(); i++) {
                    solution.addAisle(shuffled.get(i));
                    added++;
                }
            }
        }

        // A perturbation that changes nothing makes the outer loop spin: this
        // happens on single-aisle solutions whose demand is fully covered, where
        // there is nothing to drop (the last aisle must stay) and nothing to add.
        // Exchange the aisle instead, which keeps the denominator and explores a
        // different structure.
        if (removed == 0 && added == 0) {
            forceExchange(solution);
        }

        solution.randomizedGreedyRebuildOrders(random);

        return repairLowerBound(solution);
    }

    /**
     * Replaces one selected aisle by a promising unused one, keeping the number of
     * visited aisles unchanged.
     */
    private void forceExchange(Solution solution) {
        if (solution.aisles.isEmpty()) {
            return;
        }

        int[] absent = absentAisles(solution);
        if (absent.length == 0) {
            return;
        }

        double[] score = new double[absent.length];
        double[] tieBreak = new double[absent.length];
        for (int i = 0; i < absent.length; i++) {
            score[i] = coverage(absent[i], instanceDemand);
            tieBreak[i] = aisleStock[absent[i]];
        }

        int rclSize = Math.min(absent.length,
                Math.max(2, (int) Math.ceil(absent.length * rclAddFraction)));
        int[] rcl = Ranking.select(score, tieBreak, absent, rclSize, false);

        int incoming = rcl[random.nextInt(rcl.length)];
        int outgoing = solution.aisles.get(random.nextInt(solution.aisles.size()));

        solution.addAisle(incoming);
        solution.removeAisle(outgoing);
    }

    /**
     * Adds the aisles that unlock the most demand until the wave respects the
     * lower bound.
     *
     * @return true if the solution ended up feasible.
     */
    private boolean repairLowerBound(Solution solution) {
        int attempts = 0;

        while (!solution.isFeasible() && attempts < maxRepairAttempts) {
            for (int item = 0; item < problem.nItems; item++) {
                unmetBuffer[item] = Math.max(0, instanceDemand[item] - solution.stock[item]);
            }

            int[] absent = absentAisles(solution);
            if (absent.length == 0) {
                break;
            }

            double[] score = new double[absent.length];
            double[] tieBreak = new double[absent.length];
            for (int i = 0; i < absent.length; i++) {
                score[i] = coverage(absent[i], unmetBuffer);
                tieBreak[i] = aisleStock[absent[i]];
            }

            int[] best = Ranking.select(score, tieBreak, absent, 1, false);
            solution.addAisle(best[0]);
            solution.greedyRebuildOrders();

            attempts++;
            nShakeRepairs++;
        }

        return solution.isFeasible();
    }

    private int[] absentAisles(Solution solution) {
        int count = problem.nAisles - solution.aisles.size();
        if (count <= 0) {
            return new int[0];
        }

        int[] absent = new int[count];
        int idx = 0;
        for (int aisle = 0; aisle < problem.nAisles && idx < count; aisle++) {
            if (!solution.aislePresent[aisle]) {
                absent[idx++] = aisle;
            }
        }
        return absent;
    }

    /** Units of {@code demand} that the given aisle can supply. */
    private double coverage(int aisleIdx, int[] demand) {
        double val = 0.0;
        for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisleIdx).entrySet()) {
            int d = demand[e.getKey()];
            if (d > 0) {
                val += Math.min(e.getValue(), d);
            }
        }
        return val;
    }

    // endregion shaking

    private void logSummary(PrintStream output, long elapsedMillis) {
        if (output == null) {
            return;
        }

        double seconds = Math.max(elapsedMillis, 1) / 1000.0;
        output.printf(Locale.US,
                "ILS-VND summary: iters=%d (%.1f/s), vndCalls=%d, movesApplied=%d, shakes=%d, repairs=%d, "
                        + "failedShakes=%d, noImprovementIters=%d, stop=%s%n",
                nIters, nIters / seconds, nVndCalls, nMovesApplied, nShakes, nShakeRepairs,
                nShakeFailures, noImprovementIters, stopReason);
    }

    /**
     * Sets the stagnation limit: the search stops after this many consecutive outer
     * iterations without a new best solution. 0 (the default) disables it, leaving
     * the time limit as the only stopping criterion.
     */
    public void setMaxNoImprovementIters(long iters) {
        this.maxNoImprovementIters = Math.max(0, iters);
    }

    /** Returns the stagnation limit in outer iterations (0 when disabled). */
    public long getMaxNoImprovementIters() {
        return maxNoImprovementIters;
    }

    /** Returns why the outer loop ended: time_limit, max_iters or no_improvement. */
    public String getStopReason() {
        return stopReason;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "ILS-VND (maxLocalIters=%d, perturbationStrength=%.4f, acceptanceThreshold=%.4f, "
                        + "stochasticAttempts=%d, maxRepairAttempts=%d, rclRemoveFraction=%.4f, rclAddFraction=%.4f, "
                        + "maxNoImprovementIters=%d)",
                maxLocalIters, perturbationStrength, acceptanceThreshold,
                stochasticAttempts, maxRepairAttempts, rclRemoveFraction, rclAddFraction,
                maxNoImprovementIters);
    }
}
