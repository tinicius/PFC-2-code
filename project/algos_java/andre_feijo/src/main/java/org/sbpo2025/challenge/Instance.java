package org.sbpo2025.challenge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;


public class Instance {
	
	BufferedReader reader;
	BufferedWriter writer;
	
    public List<Map<Integer, Integer>> orders;
    public List<Map<Integer, Integer>> aisles;
    public List<Map<Integer, Integer>> itemsPerOrders;
    public List<Map<Integer, Integer>> itemsPerAisles;
    public int LB, UB, n, totalUnits;

	// Optimizations.
	BitSet wontBuild, wontUse;
	ArrayList<Integer> Q, D;
	Set<Pair<Integer, Integer>> mutexOrders;
	BitSet easy, dominated;
	
	int medianItems;
    
    public Instance(String file) {
        readInput(file);
    }
    
    private void readInput(String inputFilePath) {
        try {
            this.reader = new BufferedReader(new FileReader(inputFilePath));
            
            // Read first line.
            String line = this.reader.readLine();
            String[] firstLine = line.split(" ");
            int nOrders = Integer.parseInt(firstLine[0]);
            int nItems = Integer.parseInt(firstLine[1]);
            int nAisles = Integer.parseInt(firstLine[2]);

            // Initialize orders and aisles arrays
            orders = new ArrayList<>(nOrders);
            aisles = new ArrayList<>(nAisles);
            itemsPerOrders = new ArrayList<>(nItems);
            itemsPerAisles = new ArrayList<>(nItems);
            this.n = nItems;

            // Read orders
            readItemQuantityPairs(orders, nOrders);

            // Read aisles
            readItemQuantityPairs(aisles, nAisles);
            transposeOrdersToItems();
            transposeAislesToItems();

            // Read wave size bounds
            line = this.reader.readLine();
            String[] bounds = line.split(" ");
            LB = Integer.parseInt(bounds[0]);
            UB = Integer.parseInt(bounds[1]);

            this.reader.close();
            
            
            // Check for optimizations
    		Q = new ArrayList<Integer>(n);
    		D = new ArrayList<Integer>(n);

    		mutexOrders = new HashSet<Pair<Integer, Integer>>();
    		
    		wontBuild = new BitSet(orders.size()); wontBuild.clear();
    		wontUse   = new BitSet(n);             wontUse.clear();
    		easy = new BitSet(n);                  easy.clear();
            
            getInstanceInfo();
            
        } catch (IOException e) {
            System.err.println("Error reading input from " + inputFilePath);
            e.printStackTrace();
        }
    }

    private void readItemQuantityPairs(List<Map<Integer, Integer>> orders, int nLines) throws IOException {
        String line;
        for (int orderIndex = 0; orderIndex < nLines; orderIndex++) {
            line = this.reader.readLine();
            String[] orderLine = line.split(" ");
            int nOrderItems = Integer.parseInt(orderLine[0]);
            Map<Integer, Integer> orderMap = new HashMap<>();
            for (int k = 0; k < nOrderItems; k++) {
                int itemIndex = Integer.parseInt(orderLine[2 * k + 1]);
                int itemQuantity = Integer.parseInt(orderLine[2 * k + 2]);
                orderMap.put(itemIndex, itemQuantity);
            }
            orders.add(orderMap);
        }
    }
    
    public void writeOutput(ChallengeSolution challengeSolution, String outputFilePath, double execTime) {
        if (challengeSolution == null) {
            System.err.println("Solution not found");
            return;
        }
        try {
            this.writer = new BufferedWriter(new FileWriter(outputFilePath));
            var orders = challengeSolution.orders();
            var aisles = challengeSolution.aisles();

            StringBuilder sb = new StringBuilder();
            sb.append("{\"selected_orders\":[");
            boolean first = true;
            for (int order : orders) {
                if (!first) sb.append(",");
                sb.append(order);
                first = false;
            }
            sb.append("],\"visited_aisles\":[");
            first = true;
            for (int aisle : aisles) {
                if (!first) sb.append(",");
                sb.append(aisle);
                first = false;
            }
            sb.append("],\"objective\":").append(selfReportedObjective(orders, aisles));
            sb.append(",\"exec_time\":").append(execTime).append("}");

            this.writer.write(sb.toString());

            this.writer.close();
            System.out.println("Output written to " + outputFilePath);

        } catch (IOException e) {
            System.err.println("Error writing output to " + outputFilePath);
            e.printStackTrace();
        }
    }

