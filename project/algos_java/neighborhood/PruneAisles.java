package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;
import util.AislePruning;

/**
 * Removes every redundant aisle, i.e. every aisle whose stock is not needed to
 * supply the orders currently selected.
 *
 * Since the objective is (units picked / aisles visited), dropping an aisle
 * without touching the order set keeps the numerator and shrinks the
 * denominator, so this move is a guaranteed improvement whenever at least one
 * redundant aisle exists. Random aisle removal almost never finds these aisles,
 * which is why the scan is done explicitly here.
 *
 * The order selection is deliberately NOT rebuilt: the current orders remain
 * supported by the reduced stock, whereas a rebuild from scratch could return a
 * different (possibly smaller) order set.
 */
public class PruneAisles extends Move {

    private Solution savedState;

    /** Reusable buffers to avoid per-call allocation. */
    private final int[] demand;
    private final int[] residualStock;

    public PruneAisles(Problem problem, Random random, String name) {
        super(problem, random, name);
        this.demand = new int[problem.nItems];
        this.residualStock = new int[problem.nItems];
    }

    @Override
    public double doMove(Solution solution) {
        super.doMove(solution);

        if (solution.aisles.size() <= 1 || solution.orders.isEmpty()) {
            return 0;
        }

        solution.computeOrderDemand(demand);
        System.arraycopy(solution.stock, 0, residualStock, 0, residualStock.length);

        // Shared with the constructive heuristic's post-processing pass.
        List<Integer> redundant = AislePruning.findRedundant(
                problem, solution.aisles, demand, residualStock, 1);

        if (redundant.isEmpty()) {
            return 0;
        }

        savedState = solution.clone();
        applied = true;

        for (int aisle : redundant) {
            solution.removeAisle(aisle);
        }

        double delta = solution.getObj() - initialCost;
        deltaObj = (delta > 0) ? 1 : (delta == 0) ? 0 : -1;
        return delta;
    }

    @Override
    public boolean hasMove(Solution solution) {
        return solution.aisles.size() > 1 && !solution.orders.isEmpty();
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
