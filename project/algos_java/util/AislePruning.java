package util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import model.Problem;

/**
 * Detection of redundant aisles.
 *
 * An aisle is redundant when the stock of the remaining aisles still covers the
 * demand of every selected order. Dropping it keeps the units picked and reduces
 * the number of aisles visited, which strictly improves the objective
 * (units picked / aisles visited).
 *
 * The same test is needed by the constructive heuristic (as a post-processing
 * pass) and by the local search (as a neighborhood), so it lives here instead of
 * being duplicated.
 */
public final class AislePruning {

    private AislePruning() {
    }

    /**
     * Returns the aisles that can be dropped without leaving any demand uncovered.
     *
     * Candidates are tested in iteration order with an incremental stock update:
     * once an aisle is found to be indispensable it stays indispensable, because
     * removing further stock can only make coverage harder.
     *
     * @param problem       the problem reference.
     * @param aisles        the currently selected aisles, in the order to test.
     * @param demand        total demand per item of the currently selected orders.
     * @param stock         aggregated stock per item of {@code aisles}; mutated so
     *                      that on return it reflects the aisles that are kept.
     * @param minAislesKept lower limit on how many aisles must remain selected.
     * @return the redundant aisles, in the order they were dropped.
     */
    public static List<Integer> findRedundant(Problem problem, Iterable<Integer> aisles,
            int[] demand, int[] stock, int minAislesKept) {

        List<Integer> redundant = new ArrayList<>();

        int remaining = 0;
        for (@SuppressWarnings("unused") int aisle : aisles) {
            remaining++;
        }

        for (int aisle : aisles) {
            if (remaining <= minAislesKept) {
                break;
            }

            boolean droppable = true;
            for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisle).entrySet()) {
                if (stock[e.getKey()] - e.getValue() < demand[e.getKey()]) {
                    droppable = false;
                    break;
                }
            }

            if (droppable) {
                for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisle).entrySet()) {
                    stock[e.getKey()] -= e.getValue();
                }
                redundant.add(aisle);
                remaining--;
            }
        }

        return redundant;
    }
}
