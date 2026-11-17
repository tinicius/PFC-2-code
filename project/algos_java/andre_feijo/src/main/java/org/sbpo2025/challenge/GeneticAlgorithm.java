package org.sbpo2025.challenge;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.Pair;

import ilog.concert.IloException;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.Status;

public class GeneticAlgorithm extends Approach {
	
	// GA Parameters
	int _PopSize;                  // even number
	double _MutationRate   =  0.4;
	int _AdptationNum      =    5;
	double _RankPression   =  1.5; // (1, 2]
	double _EliteRate      =  0.5;
	
	// Population Management
	List<Individual> _Population, _Parents, _Offspring, _Adapted, _Mutated;
	int _IndIdControl = 0;
	
	// Individual constraints
	int _MinAisles, _MaxAisles;
	List<Integer> _AllowedAisles;
	// List<Integer> _AllowedYSums;
	
	// Models
    HeuristicModel _Model;
    NgbrModel _RemAisleModel, _AddAisleModel;
    Move _RemMove, _AddMove;
    double _RemVal, _AddVal;
	
	// Random numbers settings
    private final Random RANDOM;
    
    // Binary Search
    BSearch _BSearch;
	
	public GeneticAlgorithm(Instance inst, StopWatch stopWatch, long time_limit, long seed) {
		super(inst, stopWatch, time_limit);
		this.RANDOM = new Random(seed);
		
		_BSearch = new BSearch(inst, stopWatch, (int)(2*60*1000), 0.9);
		
		_AllowedAisles = new ArrayList<Integer>(inst.aisles.size());
		for (int a = 0; a < inst.aisles.size(); a++) {
			if (!inst.dominated.get(a)) _AllowedAisles.add(a);
		}
		
		/*_MinAisles = minAisles;
		_MaxAisles = Math.min(_AllowedAisles.size(), maxAisles);*/
		
		_Population = new ArrayList<Individual>();
		
		_Model = new HeuristicModel(inst, Runtime.getRuntime().availableProcessors());
		_Model.build();
		
		_RemAisleModel = new NgbrModel(inst, Runtime.getRuntime().availableProcessors()/2);
		_RemAisleModel.build();
		
		_AddAisleModel = new NgbrModel(inst, Runtime.getRuntime().availableProcessors()/2);
		_AddAisleModel.build();
		
	}
	
