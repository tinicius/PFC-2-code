package org.sbpo2025.challenge;


import org.apache.commons.lang3.time.StopWatch;

import ilog.concert.*;
import ilog.cplex.*;
import ilog.cplex.IloCplex.Status;


public class ParallelIterative extends Approach {
	
	// CPLEX model.
	ItModel ascendingModel;
	ItModel decendingModel;
	IloCplex.Aborter decendingAborter;
	IloCplex.Aborter ascendingAborter;
	
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
	
	// Threads informations.
	int ascendingLastIt = 0;
	int decendingLastIt = Integer.MAX_VALUE;
	int decendingLastNotAborted;
	int ascendingLastNotAborted;
	
	// Upper and lower bounds for items.
	int upperBoundItems;
	int lowerBoundItems;
	int ascendingLowerBoundItems;
	int decendingLowerBoundItems;
	
	int MAX_AISLES;
	int TOTAL_AISLES;

	int Beta;
	double BetaPerc = 0.01;
	int avoid = -1;
	

	public ParallelIterative(Instance inst, StopWatch stopWatch, long timeLimit) {
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
		
		TOTAL_AISLES = inst.aisles.size();
		MAX_AISLES   = inst.aisles.size() - inst.dominated.cardinality();
		
		ascendingIncumbent = inst.LB/TOTAL_AISLES;
		decendingIncumbent = inst.LB/TOTAL_AISLES;

		Beta = (int) Math.ceil(BetaPerc * inst.aisles.size());
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
			logln("Decending Thread finished at: " + decendingLastIt);
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
					for(int h = MAX_AISLES; h > 0; h-=Beta) {
						if(getRemainingTime(stopWatch) <= 5) {
							logln("Time Limit reached (DE).");
							decendingTimeOut = true;
							break;
						}

						// Set parameters for running the model for h aisles..
						decendingModel.setTimeLimit(getRemainingTime(stopWatch));
						
						// Optimizes model for "num_aisles" aisles.
						h = Math.min(h, MAX_AISLES);
						if(h == avoid) continue;
						decendingModel.setSumY(h);
						
						double bestIncumbent = Math.max(ascendingIncumbent, decendingIncumbent);
						decendingLowerBoundItems = Math.max(inst.LB, (int) Math.floor(bestIncumbent * h + 1));	
						
						decendingModel.setLB(decendingLowerBoundItems);
						decendingModel.setUB(upperBoundItems);
						
						// Optimizes for h aisles.
						if(!ascendingOptimal && (h >= ascendingLastIt)) {
							decendingLastIt = h;
							decendingAborter.clear();
							decendingModel.solve();
						}
						else break;
						
						// If a better solution was found.
						if(decendingModel.getStatus() == IloCplex.Status.Optimal) {
							if(decendingModel.getObjValue()/h > decendingIncumbent) {		
								decendingIncumbent = decendingModel.getObjValue()/h;
								decendingSolution = decendingModel.saveSolution();
							}
							
							upperBoundItems = (int) decendingModel.getObjValue();

						} else if(decendingModel.getStatus() == IloCplex.Status.Feasible && decendingModel.getObjValue()/h > decendingIncumbent)  {								
								decendingIncumbent = decendingModel.getObjValue()/h;
								decendingSolution = decendingModel.saveSolution();
						} else if(decendingModel.getStatus() == IloCplex.Status.Infeasible)
							upperBoundItems = ((int) decendingModel.getLB()) - 1;
						
						// Saves the last not aborted iteration (in case other method will be ran after it).
						if(decendingModel.getStatus() == IloCplex.Status.Infeasible || decendingModel.getStatus() == IloCplex.Status.Optimal)
							decendingLastNotAborted = h;

						// If a solution worst than UB is found, updates Beta to 1 and run cut iterations.
						if(Beta > 1 && (decendingModel.getStatus() == IloCplex.Status.Infeasible || decendingModel.getStatus() == IloCplex.Status.Unknown || 
							decendingModel.getObjValue() < inst.UB)) {
							h += Beta;
							Beta = 1;
						}

						printLine(h, ascendingLastIt, decendingModel, decendingIncumbent, "DE");
					}
				} catch(IloException e) {
					e.printStackTrace();
				}
				
				decendingOptimal = !decendingTimeOut;
				ascendingAborter.abort();
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
						
						// Set parameters for running the model for h aisles..
						ascendingModel.setTimeLimit(getRemainingTime(stopWatch));
						ascendingModel.setSumY(h);
						
						// Update lower bound.
						double bestIncumbent = Math.max(ascendingIncumbent, decendingIncumbent);
						ascendingLowerBoundItems = Math.max(inst.LB, (int) Math.floor(bestIncumbent * h + 1));	
						ascendingModel.setLB(ascendingLowerBoundItems);
						ascendingModel.setUB(upperBoundItems);
						
						// Optimizes model for "num_aisles" aisles.
						if(!decendingOptimal && (h <= decendingLastIt))
							ascendingModel.solve();
						else break;
						
						// If a better solution was found.
						if(ascendingModel.getStatus() == IloCplex.Status.Feasible || ascendingModel.getStatus() == IloCplex.Status.Optimal) { 
							if(ascendingModel.getObjValue()/h > ascendingIncumbent) {						
								ascendingIncumbent = ascendingModel.getObjValue()/h;
								ascendingSolution = ascendingModel.saveSolution();
							}
							
							// Update maximum of aisles.
							MAX_AISLES = Math.min(MAX_AISLES, (int) Math.floor(upperBoundItems / ascendingIncumbent));
							if(MAX_AISLES < decendingLastIt) {
								Beta = 1;
								decendingAborter.abort();
							}
						}
						
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
	
	
	public void setBetaPerc(double perc) {
		BetaPerc = perc;
		Beta = (int) Math.ceil(BetaPerc * inst.aisles.size());
	}
	
	
	// === DEBUGGING AND LOGGING METHODS ===
	private void print_header() throws IloException {
		logln("Approach: Parallel Iterative Solver");
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
		
		if(model.getStatus() == Status.Optimal)
			log += String.format("%13" + "s |", (int) model.getObjValue());
		else
			log += String.format("%13" + "s |", "-");
		
		if(incumbent > 0) log += String.format("%12.6f" + " |",  incumbent);
		else log += String.format("%12" + "s |", "-");
		
		log += String.format("%10" + "s", model.getStatus());
		logln(log);
	}
}
