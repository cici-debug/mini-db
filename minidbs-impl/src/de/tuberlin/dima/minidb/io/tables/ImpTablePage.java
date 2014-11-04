package de.tuberlin.dima.minidb.io.tables;

import de.tuberlin.dima.minidb.catalogue.ColumnSchema;
import de.tuberlin.dima.minidb.catalogue.TableSchema;
import de.tuberlin.dima.minidb.core.BigIntField;
import de.tuberlin.dima.minidb.core.CharField;
import de.tuberlin.dima.minidb.core.DataType;
import de.tuberlin.dima.minidb.core.BasicType;
import de.tuberlin.dima.minidb.core.DataTuple;
import de.tuberlin.dima.minidb.core.DataField;
import de.tuberlin.dima.minidb.core.DateField;
import de.tuberlin.dima.minidb.core.DoubleField;
import de.tuberlin.dima.minidb.core.FloatField;
import de.tuberlin.dima.minidb.core.IntField;
import de.tuberlin.dima.minidb.core.RID;
import de.tuberlin.dima.minidb.core.SmallIntField;
import de.tuberlin.dima.minidb.core.TimeField;
import de.tuberlin.dima.minidb.core.TimestampField;
import de.tuberlin.dima.minidb.core.VarcharField;
import de.tuberlin.dima.minidb.io.cache.PageExpiredException;
import de.tuberlin.dima.minidb.io.cache.PageFormatException;
import de.tuberlin.dima.minidb.io.tables.PageTupleAccessException;
import de.tuberlin.dima.minidb.io.tables.TablePage;
import de.tuberlin.dima.minidb.qexec.LowLevelPredicate;
import de.tuberlin.dima.minidb.io.tables.ImpTupleIterator;
import de.tuberlin.dima.minidb.util.Pair;

public class ImpTablePage implements TablePage {
	
	private int numRecordsOnPage;	
	private int pageNumber;	
	private int recordWidth;
	private int variableLegnthChunkOffset;
	
	private boolean expired;
	private boolean modified;
	
	private byte[] buff;	
	private DataType[] datatype;	
	private int numberOfColumn;
	
	public ImpTablePage(TableSchema tableSchema, byte[] buffer) {
		this.pageNumber = IntField.getIntFromBinary(buffer, 4);
		this.numRecordsOnPage = IntField.getIntFromBinary(buffer, 8);
		this.recordWidth = IntField.getIntFromBinary(buffer, 12);
		this.variableLegnthChunkOffset = IntField.getIntFromBinary(buffer, 16);
		this.expired = false;
		this.modified = false;	
		this.datatype = new DataType[tableSchema.getNumberOfColumns()];
		for (int i = 0; i < tableSchema.getNumberOfColumns(); i++) {
			ColumnSchema column = tableSchema.getColumn(i);
			this.datatype[i] = column.getDataType();
		}
		this.numberOfColumn = tableSchema.getNumberOfColumns();
		this.buff = buffer;
	}
	
	public ImpTablePage(TableSchema tableSchema, byte[] buffer, int newPageNumber) {		
		this.pageNumber = newPageNumber;
		this.numRecordsOnPage = 0;
		this.variableLegnthChunkOffset = buffer.length - 1;
		this.recordWidth = 4;
		this.expired = false;
		this.modified = true;
		this.datatype = new DataType[tableSchema.getNumberOfColumns()];
		for (int i = 0; i < tableSchema.getNumberOfColumns(); i++) {
			ColumnSchema column = tableSchema.getColumn(i);
			this.datatype[i] = column.getDataType();
			if (this.datatype[i].getBasicType() != BasicType.VAR_CHAR)
				this.recordWidth = this.recordWidth + this.datatype[i].getNumberOfBytes();
			else
				this.recordWidth +=8;				
		}
		this.numberOfColumn = tableSchema.getNumberOfColumns();	
		IntField.encodeIntAsBinary(this.TABLE_DATA_PAGE_HEADER_MAGIC_NUMBER, buffer, 0);
		IntField.encodeIntAsBinary(this.pageNumber, buffer, 4);
		IntField.encodeIntAsBinary(this.numRecordsOnPage, buffer, 8);
		IntField.encodeIntAsBinary(this.recordWidth, buffer, 12);
		IntField.encodeIntAsBinary(this.variableLegnthChunkOffset, buffer, 16);
		this.buff = buffer;
	}
	
