package util;

/**
 * Partial ranking helpers.
 *
 * The search only ever needs the few best (or worst) scored candidates, so a
 * full sort — let alone a sort over a boxed Integer[] with a capturing
 * comparator — is wasteful. These helpers run a partial selection in O(n*k) with
 * no allocation beyond the returned array, and break ties deterministically.
 */
public final class Ranking {

    private Ranking() {
    }

    /**
     * Returns the {@code k} ids with the lowest primary score, ascending.
     */
    public static int[] lowestFirst(double[] primary, int[] ids, int k) {
        return select(primary, null, ids, k, true);
    }

    /**
     * Returns the {@code k} ids with the highest primary score, descending.
     */
    public static int[] highestFirst(double[] primary, int[] ids, int k) {
        return select(primary, null, ids, k, false);
    }

    /**
     * Selects the {@code k} best ids according to {@code primary}.
     *
     * @param primary        score per candidate (parallel to {@code ids}).
     * @param tieBreak       optional secondary score, always preferred higher;
     *                       may be null.
     * @param ids            candidate ids (this array is not modified).
     * @param k              how many candidates to return (clamped to the input
     *                       size).
     * @param ascending      true to prefer the lowest primary scores, false to
     *                       prefer the highest.
     * @return the selected ids, best first.
     */
    public static int[] select(double[] primary, double[] tieBreak, int[] ids, int k, boolean ascending) {
        int n = ids.length;
        int count = Math.max(0, Math.min(k, n));
        if (count == 0) {
            return new int[0];
        }

        int[] pos = new int[n];
        for (int i = 0; i < n; i++) {
            pos[i] = i;
        }

        for (int j = 0; j < count; j++) {
            int best = j;
            for (int i = j + 1; i < n; i++) {
                if (better(primary, tieBreak, ids, pos[i], pos[best], ascending)) {
                    best = i;
                }
            }
            int tmp = pos[j];
            pos[j] = pos[best];
            pos[best] = tmp;
        }

        int[] result = new int[count];
        for (int j = 0; j < count; j++) {
            result[j] = ids[pos[j]];
        }
        return result;
    }

    private static boolean better(double[] primary, double[] tieBreak, int[] ids,
            int candidate, int incumbent, boolean ascending) {
        double a = primary[candidate];
        double b = primary[incumbent];
        if (a != b) {
            return ascending ? a < b : a > b;
        }
        if (tieBreak != null && tieBreak[candidate] != tieBreak[incumbent]) {
            return tieBreak[candidate] > tieBreak[incumbent];
        }
        return ids[candidate] < ids[incumbent];
    }
}
