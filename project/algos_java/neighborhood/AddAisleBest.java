package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;
import util.Ranking;

/**
 * Adds the unused aisle that unlocks the most demand.
 *
 * With obj = U/A, adding an aisle pays off if and only if the extra units it
 * unlocks exceed the current objective value U/A, so picking the aisle at random
 * mostly produces rejections. Candidates are scored by how much of the demand
 * that currently blocks unselected orders they can cover:
 *
 * lack_i   = max(0, demand of unselected orders_i - free stock_i)
 * score(a) = sum_i min(a_i, lack_i)
 *
 * The best candidates are then evaluated exactly (addition + deterministic
 * rebuild) and the first strict improvement is kept.
 */
public class AddAisleBest extends Move {

    /** Number of ranked candidates evaluated exactly per call. */
    private final int maxCandidates;

    private Solution savedState;

    /** Reusable buffers to avoid per-call allocation. */
    private final int[] selectedDemand;
    private final int[] lack;

    public AddAisleBest(Problem problem, Random random, String name) {
        this(problem, random, name, 3);
    }

    public AddAisleBest(Problem problem, Random random, String name, int maxCandidates) {
        super(problem, random, name);
        this.maxCandidates = Math.max(1, maxCandidates);
        this.selectedDemand = new int[problem.nItems];
        this.lack = new int[problem.nItems];
    }

    @Override
    public double doMove(Solution solution) {
        super.doMove(solution);

        // Collect absent aisles.
        int nAbsent = problem.nAisles - solution.aisles.size();
        if (nAbsent <= 0) {
            return 0;
        }

        solution.computeOrderDemand(selectedDemand);

        // Demand of the orders that are currently not selected, minus free stock.
        Arrays.fill(lack, 0);
        boolean[] selected = new boolean[problem.nOrders];
        for (int orderIdx : solution.orders) {
            selected[orderIdx] = true;
        }
        for (int orderIdx = 0; orderIdx < problem.nOrders; orderIdx++) {
            if (selected[orderIdx]) {
                continue;
            }
            for (Map.Entry<Integer, Integer> e : problem.orders.get(orderIdx).entrySet()) {
                lack[e.getKey()] += e.getValue();
            }
        }
        for (int item = 0; item < problem.nItems; item++) {
            int freeStock = solution.stock[item] - selectedDemand[item];
            lack[item] = Math.max(0, lack[item] - Math.max(0, freeStock));
        }

        int[] candidates = new int[nAbsent];
        double[] score = new double[nAbsent];
        double[] totalStock = new double[nAbsent];
        int idx = 0;
        for (int aisle = 0; aisle < problem.nAisles && idx < nAbsent; aisle++) {
            if (solution.aislePresent[aisle]) {
                continue;
            }
            double cover = 0.0;
            double total = 0.0;
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisle).entrySet()) {
                total += e.getValue();
                int missing = lack[e.getKey()];
                if (missing > 0) {
                    cover += Math.min(e.getValue(), missing);
                }
            }
            candidates[idx] = aisle;
            score[idx] = cover;
            totalStock[idx] = total;
            idx++;
        }

        int nCandidates = Math.min(maxCandidates, nAbsent);
        int[] ranked = Ranking.select(score, totalStock, candidates, nCandidates, false);

        savedState = solution.clone();

        for (int aisle : ranked) {
            solution.addAisle(aisle);
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
        return solution.aisles.size() < problem.nAisles;
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
