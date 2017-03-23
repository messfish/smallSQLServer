package Sorting;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import Evaluator.Evaluator;
import PhysicalOperators.Operator;
import SQLExpression.Expression;
import SmallSQLServer.Main;
import Support.Mule;
import TableElement.DataType;
import TableElement.Tuple;

/** 
 * This class mainly holds the external sort operation.
 * The logic of the sort will be displayed in the methods below.
 * @author messfish
 *
 */
public class ExternalSort {

	private static final int NUM_OF_BUFFER = 100;
	private static final int NUM_OF_BYTES = 16384;
	private int file_index = 1;
	private Tuple start = null;
	private Map<String, Mule> schema;
	private List<Expression> attributeslist;
	private File result; // this will be used to store the result.
	private Operator op;
	
	/**
	 * Constructor: this constructor is used to fetch all the tuples
	 * in an operator and store them in pages. After that, it would 
	 * perform these two types of sorting: (1) First it stores the 
	 * pages in the buffer poll and sort them and put them in one 
	 * file. (2) Next, use all but one pages in the buffer pool to 
	 * store the files. Pick the smallest of them and write that into
	 * the empty slot. Note we need to do everything in a single page.
	 * Do this iteratively until only one large file left. Pass that 
	 * file to the global file operator.
	 * @param op the operator that calls this class.
	 * @param attributeslist the list of attributes that used for sorting.
	 * @param ID this marks the ID of the operator who are calling this class.
	 */
	public ExternalSort(Operator op, List<Expression> attributeslist, int ID) {
		this.op = op;
		schema = op.getSchema();
		this.attributeslist = attributeslist;
		/* At first, we build the base of the sorting file. */
		File file = null;
		while(true) {
			file = writeBase(op, ID);
			if(file == null)
				break;
			file_index++;
		}
		/* if the file index is 1, that means there are no tuples available.
		 * do nothing and let the result be a null pointer. */
		/* this indicates all the tuples are sorted during the first step,
		 * so we simply assign the file to the result file. */
		if(file_index == 2) {
			result = file;
		}else if(file_index > 2) {
			merge(ID);
		}
	}
	
	/**
	 * This is the getter method of the result file.
	 * @return the result file.
	 */
	public File getResult() {
		return result;
	}
	
	/**
	 * This method is used to write the base of the file. Get all the tuples
	 * available from the operator and store them in the file.
	 * @param op the operator needed for sorting.
	 * @param ID the ID to identify the table.
	 * @return the file that contains sorted data.
	 */
	private File writeBase(Operator op, int ID) {
		List<Tuple> list = new ArrayList<>();
		if(start != null)
			list.add(start);
		Tuple tuple = null;
		for(int i=0;i<NUM_OF_BUFFER;i++)
			tuple = storePage(op, tuple, list);
		/* this indicates no more tuples left, simply return null. */
		if(tuple == null) return null;
		else start = tuple;
		Collections.sort(list, new Comparator<Tuple>(){
			@Override
			public int compare(Tuple t1, Tuple t2) {
				return comparison(t1, t2);
			}
		});
		File file = new File(Main.getTemp() + ID + " " + file_index);
		file_index++;
		try{
			FileOutputStream output = new FileOutputStream(file);
			FileChannel fc = output.getChannel();
			ByteBuffer buffer = null;
			int start = 0;
			while((buffer=writePage(list, start))!=null) {
				start += buffer.getInt(0);
				buffer.limit(buffer.capacity());
				buffer.position(0);
				fc.write(buffer);
			}
			fc.write(buffer);
			output.close();
		}catch (Exception e) {
			e.printStackTrace();
		}
		return file;
	}
	
	/**
	 * This method is used for storing the tuple into a tuple list. Notice the 
	 * amount of increment should not exceed the size of a single page. Also,
	 * since there are no needs to get the tuple ID and the byte to indicate
	 * the state of the tuple. We could simply leave them out.
	 * @param op the operator to extract the tuple.
	 * @param tuple might stores the tuple that 
	 * @param list the list that stores the tuples.
	 * @return tuple the next tuple that cannot fit into the page.
	 */
	private Tuple storePage(Operator op, Tuple tuple, List<Tuple> list) {
		int index = 4;
		if(tuple!=null) {
			list.add(tuple);
			index += checkSize(tuple);
		}
		while((tuple=op.getNextTuple())!=null) {
			index += checkSize(tuple);
			if(index > NUM_OF_BYTES)
				return tuple;
			list.add(tuple);
		}
		/* when we meet this code, that means there are no tuples left
		 * in the operator, so we simply return null. */ 
		return null;
	}
	
