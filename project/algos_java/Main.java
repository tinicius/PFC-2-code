import java.io.IOException;
import java.util.Random;

import constructive.AisleBased;
import constructive.OrderBased;
import heuristic.IlsVnd;
import model.Problem;
import model.Solution;
import neighborhood.AddAisleBest;
import neighborhood.AddOrder;
import neighborhood.PruneAisles;
import neighborhood.RemoveAisleBest;
import neighborhood.SwapAisle;
import neighborhood.SwapOrder;

public class Main {

    public static long startTimeMillis;

    public static String inFile = null;
    public static String outFile = null;
    public static long seed = 42;
    public static long timeLimit = 10000; // milliseconds
    public static long maxIters = 0; // 0 = unlimited, stop on the time limit only
    public static String algo = "ils_vnd";

    // ILS-VND parameters
    public static int maxLocalIters = 8000;
    public static double perturbationStrength = 0.30;
    public static double acceptanceThreshold = 0.01;

    /** -1 keeps the historical derivation from maxLocalIters. */
    public static int stochasticAttempts = -1;
    public static int maxRepairAttempts = 10;
    public static double rclRemoveFraction = 0.20;
    public static double rclAddFraction = 0.20;

    /** Stagnation limit in outer ILS-VND iterations; 0 = disabled (time limit only). */
    public static long maxNoImprovementIters = 0;

    public static void main(String[] args) throws IOException {
        if (!readArgs(args)) {
            System.err.println(
                    "Usage: java Main --input=<path> --output=<path> --time-limit=<secs> [--seed=<int>] [--algo=<name>] [--params=<json>]");
            System.exit(1);
        }

        Problem problem = new Problem(inFile);
        Random random = new Random(seed);

        startTimeMillis = System.currentTimeMillis();

        Solution solution = new Solution(problem);

        if ("ils_vnd".equals(algo)) {

            AisleBased constructor = new AisleBased();
            solution = constructor.solve(problem);

            int attempts = (stochasticAttempts > 0)
                    ? stochasticAttempts
                    : IlsVnd.defaultStochasticAttempts(maxLocalIters);

            IlsVnd solver = new IlsVnd(problem, random, maxLocalIters, perturbationStrength, acceptanceThreshold,
                    attempts, maxRepairAttempts, rclRemoveFraction, rclAddFraction);
            solver.setMaxNoImprovementIters(maxNoImprovementIters);

            // T1: shrink the number of aisles
            solver.addMove(new PruneAisles(problem, random, "PruneAisles"));
            solver.addMove(new RemoveAisleBest(problem, random, "RemoveAisleBest"));

            // T2: grow the units picked with the aisle set fixed
            solver.addMove(new SwapOrder(problem, random, "SwapOrder"));
            solver.addMove(new AddOrder(problem, random, "AddOrder"));

            // T3: add an aisle only when it pays more than the current ratio
            solver.addMove(new AddAisleBest(problem, random, "AddAisleBest"));

            // T4: structural exchange
            solver.addMove(new SwapAisle(problem, random, "SwapAisle"));

            System.out.println("Running " + solver.toString());
            solution = solver.run(solution, timeLimit, maxIters, System.out);
        } else if ("aisle_based".equals(algo)) {
            AisleBased solver = new AisleBased();
            solution = solver.solve(problem);
        } else if ("order_based".equals(algo)) {
            OrderBased solver = new OrderBased(random);
            solution = solver.solve(problem);
        } else {
            System.err.println("Unknown algorithm: " + algo);
            System.exit(1);
        }

        double execTime = (System.currentTimeMillis() - startTimeMillis) / 1000.0;

        solution.write(outFile, execTime);

        // Internal self-check. The authoritative gate is the external Python
        // validator; this only surfaces the failure early in the run log.
        if (!solution.validate(System.err)) {
            System.err.println("WARNING: internal self-check rejected the solution written to "
                    + outFile);
        }

        System.out.printf("%f\n", solution.getObj());
    }

    /**
     * Reads the input arguments.
     *
     * @param args the input arguments
     * @return true if required arguments are present
     */
    public static boolean readArgs(String args[]) {
        for (String arg : args) {
            if (arg.startsWith("--input=")) {
                inFile = arg.substring("--input=".length());
            } else if (arg.startsWith("--output=")) {
                outFile = arg.substring("--output=".length());
            } else if (arg.startsWith("--time-limit=")) {
                timeLimit = Long.parseLong(arg.substring("--time-limit=".length())) * 1000;
            } else if (arg.startsWith("--seed=")) {
                seed = Long.parseLong(arg.substring("--seed=".length()));
            } else if (arg.startsWith("--algo=")) {
                algo = arg.substring("--algo=".length());
            } else if (arg.startsWith("--params=")) {
                parseParams(arg.substring("--params=".length()));
            }
        }

        return inFile != null && outFile != null;
    }

    /**
     * Parses a flat JSON object for algorithm params.
     * Expected format (ils_vnd):
     * {"maxLocalIters":2500,"perturbationStrength":0.05,"acceptanceThreshold":0.01,
     *  "stochasticAttempts":600,"maxRepairAttempts":8,"rclRemoveFraction":0.4,"rclAddFraction":0.4,
     *  "maxIters":0,"maxNoImprovementIters":10000}
     */
    private static void parseParams(String json) {
        if (json == null || json.isEmpty())
            return;
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}"))
            return;
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty())
            return;

        String[] pairs = json.split(",");
        for (String pair : pairs) {
            pair = pair.trim();
            int colonIdx = pair.indexOf(':');
            if (colonIdx < 0)
                continue;
            String key = pair.substring(0, colonIdx).trim().replaceAll("^\"|\"$", "");
            String value = pair.substring(colonIdx + 1).trim().replaceAll("^\"|\"$", "");

            switch (key) {
                case "maxIters":
                    maxIters = Long.parseLong(value);
                    break;
                case "maxNoImprovementIters":
                    maxNoImprovementIters = Long.parseLong(value);
                    break;
                case "maxLocalIters":
                    maxLocalIters = Integer.parseInt(value);
                    break;
                case "perturbationStrength":
                    perturbationStrength = Double.parseDouble(value);
                    break;
                case "acceptanceThreshold":
                    acceptanceThreshold = Double.parseDouble(value);
                    break;
                case "stochasticAttempts":
                    stochasticAttempts = Integer.parseInt(value);
                    break;
                case "maxRepairAttempts":
                    maxRepairAttempts = Integer.parseInt(value);
                    break;
                case "rclRemoveFraction":
                    rclRemoveFraction = Double.parseDouble(value);
                    break;
                case "rclAddFraction":
                    rclAddFraction = Double.parseDouble(value);
                    break;
                default:
                    System.err.println("Warning: ignoring unknown parameter '" + key + "'");
                    break;
            }
        }
    }
}