package org.sbpo2025.challenge;


import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.Pair;

import ilog.concert.*;
import ilog.cplex.*;
import ilog.cplex.IloCplex.Status;


public class BSearch extends Approach {
	
	// CPLEX model.
	ItModel ascendingModel;
	ItModel decendingModel;
	IloCplex.Aborter decendingAborter;
	IloCplex.Aborter ascendingAborter;
	
	// Optimiaztion routines.
	Thread ascendingThread = null;
	Thread decendingThread = null;
	
	// Solutions
	ChallengeSolution ascendingSolution = null;
	ChallengeSolution decendingSolution = null;
	
	double ascendingIncumbent = 0;
	double decendingIncumbent = 0;
	
	// Solver informations.
	boolean ascendingOptimal = false;
	boolean ascendingTimeOut = false;
	boolean decendingOptimal = false;
	boolean decendingTimeOut = false;
	boolean optimal;
	
	int MAX_AISLES;
	int TOTAL_AISLES;
	
	// Threads informations.
	int ascendingLastIt = 0;                    // <- Last iteration entered.
	int decendingLastIt = Integer.MAX_VALUE;    // <--|
	
	int decendingLastNotAborted;                // <- Last iteration fully optimized.
	int ascendingLastNotAborted;                // <--|
	
	// Upper and lower bounds for items.
	int upperBoundItems;
	int lowerBoundItems;
	int ascendingLowerBoundItems;
	int decendingLowerBoundItems;
	double alfa = 1.0;
	
	// Solutions found during process.
	public List<Pair<IloCplex.Status, BitSet>> itsols;
	public Set<Integer> feasibles;
	
	// Binary Search.
	Pair<Integer, Integer> decendingRange;
	BitSet avoid = new BitSet();            // Already executed iterations.
	int NO_UB_TIMEOUT = 30;                 // If no solution was found within this time, then we assume we'll not find UB.
	double tol;                             // Tolerance for UB.
	boolean run_bs = true;                  // 1 if the DR should run binary search; 0, otherwise.     
	

	public BSearch(Instance inst, StopWatch stopWatch, long timeLimit, double tolerance) {
		super(inst, stopWatch, timeLimit);
		
		ascendingModel = new ItModel(inst, (int) Math.ceil(Runtime.getRuntime().availableProcessors()/2));
		decendingModel = new ItModel(inst, (int) Math.floor(Runtime.getRuntime().availableProcessors()/2));
		
		decendingAborter = new IloCplex.Aborter();
		ascendingAborter = new IloCplex.Aborter();
		
		ascendingModel.build();
		decendingModel.build();
		
		ascendingLowerBoundItems = inst.LB;
		decendingLowerBoundItems = inst.LB;
		upperBoundItems = inst.UB;
		avoid.clear();
		
		TOTAL_AISLES = inst.aisles.size() - inst.dominated.cardinality();
		MAX_AISLES   = TOTAL_AISLES;
		decendingRange = Pair.of(1, MAX_AISLES);
		
		ascendingIncumbent = inst.LB/TOTAL_AISLES;
		decendingIncumbent = inst.LB/TOTAL_AISLES;

		tol = tolerance;
		
		// Found solutions log.
		itsols = new ArrayList<Pair<IloCplex.Status, BitSet>>();
		for(int a = 0; a < inst.aisles.size(); a++) {
			BitSet b = new BitSet(); b.clear();
			itsols.add(Pair.of(Status.Unknown, b));
		}
		
		feasibles = new HashSet<Integer>();
	}

	
	public ChallengeSolution optimize() {
		try {	
			print_header();
			inst.loose();
			
			// Build and start both threads.
			ascendingThread = getAscendingThread();
			decendingThread = getDecendingThread();
			decendingModel.model.use(decendingAborter);
			ascendingModel.model.use(ascendingAborter);
			
			ascendingThread.start();
			decendingThread.start();
			
			// Wait for both threads.
			try {
				ascendingThread.join();
				decendingThread.join();
			} catch(InterruptedException e) {
				e.printStackTrace();
			}

			
			// Get the best thread solution.
			if(ascendingIncumbent > decendingIncumbent) {
				solution = ascendingSolution;
				objVal   = ascendingIncumbent;
			} else {
				solution = decendingSolution;
				objVal   = decendingIncumbent;
			}
			
			// Check if any thread found  a optimal solution.
			optimal = ascendingOptimal || decendingOptimal;
			lowerBoundItems = ascendingLowerBoundItems;
			
			logln("");
			logln("Ascending Thread finished at: " + ascendingLastIt);
			logln("Decending Thread finished at: " + decendingLastNotAborted);
			logln("Solution found: " + objVal);
			logln("Proved optimal? " + optimal + "\n");
			
		} catch(IloException e) {
			e.printStackTrace();
		}
		
		return solution;
	}

