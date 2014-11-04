package de.tuberlin.dima.minidb.io.tables;

import de.tuberlin.dima.minidb.core.DataTuple;
import de.tuberlin.dima.minidb.io.tables.TupleRIDIterator;
import de.tuberlin.dima.minidb.core.RID;
import de.tuberlin.dima.minidb.util.Pair;

public class ImpTupleRIDIterator implements TupleRIDIterator {

	private boolean hasnext;
	
	private ImpTupleRIDIterator nextRIDIterator;
	
	private Pair<DataTuple,RID> data;
	
	public ImpTupleRIDIterator() {
		this.hasnext = false;
		this.data = null;
		this.nextRIDIterator = null;
	}
	
	public ImpTupleRIDIterator(Pair<DataTuple,RID> data) {
		this.hasnext = false;
		this.nextRIDIterator = null;
		this.data = data;
	}
	
	public void setNext(ImpTupleRIDIterator t) {
		this.hasnext = true;
		this.nextRIDIterator = t;
	}
	

	public ImpTupleRIDIterator getNextRIDIterator() {
		return this.nextRIDIterator;
	}
	
	public Pair<DataTuple, RID> getDataTuple() {
		return this.data;
	}
	
	@Override
	public boolean hasNext() {
		return this.hasnext;
	}
	
	@Override
	public Pair<DataTuple, RID> next(){
		Pair<DataTuple, RID> t = this.nextRIDIterator.getDataTuple();
		this.hasnext = this.nextRIDIterator.hasnext;
		this.nextRIDIterator = this.nextRIDIterator.nextRIDIterator;
		return t;
	}
	
	
	
	

}
