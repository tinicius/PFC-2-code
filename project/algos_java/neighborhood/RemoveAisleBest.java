package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;
import util.Ranking;

/**
 * Removes the aisle that costs the least to give up.
 *
 * With obj = U/A, removing an aisle improves the solution if and only if the
 * units lost are below the current objective value U/A. Instead of picking an
 * aisle at random and hoping, candidates are ranked by how much currently
 * supported demand they are the sole provider of:
 *
 * deficiency(a) = sum_i max(0, demand_i - (stock_i - a_i))
 *
 * The cheapest candidates are then evaluated exactly (removal + deterministic
 * rebuild) and the first strict improvement is kept, which makes this a
 * first-improvement move over the most promising part of the neighborhood.
 */
public class RemoveAisleBest extends Move {

    /** Number of ranked candidates evaluated exactly per call. */
    private final int maxCandidates;

    private Solution savedState;

    /** Reusable buffer to avoid per-call allocation. */
    private final int[] demand;

    public RemoveAisleBest(Problem problem, Random random, String name) {
        this(problem, random, name, 3);
    }

    public RemoveAisleBest(Problem problem, Random random, String name, int maxCandidates) {
        super(problem, random, name);
        this.maxCandidates = Math.max(1, maxCandidates);
        this.demand = new int[problem.nItems];
    }

    @Override
    public double doMove(Solution solution) {
        super.doMove(solution);

        if (solution.aisles.size() <= 1) {
            return 0;
        }

        solution.computeOrderDemand(demand);

        // Rank present aisles by how much supported demand their removal breaks.
        int n = solution.aisles.size();
        int[] candidates = new int[n];
        double[] deficiency = new double[n];
        for (int i = 0; i < n; i++) {
            int aisle = solution.aisles.get(i);
            candidates[i] = aisle;
            double def = 0.0;
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisle).entrySet()) {
                int item = e.getKey();
                int shortfall = demand[item] - (solution.stock[item] - e.getValue());
                if (shortfall > 0) {
                    def += Math.min(shortfall, e.getValue());
                }
            }
            deficiency[i] = def;
        }

        int nCandidates = Math.min(maxCandidates, n);
        int[] ranked = Ranking.lowestFirst(deficiency, candidates, nCandidates);

        savedState = solution.clone();

        for (int aisle : ranked) {
            solution.removeAisle(aisle);
            solution.greedyRebuildOrders();

            double delta = solution.getObj() - initialCost;
            if (solution.isFeasible() && delta > 0) {
                applied = true;
                deltaObj = 1;
                return delta;
            }

            solution.restoreFrom(savedState);
        }

        // No candidate improves: leave the solution untouched.
        savedState = null;
        return 0;
    }

    @Override
    public boolean hasMove(Solution solution) {
        return solution.aisles.size() > 1;
    }

    @Override
    public boolean isDeterministic() {
        return true;
    }

    @Override
    public void accept() {
        savedState = null;
        super.accept();
    }

    @Override
    public void reject() {
        if (savedState != null) {
            currentSolution.restoreFrom(savedState);
            savedState = null;
        }
        super.reject();
    }
}
