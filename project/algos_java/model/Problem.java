package model;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * // TODO: add description
 * This class represents an ...
 *
 * @author Tulio Toffolo
 */
public class Problem {

    public final int lb;
    public final int ub;

    public final int nItems;

    /***
     * Number of orders
     */
    public final int nOrders;

    /***
     * Number of aisles
     */
    public final int nAisles;

    public final List<HashMap<Integer, Integer>> orders;

    public final List<HashMap<Integer, Integer>> aisles;

    /**
     * Instantiates a new Problem from a file.
     *
     * @param instancePath the instance file path
     */
    public Problem(String instancePath) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(instancePath));

        String[] first = lines.get(0).trim().split("\\s+");

        nOrders = Integer.parseInt(first[0]);
        nItems = Integer.parseInt(first[1]);
        nAisles = Integer.parseInt(first[2]);

        orders = new ArrayList<>();

        for (int i = 0; i < nOrders; i++) {

            String[] tokens = lines.get(i + 1).trim().split("\\s+");

            int nOrderItems = Integer.parseInt(tokens[0]);

            HashMap<Integer, Integer> details = new HashMap<>();

            for (int k = 0; k < nOrderItems; k++) {
                int item = Integer.parseInt(tokens[2 * k + 1]);
                int quantity = Integer.parseInt(tokens[2 * k + 2]);

                details.put(item, quantity);
            }

            orders.add(details);
        }

        aisles = new ArrayList<>();

        for (int i = 0; i < nAisles; i++) {

            String[] tokens = lines.get(i + 1 + nOrders).trim().split("\\s+");

            int nAisleItems = Integer.parseInt(tokens[0]);

            HashMap<Integer, Integer> details = new HashMap<>();

            for (int k = 0; k < nAisleItems; k++) {
                int item = Integer.parseInt(tokens[2 * k + 1]);
                int quantity = Integer.parseInt(tokens[2 * k + 2]);

                details.put(item, quantity);
            }

            aisles.add(details);
        }

        String[] wave = lines.get(nOrders + nAisles + 1).trim().split("\\s+");

        lb = Integer.parseInt(wave[0]);
        ub = Integer.parseInt(wave[1]);
    }
}
