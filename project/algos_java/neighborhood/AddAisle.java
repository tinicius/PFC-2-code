package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;

public class AddAisle extends Move {

    private int addedAisle = -1;
    private Solution savedState;

    public AddAisle(Problem problem, Random random, String name) {
        super(problem, random, name);
    }

    public double doMove(Solution solution) {
        super.doMove(solution);

        // Use boolean[] for O(1) membership check instead of List.contains()
        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < problem.nAisles; i++) {
            if (!solution.aislePresent[i]) {
                available.add(i);
            }
        }

        if (available.isEmpty()) {
            return 0;
        }

        addedAisle = available.get(random.nextInt(available.size()));
        savedState = solution.clone();

        // Incremental aisle addition updates stock via int[] array
        solution.addAisle(addedAisle);

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
        return solution.aisles.size() < problem.nAisles;
    }

    @Override
    public void accept() {
        savedState = null;
        addedAisle = -1;
        super.accept();
    }

    @Override
    public void reject() {
        if (savedState != null) {
            currentSolution.restoreFrom(savedState);
            savedState = null;
            addedAisle = -1;
        }
        super.reject();
    }
}