	private Thread getDecendingThread() {
		return new Thread() {
			@Override
			public void run() {	
				try {
					
					// ===== FIRST STEP: ITERATIVE DESCENDENT =====
					int h = MAX_AISLES/2, lastH = MAX_AISLES;
					
					while(run_bs && decendingRange.getLeft() <= decendingRange.getRight()) {
						if(getRemainingTime(stopWatch) <= 5) {
							logln("Time Limit reached (DE).");
							decendingTimeOut = true;
							break;
						}
						
						h = decendingRange.getLeft() + (decendingRange.getRight() - decendingRange.getLeft())/2;
						decendingLastIt = h;
						
						if(h == lastH) break;
						lastH = h;
						
						// Set parameters for running the model for h aisles..
						decendingModel.setTimeLimit(Math.min(NO_UB_TIMEOUT, getRemainingTime(stopWatch)));
						
						// Optimizes model for h aisles.
						h = Math.min(h, MAX_AISLES);
						decendingModel.setSumY(h);
						
						// Calculate a improvement LB.
						double bestIncumbent = Math.max(ascendingIncumbent, decendingIncumbent);
						decendingLowerBoundItems = Math.max(inst.LB, (int) (alfa * Math.floor(bestIncumbent * h + 1)));	
						decendingModel.setLB(decendingLowerBoundItems);
						decendingModel.setUB(upperBoundItems);
						
						// Optimizes for h aisles.
						if(!ascendingOptimal && (h >= ascendingLastIt)) {
							decendingAborter.clear();
							decendingModel.solve();
						}
						else break;
						
						
						// If a solution was found.
						if(decendingModel.getStatus() == IloCplex.Status.Optimal || decendingModel.getStatus() == IloCplex.Status.Feasible) {
							// Update range.
							if(decendingModel.getObjValue() >= tol*inst.UB)  {
								decendingRange = Pair.of(decendingRange.getLeft(), h);
								decendingLastNotAborted = h;
							}
							else 
								decendingRange = Pair.of(h, decendingRange.getRight());
							
							// If a better solution was found.
							if(decendingModel.getObjValue()/h > decendingIncumbent) {
								decendingIncumbent = decendingModel.getObjValue()/h;
								decendingSolution = decendingModel.saveSolution();
								decendingLastNotAborted = (int) Math.min(decendingLastNotAborted, Math.floor(inst.UB/decendingIncumbent));
								decendingRange = Pair.of(decendingRange.getLeft(), decendingLastNotAborted);
							}
						} 
						//else 
							//decendingRange = Pair.of(h, decendingRange.getRight());
						
						// Saving solution logs.
						if(decendingModel.getStatus() == IloCplex.Status.Feasible || decendingModel.getStatus() == IloCplex.Status.Optimal) {
							itsols.set(h, Pair.of(decendingModel.getStatus(), getAisles(decendingModel)));
							feasibles.add(h);
						} else 
							itsols.set(h, Pair.of(decendingModel.getStatus(), itsols.get(h).getRight()));
						
						// If a iteration was fully optimized.
						if(decendingModel.getStatus() == IloCplex.Status.Infeasible || decendingModel.getStatus() == IloCplex.Status.Optimal)
							avoid.set(h);

						printLine(h, ascendingLastIt, decendingModel, decendingIncumbent, "DE");
					}
					System.out.println("BSearch finished");
				
					
					// ===== SECOND STEP: ITERATIVE DESCENDENT =====
					h = decendingLastNotAborted;
					
					for(; h > 0; h-=1) {
						if(getRemainingTime(stopWatch) <= 5) {
							logln("Time Limit reached (DE).");
							decendingTimeOut = true;
							break;
						}
	
						decendingLastIt = h;
						
						// Set parameters for running the model for h aisles..
						decendingModel.setTimeLimit(getRemainingTime(stopWatch));
						
						// Optimizes model for "num_aisles" aisles.
						h = Math.min(h, MAX_AISLES);
						if(avoid.get(h)) {
							decendingLastNotAborted = h;
							continue;
						}
						decendingModel.setSumY(h);
						
						// Update lower bound.
						double bestIncumbent = Math.max(ascendingIncumbent, decendingIncumbent);
						decendingLowerBoundItems = Math.max(inst.LB, (int) (alfa * Math.floor(bestIncumbent * h + 1)));		
						decendingModel.setLB(decendingLowerBoundItems);
						decendingModel.setUB(upperBoundItems);
						
						// Optimizes for h aisles.
						if(!ascendingOptimal && (h >= ascendingLastIt)) {
							decendingAborter.clear();
							decendingModel.solve();
						}
						else break;
						
						// If a better was found.
						if(decendingModel.getStatus() == IloCplex.Status.Optimal || decendingModel.getStatus() == IloCplex.Status.Feasible) {
							if(decendingModel.getObjValue()/h > decendingIncumbent) {		
								decendingIncumbent = decendingModel.getObjValue()/h;
								decendingSolution = decendingModel.saveSolution();
							}
							
							if(decendingModel.getStatus() == IloCplex.Status.Optimal)
								upperBoundItems = (int) decendingModel.getObjValue();
	
						} else if(decendingModel.getStatus() == IloCplex.Status.Infeasible)
							upperBoundItems = ((int) decendingModel.getLB()) - 1;
						
						// Saving solution logs.
						if(decendingModel.getStatus() == IloCplex.Status.Feasible || decendingModel.getStatus() == IloCplex.Status.Optimal) {
							itsols.set(h, Pair.of(decendingModel.getStatus(), getAisles(decendingModel)));
							feasibles.add(h);
						} else 
							itsols.set(h, Pair.of(decendingModel.getStatus(), itsols.get(h).getRight()));
						
						// If a iteration was fully optimized.
						if(decendingModel.getStatus() == IloCplex.Status.Infeasible || decendingModel.getStatus() == IloCplex.Status.Optimal)
							decendingLastNotAborted = h;
	
						printLine(h, ascendingLastIt, decendingModel, decendingIncumbent, "DE");
					}
					
					decendingOptimal = !decendingTimeOut;
					ascendingAborter.abort();
					
				} catch(IloException e) {
					e.printStackTrace();
				}
			}
		};
	}

	
	private Thread getAscendingThread() {
		return new Thread() {
			@Override
			public void run() {	
				try {
					for(int h = 1; h <= MAX_AISLES; h++) {	
						if(getRemainingTime(stopWatch) <= 5) {
							logln("Time Limit reached (AC).");
							ascendingTimeOut = true;
							optimal = false;
							break;
						}
						
						ascendingLastIt = h;
						if(avoid.get(h)) {
							ascendingLastNotAborted = h;
							continue;
						}
						
						// Set parameters for running the model for h aisles..
						ascendingModel.setTimeLimit(getRemainingTime(stopWatch));
						ascendingModel.setSumY(h);
						
						// Update lower bound.
						double bestIncumbent = Math.max(ascendingIncumbent, decendingIncumbent);
						ascendingLowerBoundItems = Math.max(inst.LB, (int) (alfa * Math.floor(bestIncumbent * h + 1)));		
						ascendingModel.setLB(ascendingLowerBoundItems);
						ascendingModel.setUB(upperBoundItems);
						
						// Optimizes model for h aisles.
						if(!decendingOptimal && (h <= decendingLastIt))
							ascendingModel.solve();
						else break;
						
						// If a solution was found.
						if(ascendingModel.getStatus() == IloCplex.Status.Feasible || ascendingModel.getStatus() == IloCplex.Status.Optimal) { 
							if(ascendingModel.getObjValue()/h > ascendingIncumbent) {						
								ascendingIncumbent = ascendingModel.getObjValue()/h;
								ascendingSolution = ascendingModel.saveSolution();
							}
							
							// Update maximum of aisles and abort descending routine.
							MAX_AISLES = Math.min(MAX_AISLES, (int) Math.floor(upperBoundItems / ascendingIncumbent));
							if(MAX_AISLES < decendingLastIt || run_bs) {
								run_bs = false;
								decendingAborter.abort();
							}
						} 
						
						// Saving solution logs.
						if(ascendingModel.getStatus() == IloCplex.Status.Feasible || ascendingModel.getStatus() == IloCplex.Status.Optimal) {
							itsols.set(h, Pair.of(ascendingModel.getStatus(), getAisles(ascendingModel)));
							feasibles.add(h);
						} else 
							itsols.set(h, Pair.of(ascendingModel.getStatus(), itsols.get(h).getRight()));
						
						// If a iteration was fully optimized.
						if(ascendingModel.getStatus() == IloCplex.Status.Infeasible || ascendingModel.getStatus() == IloCplex.Status.Optimal)
							ascendingLastNotAborted = h;

						printLine(ascendingLastIt, Math.min(MAX_AISLES, decendingLastIt), ascendingModel, ascendingIncumbent, "AC");
					}
				} catch(IloException e) {
					e.printStackTrace();
				}
				
				ascendingOptimal = !ascendingTimeOut;
				decendingAborter.abort();
			}
		};
	}
	
