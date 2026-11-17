package org.sbpo2025.challenge;

import java.util.HashSet;
import java.util.Set;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;


public class ItModel extends BasicModel {

	// CPLEX configuration
	int numThreads;
	
	// Model variables.
	IloNumVar[] y;
	
	// Constraint sumY.
	IloLinearNumExpr sumY;
	IloConstraint sumYConstr = null;
	
	double currLB;
	double currUB;
	
	public ItModel(Instance inst, int threads) {
		super(inst);
		
		numThreads = threads;
	}
	
	@Override
	protected void buildSpecific() {
		try {
			// Set model parameters.
			model.setParam(IloCplex.Param.MIP.Display, 0);
			model.setOut(null);
			
			model.setParam(IloCplex.Param.Threads, numThreads);
			
		} catch(IloException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	protected void buildVarsSpecific() throws IloException {		
		// 1, if a-ith aisle was visited; 0, otherwise.
		y = new IloIntVar[inst.aisles.size()];
        for (int a = 0; a < inst.aisles.size(); a++) 
            y[a] = model.boolVar("y_" + a);
	}
	
	@Override
	protected void buildConstrsSpecific() throws IloException {
        // ( 2 ) SUM y_a = NUM_AISLES
        sumY = model.linearNumExpr();
        for(int a = 0; a < y.length; a++) 
        	sumY.addTerm(1, y[a]);
        
        for (int i = 0; i < inst.n; i++) {
        	if(inst.wontUse.get(i) || inst.easy.get(i)) continue;
        	
        	IloLinearNumExpr sumOrders = model.linearNumExpr();
        	for (int o : inst.itemsPerOrders.get(i).keySet())
        		if(p[o] != null) sumOrders.addTerm(w.get(o).get(i), p[o]);
        	
        	IloLinearNumExpr sumAisles = model.linearNumExpr();
        	for(int a : inst.itemsPerAisles.get(i).keySet()) 
        		sumAisles.addTerm(q.get(a).get(i), y[a]);
        	
        	model.addLe(sumOrders, sumAisles);
        }
        
        for (int i = 0; i < inst.n; i++) {
        	if(inst.wontUse.get(i) || !inst.easy.get(i)) continue;
        	
        	IloLinearNumExpr sumAisles = model.linearNumExpr();
        	for(int a : inst.itemsPerAisles.get(i).keySet()) 
        		sumAisles.addTerm(1, y[a]);
        	
        	for (int o : inst.itemsPerOrders.get(i).keySet())
        		if(p[o] != null) model.addLe(p[o], sumAisles);
        }
        
        // Disable dominated aisles.
		for(int a = 0; a < inst.aisles.size(); a++)
			if(inst.dominated.get(a)) {
				y[a].setLB(0);
				y[a].setUB(0);				
			}
	}
        
	public void setSumY(int NUM_AISLES) throws IloException {
		if(sumYConstr != null) model.delete(sumYConstr);
		sumYConstr = model.addEq(sumY, NUM_AISLES);
	}
	
	public void setLB(int lb) throws IloException {
		if(lbConstr != null) model.remove(lbConstr);
		lbConstr = model.addLe(lb,  objective);
		currLB = lb;
	}
	
	public void setUB(int ub) throws IloException {
		if(ubConstr != null) model.remove(ubConstr);
		ubConstr = model.addLe(objective, ub);
		currUB = ub;
	}
	
	public int getLB() throws IloException {
		return (int) currLB;
	}
	
	public int getUB() throws IloException {
		return (int) currUB;
	}
	
	public void forceAisle(int a) throws IloException {
		y[a].setLB(1);
		y[a].setUB(1);
	}
	
	public void disableAisle(int a) throws IloException {
		y[a].setLB(0);
		y[a].setUB(0);
	}
	
	@Override
	public ChallengeSolution saveSolution() throws IloException {
		Set<Integer> orders = new HashSet<>();
		int size_o = inst.orders.size();
		for(int o = 0; o < size_o; o++) 
			if(p[o] != null && model.getValue(p[o]) > .5) 
				orders.add(o);
		
		Set<Integer> aisles = new HashSet<>();
		for(int a = 0; a < y.length; a++) 
			if(model.getValue(y[a]) > .5) 
				aisles.add(a);
		
		return new ChallengeSolution(orders, aisles);
	}
}
