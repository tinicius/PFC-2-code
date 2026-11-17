package org.sbpo2025.challenge;

import java.util.BitSet;

import org.apache.commons.lang3.time.StopWatch;

import ilog.concert.IloException;
import ilog.cplex.IloCplex;


public class TSHeuristic extends Approach {
	
	// Initial solution data.
	BSearch initialSolution;
	int minAisles, maxAisles;
	
	// Tabu list.
	int TABU_LOCK = 10;
	int[] tabu;
	
	// Moves.
	Move mv1, mv2;
	Thread mv1Thread,
		   mv2Thread;
	double mv1Value,
		   mv2Value;
	NgbrModel mv1Model, 
	          mv2Model;
	
	ChallengeSolution mv1Solution,
					  mv2Solution,
					  currSolution;
	double currObj;
	
	BitSet aisles, bestAisles;
	
	boolean timeOut    = false,
			timeOutMv1 = false,
			timeOutMv2 = false;
	
	boolean hillclimb = true;
	double DISTURB_FACTOR = 1;
	int    MAX_NO_IMPRV_ITS,
		   noImprvIts = 0;
	
	// Solving info.
	int numIts = 0;
	
	
	public TSHeuristic(Instance inst, StopWatch stopWatch, long time_limit) {
		super(inst, stopWatch, time_limit);
		
		aisles = new BitSet();
		aisles.clear();
		
		initialSolution = new BSearch(inst, stopWatch, (int)(2*60*1000), 0.9);
		initialSolution.setAlfa(0.95);

		mv1Model = new NgbrModel(inst, Runtime.getRuntime().availableProcessors()/2);
		mv1Model.build();
		
		mv2Model = new NgbrModel(inst, Runtime.getRuntime().availableProcessors()/2);
		mv2Model.build();
		
		tabu = new int[inst.aisles.size()];
		for(int i = 0; i < 0; i++) tabu[i] = 0;
		
		MAX_NO_IMPRV_ITS = inst.aisles.size() + TABU_LOCK;
	}
	
	
	public ChallengeSolution optimize() {
		try {
			
			// Finds a initial solution using parallel iterative exact method.
			solution = initialSolution.optimize();
			for(int a : solution.aisles())
				aisles.set(a);
			minAisles = initialSolution.ascendingLastNotAborted + 1;
			maxAisles = initialSolution.decendingLastNotAborted + 1;
			objVal = initialSolution.objVal;
			
			print_header();
			while(!timeOut && noImprvIts <= MAX_NO_IMPRV_ITS) {
				Move mv = move();
				numIts++;
				
				if(mv != null)  {					
					switch(mv.mvType()) {
						case 1:
							aisles.clear(mv.a1());
							break;
						case 2:
							aisles.set(mv.a1());
							break;
					}
					
					if(currObj > objVal) {
						solution = currSolution;
						objVal   = currObj;
						bestAisles = (BitSet) aisles.clone();
						noImprvIts = 0;
						
						printLine();
					} else noImprvIts++;
				} else {
					hillclimb = false;
					DISTURB_FACTOR = 0.5;
				}
				
				maxAisles = (int) Math.floor(inst.UB/objVal);
				updateTabu();
				
				if(noImprvIts > 0 && noImprvIts % 10 == 0)
					printLine();
			}

			
		} catch(IloException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		logln(""+bestAisles);
		
		return solution;
	}
	
	public Move move() throws IloException, InterruptedException {
		Move mv = new Move(-1.0, -1, -1, -1);
		
		mv1Thread = getMv1Thread();
		mv2Thread = getMv2Thread();
		
		mv1Thread.start();
		mv2Thread.start();
		
		mv1Thread.join();
		mv2Thread.join();
		

		timeOut = timeOutMv1 | timeOutMv2;
		if(mv1Solution == null && mv2Solution == null)
			return null;
		
		
		if(mv2Solution == null || mv1Value > mv2Value) {
			mv = mv1;
			tabu[mv1.a1()] = TABU_LOCK;
			
			currSolution = mv1Solution;
			currObj      = mv1.newObj();
			
		} else if(mv2Solution != null) {
			mv = mv2;
			tabu[mv2.a1()] = TABU_LOCK;
			
			currSolution = mv2Solution;
			currObj      = mv2.newObj();
		}
		
		
		
		return mv;
	}
	
	// Remove aisles.
	private Thread getMv1Thread() {
		return new Thread() {
			@Override
			public void run() {	
				try {
					mv1Solution = null; mv1 = null; mv1Value = 0;
					mv1Model.setLB(Math.max(inst.LB, (int)(DISTURB_FACTOR * Math.floor(objVal * (aisles.cardinality()-1))) + 1));

					if(aisles.cardinality() <= minAisles)
						return;
					
					
					if(aisles.cardinality() > 0.25*inst.aisles.size()) {
						mv1Model.disableAisles(aisles);
						for(int a = 0; a < inst.aisles.size(); a++)
							if(aisles.get(a) && tabu[a] > 0) mv1Model.setAisle(a);
						
						mv1Model.setSumY(aisles.cardinality()-1);
						
						mv1Model.setTimeLimit(getRemainingTime(stopWatch));
						mv1Model.solve();
						
						if(mv1Model.getStatus() != IloCplex.Status.Infeasible && mv1Model.getStatus() != IloCplex.Status.Unknown) {
							BitSet sol = mv1Model.getAisles(); int remAisle = 0;
							
							for(int a = 0; a < inst.aisles.size(); a++) 
								if(aisles.get(a) && !sol.get(a)) {
									remAisle = a;
									break;
								}
							
							mv1Value = mv1Model.getObjValue()/(aisles.cardinality()-1);
							mv1 = new Move(mv1Value, 1, remAisle, remAisle);
							mv1Solution = mv1Model.saveSolution();
						}
						
						// Time out.
						if(mv1Model.getStatus() != IloCplex.Status.Infeasible && mv1Model.getStatus() != IloCplex.Status.Optimal)
							timeOutMv1 = true;
						
					} else {
						BitSet copy = (BitSet) aisles.clone();
						mv1Value = 0;
						
						for(int a = 0; a < inst.aisles.size(); a++) {
							if(!copy.get(a) || tabu[a] > 0) continue;
							
							copy.clear(a);
							
							mv1Model.setAisles(copy);
							mv1Model.rmvSumYConstr();
							
							mv1Model.setTimeLimit(getRemainingTime(stopWatch));
							mv1Model.solve();
							
							if(mv1Model.getStatus() != IloCplex.Status.Infeasible && mv1Model.getStatus() != IloCplex.Status.Unknown &&
									mv1Value < mv1Model.getObjValue()/copy.cardinality()) {
								mv1Value = mv1Model.getObjValue()/copy.cardinality();
								mv1 = new Move(mv1Value, 1, a, a);
								mv1Solution = mv1Model.saveSolution();
							}
							
							copy.set(a);
							
							// Time out.
							if(mv1Model.getStatus() != IloCplex.Status.Infeasible && mv1Model.getStatus() != IloCplex.Status.Optimal) {
								timeOutMv1 = true;
								break;
							}
						}
					}
				} catch(IloException e) {
					e.printStackTrace();
				}
			}
		};
	}
	
	// Add aisles.
	private Thread getMv2Thread() {
		return new Thread() {
			@Override
			public void run() {	
				try {
					mv2Solution = null; mv2 = null; mv2Value = 0;
					mv2Model.setLB(Math.max(inst.LB, (int)(DISTURB_FACTOR * Math.floor(objVal * (aisles.cardinality()+1))) + 1));

					if(aisles.cardinality() >= maxAisles)
						return;
					
					
					if(inst.aisles.size() - aisles.cardinality() > 0.25*inst.aisles.size()) {
						mv2Model.enableAisles(aisles);
						for(int a = 0; a < inst.aisles.size(); a++)
							if(!aisles.get(a) && tabu[a] > 0) mv2Model.unsetAisle(a);
						
						mv2Model.setSumY(aisles.cardinality()+1);
						
						mv2Model.setTimeLimit(getRemainingTime(stopWatch));
						mv2Model.solve();
						
						if(mv2Model.getStatus() != IloCplex.Status.Infeasible && mv2Model.getStatus() != IloCplex.Status.Unknown) {
							BitSet sol = mv2Model.getAisles(); int addAisle = 0;
							
							for(int a = 0; a < inst.aisles.size(); a++) 
								if(!aisles.get(a) && sol.get(a)) {
									addAisle = a;
									break;
								}
							
							mv2Value = mv2Model.getObjValue()/(aisles.cardinality()+1);
							mv2 = new Move(mv2Value, 2, addAisle, addAisle);
							mv2Solution = mv2Model.saveSolution();
						}
						
						// Time out.
						if(mv2Model.getStatus() != IloCplex.Status.Infeasible && mv2Model.getStatus() != IloCplex.Status.Optimal)
							timeOutMv2 = true;
						
					} else {
						BitSet copy = (BitSet) aisles.clone();
						mv2Value = 0;
						
						for(int a = 0; a < inst.aisles.size(); a++) {
							if(copy.get(a) || inst.dominated.get(a) || tabu[a] > 0) continue;
							
							copy.set(a);
							
							mv2Model.setAisles(copy);
							mv2Model.rmvSumYConstr();
							
							mv2Model.setTimeLimit(getRemainingTime(stopWatch));
							mv2Model.solve();
							
							if(mv2Model.getStatus() != IloCplex.Status.Infeasible && mv2Model.getStatus() != IloCplex.Status.Unknown &&
									mv2Value < mv2Model.getObjValue()/copy.cardinality()) {
								mv2Value = mv2Model.getObjValue()/copy.cardinality();
								mv2 = new Move(mv2Value, 2, a, a);
								mv2Solution = mv2Model.saveSolution();
							}
							
							copy.clear(a);
							
							// Time out.
							if(mv2Model.getStatus() != IloCplex.Status.Infeasible && mv2Model.getStatus() != IloCplex.Status.Optimal) {
								timeOutMv2 = true;
								break;
							}
						}
					}
				} catch(IloException e) {
					e.printStackTrace();
				}
			}
		};
	}

	protected void updateTabu() {
		for(int i = 0; i < inst.aisles.size(); i++) 
			tabu[i] = Math.max(tabu[i]-1, 0);
	}
	
	public void logAisles() {
		for(int a = 0; a < inst.aisles.size(); a++) {
			if(aisles.get(a)) log("1");
			else log("0");
		}
		logln("");
	}
	
	private void print_header() throws IloException {
		logln("Heuristic step: Tabu Search");
		logln("Improving solution for range: [" + minAisles + "," + maxAisles + "].");
		logln("");
		
		logln("  It  |  h  |  H  |  Incumbent  |  No imprv. |  Max. no imprv.  ");
	}
	
	private void printLine() throws IloException {
		String log = String.format("%5" + "s |", numIts);
		log += String.format("%4" + "s |", minAisles);
		log += String.format("%4" + "s |", maxAisles);
		log += String.format("%12.6f" + " |", objVal);
		log += String.format("%11" + "s |", noImprvIts);
		log += String.format("%15" + "s ", MAX_NO_IMPRV_ITS);
		logln(log);
	}
}