	// Sets the percentage of improvement lower bound the DR will take as LB.
	public void setAlfa(double a) {
		alfa = a;
	}
	
	// Returns a bit set containing the used aisles.
	protected BitSet getAisles(ItModel model) throws IloException {
		BitSet aisles = new BitSet();
		aisles.clear();
		
		for(int a = 0; a < inst.aisles.size(); a++) {
			if(inst.dominated.get(a)) continue;
			if(model.getValue(model.y[a]) >= .5)
				aisles.set(a);
		}
		
		return aisles;
	}
	
	// === DEBUGGING AND LOGGING METHODS ===
	private void print_header() throws IloException {
		logln("First step: Binary Search to reduce search space");
		logln("Thread count: CPLEX using up to " + (ascendingModel.model.getParam(IloCplex.Param.Threads) + decendingModel.model.getParam(IloCplex.Param.Threads)) + " threads");
		logln("Variable types: 1 continuous; " + (ascendingModel.y.length + ascendingModel.p.length) + 
				" integer (" + (ascendingModel.y.length + ascendingModel.p.length) + " binaries)");
		logln("");
		
		logln("  Thread  |  h  |  H  |  LB  |  UB  |  Num. Items  |  Incumbent  |  Status  ");
	}
	
	private void printLine(int h, int H, ItModel model, double incumbent, String threadName) throws IloException {
		String log = String.format("%9" + "s |", threadName);
		log += String.format("%4" + "s |", h);
		log += String.format("%4" + "s |", H);
		log += String.format("%5" + "s |", (int) model.getLB());
		log += String.format("%5" + "s |", (int) model.getUB());
		
		if(model.getStatus() != Status.Unknown && model.getStatus() != Status.Infeasible)
			log += String.format("%13" + "s |", (int) model.getObjValue());
		else
			log += String.format("%13" + "s |", "-");
		
		if(incumbent > 0) log += String.format("%12.6f" + " |",  incumbent);
		else log += String.format("%12" + "s |", "-");
		
		log += String.format("%10" + "s", model.getStatus());
		logln(log);
	}
}