    /**
     * Objective value as computed from this solver's own instance data. It is
     * written to the output file so that the external validator can recompute
     * it independently and flag any disagreement.
     */
    private double selfReportedObjective(java.util.Set<Integer> selectedOrders,
                                         java.util.Set<Integer> visitedAisles) {
        if (visitedAisles.isEmpty())
            return 0.0;
        long units = 0;
        for (int o : selectedOrders)
            for (int q : orders.get(o).values())
                units += q;
        return (double) units / visitedAisles.size();
    }
    
    protected void transposeAislesToItems() {    	
    	for(int i = 0; i < n; i++)
    		itemsPerAisles.add(i, new HashMap<Integer, Integer>());
    	
    	for(int a = 0; a < aisles.size(); a++) 
    		for(int i : aisles.get(a).keySet())
    			itemsPerAisles.get(i).put(a, aisles.get(a).get(i));
    }
    
    protected void transposeOrdersToItems() {    	
    	for(int i = 0; i < n; i++)
    		itemsPerOrders.add(i, new HashMap<Integer, Integer>());
    	
    	for(int o = 0; o < orders.size(); o++) 
    		for(int i : orders.get(o).keySet())
    			itemsPerOrders.get(i).put(o, orders.get(o).get(i));
    }
    
    protected void getInstanceInfo() {		
		// Calculate demand and quantity of a specific item.
		for(int i = 0; i < n; i++) {			
			int Di = 0;
			for(int o : itemsPerOrders.get(i).keySet())
				Di += orders.get(o).get(i);
			D.add(Di);
			
			int Qi = 0;
			for(int a : itemsPerAisles.get(i).keySet())
				Qi += aisles.get(a).get(i);
			Q.add(Qi);
		}
		
		List<Integer> Qa = new ArrayList<Integer>();
		for(int a = 0; a < aisles.size(); a++) {
			Qa.add(0);
			for(int i : aisles.get(a).keySet())
				Qa.set(a, Qa.get(a) + aisles.get(a).get(i));
		}
		Collections.sort(Qa);
		
		if(Qa.size() % 2 != 0)
			medianItems = Qa.get(Qa.size()/2);
		else medianItems = (Qa.get(Qa.size()/2-1) + Qa.get(Qa.size()/2))/2;

		// Check for orders that won't be built for sure.
		for(int o = 0; o < orders.size(); o++) {			
			for(int i : orders.get(o).keySet())
				if(Q.get(i) < orders.get(o).get(i)) {
					wontBuild.set(o);
					break;
				}
		}
		
		// If an item is only used in orders that won't be built, then
		// we'll won't use it.
		for(int i = 0; i < n; i++) {						
			boolean wont = true;
			Set<Integer> aux = new HashSet<Integer>();
			for(int o : itemsPerOrders.get(i).keySet()) {
				if(!wontBuild.get(o)) {
					wont = false;
				} else {
					D.set(i, Math.max(0, D.get(i) - orders.get(o).get(i)));
					aux.add(o);
				}
			}
			for(int o : aux)
				itemsPerOrders.get(i).remove(o);
			if(wont) wontUse.set(i);
		}
		
		// Coefficient optimizations.
		for(int i = 0; i < n; i++) {
			int totalI = 0;
			for(int o : itemsPerOrders.get(i).keySet()) {
				if(wontBuild.get(o)) continue;
				totalI += orders.get(o).get(i);
			}
			
			for(int a : itemsPerAisles.get(i).keySet())
				if(totalI < aisles.get(a).get(i)) {
					Q.set(i, Q.get(i) - (aisles.get(a).get(i) - totalI));
					aisles.get(a).put(i, totalI);
					itemsPerAisles.get(i).put(a, totalI);
				}
		}
		
		MaxSubsetSum maxSubSetSum = new MaxSubsetSum();
		for(int i = 0; i < n; i++) {
			if(wontUse.get(i) || itemsPerAisles.get(i).keySet().size() > 1)
				continue;
			
			int a = itemsPerAisles.get(i).keySet().iterator().next();
			int[] ds = new int[itemsPerOrders.get(i).size()];
			
			// Checking if the quantity of these items is correct
			assert aisles.get(a).get(i) == Q.get(i);
			
			int j = 0;
			for(int o : itemsPerOrders.get(i).keySet())
				if(!wontBuild.get(o)) {
					ds[j] = itemsPerOrders.get(i).get(o);
					j++;
				}
			
			int totalI = maxSubSetSum.solve(ds, j, aisles.get(a).get(i));
			aisles.get(a).put(i, totalI);
			itemsPerAisles.get(i).put(a, totalI);
		}
		
		// Items that its demand is covered by getting ANY aisles that has it.
		for(int i = 0; i < n; i++) {
			boolean test = true;
			for(int a : itemsPerAisles.get(i).keySet())
				if(aisles.get(a).get(i) < D.get(i))
					test = false;
			if(test) easy.set(i);
		}
		
		// Remove (Qi - Di) unique orders when Qi < Di.
		totalUnits = 0;
		for(int i = 0; i < n; i++) {
			int sumUnits = 0;
			for(int o : itemsPerOrders.get(i).keySet()) {
				if(orders.get(o).get(i) == 1 && orders.get(o).size() == 1) {
					sumUnits++; totalUnits++;
					if(sumUnits > Q.get(i))
						wontBuild.set(o);
				}
			}
		}

		// Check for mutual exclusive orders.
		for(int o1 = 1; o1 < orders.size(); o1++) {
			if(wontBuild.get(o1)) continue;

			for(int i : orders.get(o1).keySet()) {
				if(wontUse.get(i)) continue;
				
				for(int o2 : itemsPerOrders.get(i).keySet()) 
					if(!wontBuild.get(o2) && o2 < o1 && (orders.get(o1).get(i) + orders.get(o2).get(i) > Q.get(i)))
						mutexOrders.add(Pair.of(o1, o2));
			}
		}
		
		List<List<Integer>> domination = new ArrayList<List<Integer>>(aisles.size());
		for(int a = 0; a < aisles.size(); a++)
			domination.add(new ArrayList<Integer>());
		
		dominated = new BitSet(aisles.size());
		dominated.clear();
		
		for(int a = 0; a < aisles.size(); a++) {
			
			Set<Integer> cover = new HashSet<Integer>();
			for(int i : aisles.get(a).keySet())
				if(dominates(a, i))
					cover.add(i);
			
			for(int b = 0; b < aisles.size(); b++) {
				if(a == b) continue;
				boolean T = true;
				
				for(int ib : aisles.get(b).keySet()) {
					if(!cover.contains(ib)) {
						T = false;
						break;
					}
				}
				
				if(T) {
					domination.get(b).add(a);
					dominated.set(b);
				}
			}
			
		}
		
		for(int a = 0; a < aisles.size(); a++)
			for(int b : domination.get(a)) {
				if(domination.get(b).size() == 1 && domination.get(b).get(0) == a)
					dominated.clear(b);
			}
		
		domination = null;
    }
    
    private boolean dominates(int a, int i) {
    	return aisles.get(a).get(i) >= D.get(i);
    }
    
    public void loose() {
    	reader = null;
    	
        itemsPerOrders = null;
        itemsPerAisles = null;

    	wontBuild = null;
    	wontUse = null;
    	
    	Q = null;
    	D = null;
    	
    	mutexOrders = null;
    	easy = null;
    	
    	System.gc();
    }
}