	public int getPageNumber() throws PageExpiredException{
		if (this.expired == false)
			return this.pageNumber;
		else
			throw new PageExpiredException();
	}
	
	public int getNumRecordsOnPage() throws PageExpiredException{
		if (this.expired == false)
			return this.numRecordsOnPage;
		else
			throw new PageExpiredException();
	}
	
	
	public boolean insertTuple(DataTuple tuple) throws PageFormatException, PageExpiredException{
		if (this.expired == false){
			int numberOfFields = tuple.getNumberOfFields();
			int totalLength = 4;
			for (int pos = 0; pos < numberOfFields; pos++){
				DataField field = tuple.getField(pos);
				if (field.getBasicType() != BasicType.VAR_CHAR)
					totalLength += field.getNumberOfBytes();
				else
					totalLength += 8;
			}
			if (32 + (this.numRecordsOnPage + 1) * this.recordWidth > this.variableLegnthChunkOffset)
				return false;
			else{
				int offset = 32 + this.numRecordsOnPage * this.recordWidth;
				buff[offset + 3] = buff[offset + 2] = buff[offset + 1] = buff[offset] = 0;
				offset += 4;
				for (int pos = 0; pos < numberOfFields; pos++){
					DataField field = tuple.getField(pos);
					if (field.getBasicType() != BasicType.VAR_CHAR) {
						field.encodeBinary(buff, offset);
						offset += (this.datatype[pos].getNumberOfBytes());
					}
					else {
						int length = field.getNumberOfBytes();
						int start = this.variableLegnthChunkOffset - length;
						if (start < 32 + (this.numRecordsOnPage + 1) * this.recordWidth)
							return false;	
						if (field.isNULL())
							length = -1;
						IntField.encodeIntAsBinary(start, buff, offset);
						IntField.encodeIntAsBinary(length, buff, offset + 4);
						field.encodeBinary(buff, start);					
						this.variableLegnthChunkOffset = start;
						offset +=8;
					}
				}
			this.numRecordsOnPage++;
			IntField.encodeIntAsBinary(this.numRecordsOnPage, buff, 8);
			IntField.encodeIntAsBinary(this.variableLegnthChunkOffset, buff, 16);
			this.modified = true;
			return true;
			}	
		}		
		else
			throw new PageExpiredException();
	}

		
	public void deleteTuple(int position) throws PageTupleAccessException, PageExpiredException{
		if (this.expired == true)
			throw new PageExpiredException();
		else if (position < 0 || position >= this.numRecordsOnPage)
				throw new PageTupleAccessException(position);
		else {
			buff[32 + position * this.recordWidth] = 1;
			this.modified = true;
		}		
	}
	
	
	public DataField getDataField(int colIndex, int offset) {
		switch (datatype[colIndex].getBasicType()) {
		case SMALL_INT:
			return SmallIntField.getFieldFromBinary(this.buff, offset);
		case  INT:
			return IntField.getFieldFromBinary(this.buff, offset);
		case BIG_INT:
			return BigIntField.getFieldFromBinary(this.buff, offset);
		case FLOAT:
			return FloatField.getFieldFromBinary(this.buff, offset);
		case DOUBLE:
			return DoubleField.getFieldFromBinary(this.buff, offset);
		case CHAR:
			return CharField.getFieldFromBinary(this.buff, offset, this.datatype[colIndex].getNumberOfBytes());
		case DATE:
			return DateField.getFieldFromBinary(this.buff, offset);
		case TIME:
			return TimeField.getFieldFromBinary(this.buff, offset);
		case TIMESTAMP:
			return TimestampField.getFieldFromBinary(this.buff, offset);
		case VAR_CHAR:
			int start = IntField.getIntFromBinary(this.buff, offset);
			int length = IntField.getIntFromBinary(this.buff, offset + 4);
			if (length == -1)
				return DataType.varcharType(0).getNullValue();
			return VarcharField.getFieldFromBinary(this.buff, start, length);
		case RID:
			return RID.getRidFromBinary(this.buff, offset);
		default:
			return null;
		}
	}
	