	/**
	 * This method is mainly used for checking how many bytes it need to 
	 * store the whole tuple.
	 * @param tuple the tuple that will be used for checking.
	 * @return the number of bytes to store the tuple.
	 */
	private int checkSize(Tuple tuple) {
		int size = 0;
		for(int i=0; i<tuple.datasize();i++) {
			DataType data = tuple.getData(i);
			if(data.getType()==1) 
				size += 8;
			/* we need a byte to identify the length of the string. */
			else if(data.getType()==2)
				size += data.getString().length() + 1;
			else if(data.getType()==5)
				size += 8;
		}
		return size;
	}
	
	/**
	 * This method is mainly used for comparing two different tuples
	 * by using the schema and the attribute list. Note if we cannot
	 * tell apart from the attributes list, we use the rest of the 
	 * attributes to pull them apart. Return 0 if we find these two
	 * tuples are actually equal.
	 * @param t1 one of the tuples to be compared.
	 * @param t2 one of the tuples to be compared.
	 * @return an integer to show which is bigger, 1 means t1 is bigger
	 * than t2, -1 means t1 is smaller than t2. 0 means they are equally
	 * the same.
	 */
	private int comparison(Tuple t1, Tuple t2) {
		for(int i=0;i<attributeslist.size();i++) {
			Expression exp = attributeslist.get(i);
			Evaluator eva1 = new Evaluator(t1, exp, schema);
			DataType data1 = eva1.getData();
			Evaluator eva2 = new Evaluator(t2, exp, schema);
			DataType data2 = eva2.getData();
			if(data1.compare(data2)!=0)
				return data1.compare(data2);
		}
		for(int i=0;i<t1.datasize();i++){
			DataType data1 = t1.getData(i);
			DataType data2 = t2.getData(i);
			if(data1.compare(data2)!=0)
				return data1.compare(data2);
		}
		return 0;
	}
	
	/**
	 * This method is used for writing the tuples as bytes in the byte
	 * buffer. We start the tuple from the starting point and moves on
	 * until there is not enough room for the tuple at the current index.
	 * @param list the list of Tuples in the 
	 * @param start
	 * @return
	 */
	private ByteBuffer writePage(List<Tuple> list, int start) {
		if(list.size()==start)
			return null;
		ByteBuffer buffer = ByteBuffer.allocate(NUM_OF_BYTES);
		int temp = start, index = 4;
		while(start<list.size()) {
			Tuple tuple = list.get(start);
			index += checkSize(tuple);
			if(index > NUM_OF_BYTES) 
				break;
			for(int i=0;i<tuple.datasize();i++) {
				DataType data = tuple.getData(i);
				if(data.getType()==1) {
					long number = data.getLong();
					buffer.putLong(index, number);
					index += 8;
				}else if(data.getType()==2) {
					String s = data.getString();
					buffer.put(index, (byte)s.length());
					index++;
					for(char c : s.toCharArray()) {
						buffer.put(index, (byte)c);
						index++;
					}
				}else if(data.getType()==5) {
					double number = data.getDouble();
					buffer.putDouble(index, number);
					index += 8;
				}
			}
			start++;
		}
		buffer.putInt(0, start - temp);
		return buffer;
	}
	
	/**
	 * This is the second part of the external sort: bring the files
	 * to fill up all but one slots in the buffer page. Pick the 
	 * smallest tuple and store that into the empty slot. In other words,
	 * merge the file array into one file.
	 * @param ID the ID that used to identify the operator which calls this class.
	 */
	private void merge(int ID) {
		int current = 1;
		/* only when we get all the data in one file can we break out
		 * from the condition. */
		while(file_index - current > 1) {
			int limit = file_index;
			while(current < limit) {
				TempOperator[] temparray = new TempOperator[NUM_OF_BUFFER-1];
				int numofoperators = 0;
				for(;numofoperators<NUM_OF_BUFFER - 1&&current<limit;
						numofoperators++) {
					String filelocation = Main.getTemp() + ID + " " + current;
					File file = new File(filelocation);
					temparray[numofoperators] = new TempOperator(file, op);
					current++;
				}
				PriorityQueue<HeapData> pq = new PriorityQueue<>
						((a,b)->comparison(a.getTuple(),b.getTuple()));
				for(int i=0;i<numofoperators;i++) 
					pq.offer(new HeapData(i, temparray[i].getNextTuple()));
				writeFile(pq,temparray,ID);
				file_index++;
			}
		}
	}
	
	/**
	 * This method is mainly used for writing the data into the file.
	 * @param pq the priority queue to fetch the minimum data out.
	 * @param temparray the array of temp operators.
	 * @param ID the ID of the operators using this class.
	 */
	private void writeFile(PriorityQueue<HeapData> pq, 
						   TempOperator[] temparray, int ID) {
		File file = new File(Main.getTemp() + ID + " " + file_index);
		try {
			FileOutputStream out = new FileOutputStream(file);
			FileChannel fc = out.getChannel();
			out.close();
			List<Tuple> temp = new ArrayList<>();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
}