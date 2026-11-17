package org.sbpo2025.challenge;

import java.util.HashSet;
import java.util.Set;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.cplex.IloCplex;


public class ParametricModel extends BasicModel {

	// Model constants.
	double ratio = 0;
	
	// Model variables.
	public IloIntVar[] y;
	
	// Constraint sumY.
	IloLinearNumExpr sumY;
	IloConstraint sumYConstr = null;
	
	public IloLinearNumExpr sumP;
	
	
	public ParametricModel(Instance inst) {
		super(inst);
	}
	
	protected void buildSpecific() throws IloException {
		// Set model parameters.
		model.setParam(IloCplex.Param.MIP.Display, 0);
		model.setOut(null);
	}
	
	protected void buildVarsSpecific() {
		try {	
			// 1, if a-ith aisle was visited; 0, otherwise.
			y = new IloIntVar[inst.aisles.size()];
	        for (int a = 0; a < inst.aisles.size(); a++) 
	            y[a] = model.boolVar("y_" + a);

		} catch(IloException e) {
			e.printStackTrace();
		}
	}
	
	protected void buildObjective() {
		try {
			if(model.getObjective() != null)
				model.delete(model.getObjective());
			
			sumP = objective;
			model.addMaximize(model.sum(sumP, model.prod(-1 * ratio, sumY)));
			
		} catch(IloException e) {
			e.printStackTrace();
		}
	}
	
	protected void buildConstrsSpecific() throws IloException {
        // ( 2 ) SUM y_a = NUM_AISLES
		sumY = model.linearNumExpr();
        for(int a = 0; a < y.length; a++) 
        	sumY.addTerm(1, y[a]);
        
        
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
	}
	
	public void setRatio(double r) {
		ratio = r;
		buildObjective();
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
