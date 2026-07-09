package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;

public class RemoveAisle extends Move {

    private int removedAisle = -1;
    private Solution savedState;

    public RemoveAisle(Problem problem, Random random, String name) {
        super(problem, random, name);
    }

    public double doMove(Solution solution) {
        super.doMove(solution);

        if (solution.aisles.isEmpty()) {
            return 0;
        }

        removedAisle = solution.aisles.get(random.nextInt(solution.aisles.size()));
        savedState = solution.clone();

        // Incremental aisle removal updates stock via int[] array
        solution.removeAisle(removedAisle);

        // Greedy rebuild uses pre-allocated work buffer and cached orderUnits
        solution.randomizedGreedyRebuildOrders(random);

        if (solution.getTotalItemsPicked() < problem.lb) {
            deltaObj = -1;
            return -(Math.abs(initialCost) + 1);
        }

        double delta = solution.getObj() - initialCost;
        deltaObj = (delta > 0) ? 1 : (delta == 0) ? 0 : -1;
        return delta;
    }

    @Override
    public boolean hasMove(Solution solution) {
        return solution.aisles.size() > 0;
    }

    @Override
    public void accept() {
        savedState = null;
        removedAisle = -1;
        super.accept();
    }

    @Override
    public void reject() {
        if (savedState != null) {
            currentSolution.restoreFrom(savedState);
            savedState = null;
            removedAisle = -1;
        }
        super.reject();
    }
}
