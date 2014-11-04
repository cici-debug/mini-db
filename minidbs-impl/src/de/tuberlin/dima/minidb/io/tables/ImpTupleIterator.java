package de.tuberlin.dima.minidb.io.tables;

import de.tuberlin.dima.minidb.core.DataTuple;
import de.tuberlin.dima.minidb.io.tables.TupleIterator;


public class ImpTupleIterator implements TupleIterator {

	private boolean hasnext;
	
	private ImpTupleIterator nextIter;
	
	private DataTuple dataTuple;
	
	public ImpTupleIterator() {
		this.hasnext = false;
		this.dataTuple = null;
		this.nextIter = null;
	}
	
	public ImpTupleIterator(DataTuple data) {
		this.hasnext = false;
		this.nextIter = null;
		this.dataTuple = data;
	}
	
	public void setNext(ImpTupleIterator t) {
		this.hasnext = true;
		this.nextIter = t;
	}
	
	public ImpTupleIterator getNextIterator(){
		return nextIter;
	}
	
	public DataTuple getDataTuple() {
		return dataTuple;
	}
	
	@Override
	public boolean hasNext() {
		return this.hasnext;
	}
	
	@Override
	public DataTuple next(){
		DataTuple t = this.nextIter.getDataTuple();
		this.hasnext = this.nextIter.hasnext;
		this.nextIter = this.nextIter.nextIter;
		return t;
	}
}