	public DataTuple getDataTuple(int position, long columnBitmap, int numCols) throws PageTupleAccessException, PageExpiredException {
		if (this.expired == true)
			throw new PageExpiredException();
		else if (position < 0 || position >= this.numRecordsOnPage)
			throw new PageTupleAccessException(position);
		else {
			DataTuple t = new DataTuple(numCols);
			int offset = 32 + position * this.recordWidth;
			if (buff[offset] == 1)
				return null;
			offset +=4;
			int n = 0;
			for (int i = 0; i < numCols & columnBitmap != 0; columnBitmap >>>=1){
				if (columnBitmap == 0 || n >= this.numberOfColumn)
					throw new PageTupleAccessException(position);
				
				if ((columnBitmap & 0x1) != 0) {
					t.assignDataField(getDataField(n, offset), i);
					i++;
				}
				
				if (this.datatype[n].getBasicType() != BasicType.VAR_CHAR)
					offset = offset + this.datatype[n].getNumberOfBytes();
				else
					offset +=8;
				n++;
			}
			return t;
		}
	}

	public DataTuple getDataTuple(LowLevelPredicate[] preds, int position, long columnBitmap, int numCols) throws PageTupleAccessException, PageExpiredException{
		if (this.expired == true)
			throw new PageExpiredException();
		else {
			int n = 0;
			int offset = 32 + position * this.recordWidth + 4;
			for (int i = 0; i < preds.length; i++) {
				int colIndex = preds[i].getColumnIndex();
				while (n < colIndex) {
					if (this.datatype[n].getBasicType() != BasicType.VAR_CHAR)
						offset = offset + this.datatype[n].getNumberOfBytes();
					else
						offset +=8;				
					n++;
				}
				DataField field = getDataField(colIndex, offset);
				if (preds[i].evaluateWithNull(field) == false)
					return null;
			}
			return getDataTuple(position, columnBitmap, numCols);
		}
		
	}
	
	public TupleIterator getIterator(int numCols, long columnBitmap) throws PageTupleAccessException, PageExpiredException {
		if (this.expired == true)
			throw new PageExpiredException();
		else {
			ImpTupleIterator t = new ImpTupleIterator();
			ImpTupleIterator temp = t;
			for (int i = 0; i < this.numRecordsOnPage; i++) {
				DataTuple dataTemp = getDataTuple(i, columnBitmap, numCols);
				if (dataTemp != null){
					t.setNext(new ImpTupleIterator(dataTemp));
					t = t.getNextIterator();
				}
			}
			return temp;
		}	
	}
	
	public TupleIterator getIterator(LowLevelPredicate[] preds, int numCols, long columnBitmap) throws PageTupleAccessException, PageExpiredException{
		if (this.expired == true)
			throw new PageExpiredException();
		else {
			ImpTupleIterator t = new ImpTupleIterator();
			ImpTupleIterator temp = t;
			for (int i = 0; i < this.numRecordsOnPage; i++) {
				DataTuple dataTemp = getDataTuple(preds, i, columnBitmap, numCols);
				if (dataTemp != null){
					t.setNext(new ImpTupleIterator(dataTemp));
					t = t.getNextIterator();
				}
			}
			return temp;
		}
	}
	
	
	public TupleRIDIterator getIteratorWithRID() throws PageTupleAccessException, PageExpiredException{
		if (this.expired == true)
			throw new PageExpiredException();
		else {
			ImpTupleRIDIterator t = new ImpTupleRIDIterator();
			ImpTupleRIDIterator temp = t;
			for (int i = 0; i < this.numRecordsOnPage; i++) {
				DataTuple dataTemp = getDataTuple(i, (1 << this.numberOfColumn)  - 1, this.numberOfColumn);
				if (dataTemp != null) {
					Pair<DataTuple, RID> data= new Pair<DataTuple, RID>(dataTemp, new RID(this.pageNumber, i));
					t.setNext(new ImpTupleRIDIterator(data));
					t = t.getNextRIDIterator();
				}
			}
			return temp;
		}	
	}
	
	public boolean hasBeenModified() throws PageExpiredException{
		if (this.expired == true)
			throw new PageExpiredException();
		else
			return this.modified;
	}
	
	public void markExpired() {
		this.expired = true;
	}
		
	public boolean isExpired() {
		return this.expired;
	}
	
	public byte[] getBuffer(){
		return buff;
	}
}
