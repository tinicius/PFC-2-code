package org.sbpo2025.challenge;

import java.util.HashSet;
import java.util.Set;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;


public class RefLinModel extends BasicModel {

	// Model variables.
	public IloIntVar[] y;
	public IloNumVar[] g, t;
	public IloNumVar u;
	
	// Model expressions.
	IloLinearNumExpr objective;
	IloConstraint lbConstr, ubConstr,
				  lbAisles, ubAisles,
				  lbObj, ubObj;
	
	IloLinearNumExpr sumAisles;
	
	
	public RefLinModel(Instance inst) {
		super(inst);
	}

	protected void buildVarsSpecific() {
		try {	
			// 1, if a-ith aisle was visited; 0, otherwise.
			y = new IloIntVar[inst.aisles.size()];
	        for (int a = 0; a < inst.aisles.size(); a++) 
	            y[a] = model.boolVar("y_" + a);
	        
	        // 1/SUM y_a
	        u = model.numVar(1/inst.aisles.size(), 1, "u");
	        
	        // u, if o-ith order was built; 0, otherwise.
			t = new IloNumVar[inst.orders.size()];
	        for (int o = 0; o < inst.orders.size(); o++) {
	        	if(inst.wontBuild.get(o)) {
	        		t[o] = null;
	        		continue;
	        	}
	            t[o] = model.numVar(0, 1, "t_" + o);
	        }
	        
	        // u, if a-ith aisle was visited; 0, otherwise.
			g = new IloNumVar[inst.aisles.size()];
	        for (int a = 0; a < inst.aisles.size(); a++) 
	            g[a] = model.numVar(0, 1, "g_" + a);
	        

		} catch(IloException e) {
			e.printStackTrace();
		}
	}
	
	protected void buildObjective() {
		try {
			model.addMaximize(objective);
			
		} catch(IloException e) {
			e.printStackTrace();
		}
	}
	
	protected void buildConstrs() throws IloException {
		// LB and UB.
		objective = model.linearNumExpr();
		int totalItems = 0;
		for(int o = 0; o < inst.orders.size(); o++) {
			if(t[o] == null) continue;
			
			for(int i : inst.orders.get(o).keySet())
				totalItems += w.get(o).get(i);
			objective.addTerm(totalItems, t[o]); 
			totalItems = 0;
		}
		
		lbConstr = model.addLe(model.prod(inst.LB, u),  objective);
		ubConstr = model.addLe(objective, model.prod(inst.UB, u));
		
        for (int i = 0; i < inst.n; i++) {
        	if(inst.wontUse.get(i) || inst.easy.get(i)) continue;
        	
        	IloLinearNumExpr sumOrders = model.linearNumExpr();
        	for (int o : inst.itemsPerOrders.get(i).keySet())
        		if(t[o] != null) sumOrders.addTerm(w.get(o).get(i), t[o]);
        	
        	IloLinearNumExpr sumAisles = model.linearNumExpr();
        	for(int a : inst.itemsPerAisles.get(i).keySet()) 
        		sumAisles.addTerm(q.get(a).get(i), g[a]);
        	
        	model.addLe(sumOrders, sumAisles);
        }
        
        for (int i = 0; i < inst.n; i++) {
        	if(inst.wontUse.get(i) || !inst.easy.get(i)) continue;
        	
        	IloLinearNumExpr sumAisles = model.linearNumExpr();
        	for(int a : inst.itemsPerAisles.get(i).keySet()) 
        		sumAisles.addTerm(1, g[a]);
        	
        	for (int o : inst.itemsPerOrders.get(i).keySet())
        		if(t[o] != null) model.addLe(t[o], sumAisles);
        }
        
        // Reformulation-Lienarization constraints.
        for(int a = 0; a < inst.aisles.size(); a++) {
        	model.addLe(g[a], y[a]);
        	model.addLe(g[a], u);
        	model.addGe(g[a], model.sum(u, model.sum(y[a], -1)));
        }
        
        for(int o = 0; o < inst.orders.size(); o++) {
        	if(inst.wontBuild.get(o))
        		continue;
        	
        	model.addLe(t[o], p[o]);
        	model.addLe(t[o], u);
        	model.addGe(t[o], model.sum(u, model.sum(p[o], -1)));
        }
        
        // ( 2 ) SUM y_a = NUM_AISLES
		sumAisles = model.linearNumExpr();
        for(int a = 0; a < g.length; a++) 
        	sumAisles.addTerm(1, g[a]);
        
        model.addEq(sumAisles, 1);
	}
	
	public void setLB(double lb) throws IloException {
		if(lbConstr != null) model.delete(lbConstr);
		lbConstr = model.addLe(model.prod(u, lb),  objective);
	}
	
	public void setUB(double ub) throws IloException {
		if(ubConstr != null) model.delete(ubConstr);
		ubConstr = model.addLe(objective, model.prod(ub, u));
	}
	
	public void setAislesRange(int min, int max) throws IloException {
		if(lbAisles != null) model.delete(lbAisles);
		lbAisles = model.addLe(model.prod(min, u), sumAisles);
		
		if(ubAisles != null) model.delete(ubAisles);
		ubAisles = model.addLe(sumAisles, model.prod(max, u));
	}
	
	public void setObjRange(double min, double max) throws IloException {
		if(lbObj != null) model.delete(lbObj);
		lbObj = model.addLe(min, objective);
		
		if(ubObj != null) model.delete(ubObj);
		ubObj = model.addLe(objective, max);
	}

	public void setObjUB(double ub) throws IloException {
		if(ubObj != null) model.delete(ubObj);
		ubObj = model.addLe(objective, ub);
	}
	
	@Override
	public ChallengeSolution saveSolution() throws IloException {
		Set<Integer> orders = new HashSet<>();
		for(int o = 0; o < inst.orders.size(); o++) 
			if(p[o] != null && model.getValue(p[o]) >= 0.9*model.getValue(u)) 
				orders.add(o);
		
		Set<Integer> aisles = new HashSet<>();
		for(int a = 0; a < y.length; a++) 
			if(model.getValue(y[a]) > .1) 
				aisles.add(a);
		
		return new ChallengeSolution(orders, aisles);
	}
}