	public ChallengeSolution optimize() { 
		
		long begTime = System.currentTimeMillis();
		
		generateInitialPop();
	
		for (int gen = 1; getRemainingTime(stopWatch) > 5 && gen <= 2; gen++) {
			
			System.out.println("<--- Generation " + gen + " --->\n");
			
			matching();
			recombination();
			mutation();
			adaptation();
			selection();
			
			printPopulationStats();
			
			long currTime = System.currentTimeMillis();
			double passedTime = (currTime - begTime) / 1000.0;
			System.out.println("Passed time: " + String.format("%.2f", passedTime) + "\n");
			
			
			_AdptationNum--;
		}
		
		try {
			double max = 0; int best = -1;
			for(int p = 0; p < _Population.size(); p++) 
				if(_Population.get(p).getFitness() > max) {
					max = _Population.get(p).getFitness();
					best = p;
				}
				logln("best = " + _Population.get(best).getCromossome());
			
				_Model.setTimeLimit(100000);
				_Model.setAisles(_Population.get(best).getCromossome());
				_Model.solve();
				solution = _Model.saveSolution();
				
		} catch (IloException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return solution;
	}
	
	private void generateInitialPop() {
		long begTime = System.currentTimeMillis();
		
		_BSearch.setAlfa(0.5);
		_BSearch.optimize();
		
		List<Pair<Integer, Integer>> intervals = new ArrayList<Pair<Integer,Integer>>();
		
		int begin = _BSearch.ascendingLastNotAborted;
		for (int i = _BSearch.ascendingLastNotAborted + 1; i <= _BSearch.decendingLastNotAborted; i++) {
			if (_BSearch.feasibles.contains(i)) {
				intervals.add(Pair.of(begin, i));
				begin = i;
			}
		}
		
		System.out.println("AC not abt = " + _BSearch.ascendingLastNotAborted);
		System.out.println("DC not abt = " + _BSearch.decendingLastNotAborted);
		
		System.out.println("INTERVALS = " + intervals);
		
		_MinAisles = _BSearch.ascendingLastNotAborted + 1;
		_MaxAisles = _BSearch.decendingLastNotAborted - 1;
		
		for (int i = 0; i < intervals.size(); i++) {
			int beg = intervals.get(i).getLeft();
			int end = intervals.get(i).getRight();
			
			// Create individuals with the limits of the interval
			BitSet begCrom = _BSearch.itsols.get(beg).getRight();
			double begFitness = evaluate(begCrom);
			Individual begInd = new Individual(++_IndIdControl, begCrom, false);
			begInd.setFitness(begFitness);
			_Population.add(begInd);
			
			BitSet endCrom = _BSearch.itsols.get(end).getRight();
			if (i == intervals.size() - 1) {
				double endFitness = evaluate(endCrom);
				Individual endInd = new Individual(++_IndIdControl, endCrom, false);
				endInd.setFitness(endFitness);
				_Population.add(endInd);
			}
			
			for (int j = beg + 1; j < end; j++) {
				for (int k = 0; k < 5; k++) {
					BitSet crom = (BitSet) begCrom.clone();
					addRandomAisles(crom, j - beg);
					
					double fitness = evaluate(crom);
					Individual ind = new Individual(++_IndIdControl, crom, false);
					ind.setFitness(fitness);
					
					_Population.add(ind);
				}
			}
			
			
			for (int j = end - 1; j > beg; j--) {
				for (int k = 0; k < 5; k++) {
					BitSet crom = (BitSet) endCrom.clone();
					removeRandomAisles(crom, end - j);
					
					double fitness = evaluate(crom);
					Individual ind = new Individual(++_IndIdControl, crom, false);
					ind.setFitness(fitness);
					
					_Population.add(ind);
				}
			}
		}
		
		if (_Population.size() % 2 != 0) {
			BitSet crom = (BitSet) _BSearch.itsols.get(_BSearch.ascendingLastNotAborted).getRight().clone();
			mutateCromossome(crom);
			Individual ind = new Individual(++_IndIdControl, crom, false);
			double fitness = evaluate(crom);
			ind.setFitness(fitness);
			_Population.add(ind);
		}
		
		/*
		for (int ind = 0; ind < _PopSize; ind++) {
			List<Integer> shuffledAisles = new ArrayList<Integer>(_AllowedAisles); 
			Collections.shuffle(shuffledAisles, RANDOM);
			
			int sumY = RANDOM.nextInt(_MinAisles, _MaxAisles + 1);
			BitSet cromossome = new BitSet(inst.aisles.size());
			for (int i = 0; i < sumY; i++) cromossome.set(shuffledAisles.get(i));
			
			Individual newInd = new Individual(++_IndIdControl, cromossome, false);
			try {				
				_Model.setAisles(cromossome);
				_Model.setTimeLimit(getRemainingTime(stopWatch));
				_Model.solve();
				
				if (_Model.getStatus() != IloCplex.Status.Infeasible && _Model.getStatus() != IloCplex.Status.Unknown)
					newInd.setFitness(_Model.getObjValue() / cromossome.cardinality());
				else
					countZeroFit++;
				
			} catch(IloException e) {
				System.out.println("Error on setAisles with cromossome = " + cromossome);
				e.printStackTrace();
			}
			_Population.add(newInd);
		}
		*/
		_PopSize = _Population.size();
		System.out.println("Population size = " + _PopSize);
		
		int countZeroFit = 0;
		for (int i = 0; i < _Population.size(); i++)
			if (_Population.get(i).getFitness() == 0.0) countZeroFit++;
		
		long endTime = System.currentTimeMillis();
		double duration = (endTime - begTime) / 1000.0;
		System.out.println("Population Generation: " + String.format("%.2f", duration) + "s");
		System.out.println("Invalid individuals  : " + (double) countZeroFit / _PopSize + "\n");
	}
	
	private void matching() {
		long begTime = System.currentTimeMillis();
		
		_Parents = new ArrayList<Individual>(_PopSize);
		
		Collections.sort(_Population, (a, b) -> Double.compare(a.getFitness(), b.getFitness()));		
		
		// Ranking selection
		List<Double> cumProb = new ArrayList<>(_PopSize);
		double baseline = (2 - _RankPression) / _PopSize;
		double denominator = _PopSize * (_PopSize - 1);
		
		int i = 0;
		for (Individual ind : _Population) {
			double p = baseline + (2 * i * (_RankPression - 1))/denominator;
			if (i == 0) {
				cumProb.add(p);
				i++;
				continue;
			}
			cumProb.add(cumProb.get(i-1) + p);
			i++;
		}
		
		List<Integer> result = susRoulette(cumProb, _PopSize);
		Collections.shuffle(result, RANDOM);
		
		for (int j = 0; j < result.size(); j++)
			_Parents.add(_Population.get(result.get(j)));
		
		long endTime = System.currentTimeMillis();
		System.out.println("Matching: " + (endTime - begTime) / 1000.0 + "s");
	}
	
	private void recombination() {
		long begTime = System.currentTimeMillis();
		
		_Offspring = new ArrayList<Individual>(_PopSize / 2);
		
		try {			
			for (int i = 0; i < _Parents.size(); i += 2) {
				Individual p1 = _Parents.get(i);
				Individual p2 = _Parents.get(i+1);
				
				BitSet crom1 = p1.getCromossome();
				BitSet crom2 = p2.getCromossome();
				
				if (p1.getId() == p2.getId()) { // Mutation will be applied
					BitSet crom = p1.getCromossome();
					Individual newInd = new Individual(++_IndIdControl, crom, true); 
					_Offspring.add(newInd);
					
					_Model.setAisles(crom);
					_Model.setTimeLimit(getRemainingTime(stopWatch));
					_Model.solve();
					
					if (_Model.getStatus() != IloCplex.Status.Infeasible && _Model.getStatus() != IloCplex.Status.Unknown)
						newInd.setFitness(_Model.getObjValue() / crom.cardinality());
					
				} else { // Crossover
					BitSet orPs = (BitSet) crom1.clone();
					orPs.or(crom2);
					
					BitSet andPs = (BitSet) crom1.clone();
					andPs.and(crom2);
					
					BitSet xorRes = (BitSet) orPs.clone();
					xorRes.xor(andPs);
					
					int minCard = Math.min(crom1.cardinality(), crom2.cardinality());
					int maxCard = Math.max(crom1.cardinality(), crom2.cardinality());
					int sonCard = RANDOM.nextInt(minCard, maxCard + 1);
					
					int remove = xorRes.cardinality() - (sonCard - andPs.cardinality());
					List<Integer> positions = getOnePositions(xorRes);
					Collections.shuffle(positions, RANDOM);
					for (int j = 0; j < remove; j++) orPs.clear(positions.get(j));
					
					Individual newInd = new Individual(++_IndIdControl, orPs, false);
					_Offspring.add(newInd);
					
					if (sonCard != orPs.cardinality()) System.out.println("!!! Error on recombination !!!");
					
					_Model.setAisles(orPs);
					_Model.setTimeLimit(getRemainingTime(stopWatch));
					_Model.solve();
					
					if (_Model.getStatus() != IloCplex.Status.Infeasible && _Model.getStatus() != IloCplex.Status.Unknown)
						newInd.setFitness(_Model.getObjValue() / orPs.cardinality());
				}
			}
		} catch(IloException e) {
			System.out.println("CPLEX error on recombination");
			e.printStackTrace();
		}
		
		long endTime = System.currentTimeMillis();
		double duration = (endTime - begTime) / 1000.0;
		System.out.println("Recombination: " + String.format("%.2f", duration) + "s");
	}
	
	private void mutation() {
		long begTime = System.currentTimeMillis();
		
		_Mutated = new ArrayList<Individual>((int) Math.ceil((_MutationRate + 0.3) * _Offspring.size())); // Estimate size
		try {
			for (int i = 0; i < _Offspring.size(); i++) {
				if (_Offspring.get(i).getParthenogenesis()) {
					Individual ind = _Offspring.get(i);
					BitSet crom = ind.getCromossome();
					mutateCromossome(crom);
					
					_Model.setAisles(crom);
					_Model.setTimeLimit(getRemainingTime(stopWatch));
					_Model.solve();
					
					if (_Model.getStatus() != IloCplex.Status.Infeasible && _Model.getStatus() != IloCplex.Status.Unknown)
						ind.setFitness(_Model.getObjValue() / crom.cardinality());
					else
						ind.setFitness(0.0);
					
					ind.setCromossome(crom);
					
				} else if (RANDOM.nextDouble() <= _MutationRate) {
					BitSet crom = _Offspring.get(i).getCromossome();
					mutateCromossome(crom);
					Individual newInd = new Individual(++_IndIdControl, crom, false);
					
					_Model.setAisles(crom);
					_Model.setTimeLimit(getRemainingTime(stopWatch));
					_Model.solve();
					
					if (_Model.getStatus() != IloCplex.Status.Infeasible && _Model.getStatus() != IloCplex.Status.Unknown)
						newInd.setFitness(_Model.getObjValue() / crom.cardinality());
					
					_Mutated.add(newInd);
				}
			}
		} catch (IloException e) {
			System.out.println("CPLEX error on mutation");
			e.printStackTrace();
		}
		
		long endTime = System.currentTimeMillis();
		double duration = (endTime - begTime) / 1000.0;
		System.out.println("Mutation: " + String.format("%.2f", duration) + "s");
	}
	
	private void mutateCromossome(BitSet crom) {
		List<Integer> onePos = getOnePositions(crom);
		List<Integer> zeroPos = getAllowedZeroPositions(crom);
		
		if (onePos.size() > 0 && zeroPos.size() > 0) {
			int randIdx = RANDOM.nextInt(0, onePos.size());
			crom.clear(onePos.get(randIdx));
			
			randIdx = RANDOM.nextInt(0, zeroPos.size());
			crom.set(zeroPos.get(randIdx));
		}
	}
	
	private void adaptation() {
		long begTime = System.currentTimeMillis();
		
		_Population.addAll(_Offspring);
		_Population.addAll(_Mutated);
		
		Collections.sort(_Population, (a, b) -> Double.compare(a.getFitness(), b.getFitness()));
		
		List<Integer> adapted = new ArrayList<Integer>();
		
		int i = _Population.size() - 1;
		while (i >= 0 && adapted.size() < _AdptationNum) {
			logln("rodando");
			
			Individual ind = _Population.get(i);
			BitSet crom = ind.getCromossome();
			
			if (ind.getImprovable()) {
				try {
					Thread remThread = removeAisle(crom, ind.getFitness());
					Thread addThread = addAisle(crom, ind.getFitness());
					
					remThread.start();
					addThread.start();
					
					remThread.join();
					addThread.join();
					
					if (_RemVal > _AddVal && _RemVal > ind.getFitness()) {
						crom.clear(_RemMove.a1());
						ind.setCromossome(crom);
						ind.setFitness(_RemVal);
						adapted.add(ind.getId());
					} else if (_AddVal > _RemVal && _AddVal > ind.getFitness()) {
						crom.set(_AddMove.a2());
						ind.setCromossome(crom);
						ind.setFitness(_AddVal);
						adapted.add(ind.getId());
					} else {
						ind.setImprovable(false);
					}
				} catch (InterruptedException e) {
					System.out.println("Thread error on adaptation");
					e.printStackTrace();
				}
			
			}
			i--;
		}
		
		System.out.println("Adapted: " + adapted);
		
		/*for (int i = 0; i < _Population.size(); i++) {
			if (RANDOM.nextDouble() > _AdaptationRate) continue;
			
			try {
				Individual ind = _Population.get(i);
				System.out.println("Start adaptation on " + ind.getId());
				BitSet crom = ind.getCromossome();
				Thread remThread = removeAisle(crom);
				Thread addThread = addAisle(crom);
				
				remThread.start();
				addThread.start();
				
				remThread.join();
				addThread.join();
			
				if (_RemVal > _AddVal && _RemVal > ind.getFitness()) {
					crom.clear(_RemMove.a1());
					ind.setCromossome(crom);
					ind.setFitness(_RemVal);
				} else if (_AddVal > _RemVal && _AddVal > ind.getFitness()) {
					crom.set(_AddMove.a2());
					ind.setCromossome(crom);
					ind.setFitness(_AddVal);
				}
				System.out.println("End adaptation on " + ind.getId());
			} catch (InterruptedException e) {
				System.out.println("Thread error on adaptation");
				e.printStackTrace();
			}
		}*/
		
		long endTime = System.currentTimeMillis();
		double duration = (endTime - begTime) / 1000.0;
		System.out.println("Adaptation: " + String.format("%.2f", duration) + "s");
	}
	
	private void selection() {
		long begTime = System.currentTimeMillis();
		
		/*_Population.addAll(_Offspring);
		_Population.addAll(_Mutated);*/
		
		Collections.sort(_Population, (a, b) -> Double.compare(a.getFitness(), b.getFitness()));
		
		int eliteSlots = (int) Math.ceil(_EliteRate * _PopSize);
		List<Individual> nextGen = new ArrayList<Individual>(_Population.subList(_Population.size() - eliteSlots, _Population.size()));
		
		List<Individual> luckPop = new ArrayList<Individual>(_Population.subList(0, _Population.size() - eliteSlots));
		Collections.shuffle(luckPop, RANDOM);
		
		for (int i = 0; i < luckPop.size() && nextGen.size() < _PopSize; i++)
			nextGen.add(luckPop.get(i));
		
		_Population = nextGen;
		
		long endTime = System.currentTimeMillis();
		double duration = (endTime - begTime) / 1000.0;
		System.out.println("Selection: " + String.format("%.2f", duration) + "s");
	}
	
	// ===== Auxiliary methods =====
	
	private double evaluate(BitSet cromossome) {
		try {				
			_Model.setAisles(cromossome);
			_Model.setTimeLimit(getRemainingTime(stopWatch));
			_Model.solve();
			
			if (_Model.getStatus() != IloCplex.Status.Infeasible && _Model.getStatus() != IloCplex.Status.Unknown)
				return _Model.getObjValue() / cromossome.cardinality();
			else
				return 0.0;
			
		} catch(IloException e) {
			System.out.println("Error on evaluate with cromossome = " + cromossome);
			e.printStackTrace();
			return 0.0;
		}
	}
	
	private List<Integer> susRoulette(List<Double> cumProb, int numSelections) {
		List<Integer> result = new ArrayList<Integer>(Collections.nCopies(numSelections, null));
		int currSel = 0, i = 0;
		double r = RANDOM.nextDouble(1.0 / numSelections);
		while (currSel < numSelections) {
			while (r <= cumProb.get(i)) {
				result.set(currSel, i);
				r += 1.0 / numSelections; 
				currSel++;
				
				if(currSel == numSelections) break;
			}
			i++;
		}
		return result;
	}
	
	private List<Integer> getOnePositions(BitSet bitset) {
        List<Integer> positions = new ArrayList<>(bitset.cardinality());
        int i = bitset.nextSetBit(0);
        while (i != -1) {
            positions.add(i);
            i = bitset.nextSetBit(i + 1);
        }
        return positions;
    }
	
	private List<Integer> getAllowedZeroPositions(BitSet bitset) {
	    List<Integer> positions = new ArrayList<>(inst.aisles.size());
	    for (int i = 0; i < inst.aisles.size(); i++) {
	        if (!bitset.get(i) && !inst.dominated.get(i))
	            positions.add(i);
	    }
	    return positions;
	}
	
	private Thread removeAisle(BitSet aisles, double fitness) {
		return new Thread() {
			@Override
			public void run() {
				try {
					_RemMove = null;
					BitSet aislesCopy = (BitSet) aisles.clone();
					_RemVal = 0.0;
					
					_RemAisleModel.setLB(Math.max(inst.LB, (int)(Math.floor(fitness * (aisles.cardinality()-1))) + 1));
					
					for (int a = 0; a < inst.aisles.size(); a++) {
						if (!aislesCopy.get(a)) continue;
						
						aislesCopy.clear(a);
						
						_RemAisleModel.setAisles(aislesCopy);
						_RemAisleModel.setTimeLimit(getRemainingTime(stopWatch));
						_RemAisleModel.solve();
						
						Status status = _RemAisleModel.getStatus(); 
						if (status != IloCplex.Status.Infeasible && status != IloCplex.Status.Unknown &&
								(_RemAisleModel.getObjValue() / aislesCopy.cardinality()) > _RemVal) {
							_RemVal = _RemAisleModel.getObjValue() / aislesCopy.cardinality();
							_RemMove = new Move(_RemVal, -1, a, -1);
						}
						
						aislesCopy.set(a);
					}
				} catch (IloException e) {
					System.out.println("Error on removeAilse");
					e.printStackTrace();
				}
			}
		};
	}
	
	private Thread addAisle(BitSet aisles, double fitness) {
		return new Thread() {
			@Override
			public void run() {
				try {
					_AddMove = null;
					_AddVal = 0.0;
					BitSet aislesCopy = (BitSet) aisles.clone();
					
					_AddAisleModel.setLB(Math.max(inst.LB, (int)(Math.floor(fitness * (aisles.cardinality()+1))) + 1));
					
					for (int a = 0; a < inst.aisles.size(); a++) {
						if (aislesCopy.get(a) || inst.dominated.get(a)) continue;
						
						aislesCopy.set(a);
						
						_AddAisleModel.setAisles(aislesCopy);
						_AddAisleModel.setTimeLimit(getRemainingTime(stopWatch));
						_AddAisleModel.solve();
						
						Status status = _AddAisleModel.getStatus(); 
						if (status != IloCplex.Status.Infeasible && status != IloCplex.Status.Unknown &&
								(_AddAisleModel.getObjValue() / aislesCopy.cardinality()) > _AddVal) {
							_AddVal = _AddAisleModel.getObjValue() / aislesCopy.cardinality();
							_AddMove = new Move(_AddVal, 1, -1, a);
						}
						
						aislesCopy.clear(a);
					}
				} catch (IloException e) {
					System.out.println("Error on addAisle");
					e.printStackTrace();
				}
			}
		};
	}
	
	private void removeRandomAisles(BitSet crom, int n) {
		if (crom.cardinality() - n <= 0) {
			System.out.println("!!! Invalid remove !!!");
			return;
		}
		
		List<Integer> onePos = getOnePositions(crom);
		Collections.shuffle(onePos, RANDOM);
		
		int removed = 0; 
		for (int i = 0; i < onePos.size() && removed < n; i++) {
			crom.clear(onePos.get(i));
			removed++;
		}
	}
	
	private void addRandomAisles(BitSet crom, int n) {
		Collections.shuffle(_AllowedAisles, RANDOM);
		
		int added = 0;
		for (int i = 0; i < _AllowedAisles.size() && added < n; i++) {
			if (!crom.get(_AllowedAisles.get(i))) {
				crom.set(_AllowedAisles.get(i));
				added++;
			}
		}
	}
	
	private void printPopulationStats() {
		double sum = 0.0;
		double sumSquares = 0.0;
		double best = Double.NEGATIVE_INFINITY;
		
		for (Individual ind : _Population) {
			double fitness = ind.getFitness();
			sum += fitness;
			sumSquares += fitness * fitness;
			if (fitness > best) best = fitness;
		}
		
		double avg = sum / _PopSize;
		double stdDev = Math.sqrt((sumSquares / _PopSize) - (avg * avg));
		
		System.out.println();
		System.out.println("Best Fitness : " + String.format("%.4f", best));
		System.out.println("Average      : " + String.format("%.2f", avg));
		System.out.println("Std Deviation: " + String.format("%.2f", stdDev));
		System.out.println();
	}
}
