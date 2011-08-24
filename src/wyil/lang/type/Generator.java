package wyil.lang.type;

import java.util.*;
import wyil.lang.*;

/**
 * The generator class is used generate lists of types, primarily for testing
 * purposes.
 * 
 * @author djp
 * 
 */
public class Generator {

	public static class Config {
		/**
		 * MAX_FIELDS defines the maximum number of fields to explore per
		 * record.
		 */
		public int MAX_FIELDS;

		/**
		 * MAX_UNIONS defines the maximum number of disjuncts in a union that
		 * can be explored.
		 */
		public int MAX_UNIONS;
		
		/**
		 * MAX_TUPLES defines the maximum number of elements in a tuple that
		 * can be explored.
		 */
		public int MAX_TUPLES;
		
		/**
		 * MAX_DEPTH defines the maximum depth of a type.
		 */
		public int MAX_DEPTH;			
	}
	
	public static List<Type> generate(Config config) {
		ArrayList<Type> types = new ArrayList<Type>();
		// types.add(Type.T_VOID);
		types.add(Type.T_ANY);
		types.add(Type.T_NULL);
		types.add(Type.T_BYTE);
		types.add(Type.T_CHAR);
		types.add(Type.T_INT);
		types.add(Type.T_REAL);
		types.add(Type.T_STRING);
		
		for(int i=0;i!=config.MAX_DEPTH;++i) {
			int end = types.size();
			addListTypes(types,end);
			addSetTypes(types,end);
			addProcessTypes(types,end);
			addTupleTypes(types,config.MAX_TUPLES,end);
			addRecordTypes(types,config.MAX_FIELDS,end);
		}
		return types;
	}
	
	private static void addListTypes(ArrayList<Type> types, int end) {
		for(int i=0;i!=end;++i) {
			Type t = types.get(i);
			types.add(Type.T_LIST(t));
		}
	}
	
	private static void addSetTypes(ArrayList<Type> types, int end) {
		for(int i=0;i!=end;++i) {
			Type t = types.get(i);
			types.add(Type.T_SET(t));
		}
	}
	
	private static void addProcessTypes(ArrayList<Type> types, int end) {
		for(int i=0;i!=end;++i) {
			Type t = types.get(i);
			types.add(Type.T_PROCESS(t));
		}
	}
	
	private static void addTupleTypes(ArrayList<Type> types, int MAX_TUPLES, int end) {
		for(int i=2;i<=MAX_TUPLES;++i) {
			Type[] elems = new Type[i];
			addTupleTypes(elems,types,0,end);			
		}
	}
	
	private static void addTupleTypes(Type[] elems, ArrayList<Type> types, int dim, int end) {
		if(dim == elems.length) {
			types.add(Type.T_TUPLE(elems));
		} else {
			for(int i=0;i!=end;++i) {
				elems[dim] = types.get(i);
				addTupleTypes(elems,types,dim+1,end);
			}
		}
	}
	
	private static void addRecordTypes(ArrayList<Type> types, int MAX_FIELDS, int end) {
		for(int i=1;i<=MAX_FIELDS;++i) {
			Type[] elems = new Type[i];
			addRecordTypes(elems,types,0,end);			
		}
	}
	
	private static void addRecordTypes(Type[] elems, ArrayList<Type> types, int dim, int end) {
		if(dim == elems.length) {
			HashMap<String,Type> fields = new HashMap<String,Type>();
			for(int i=0;i!=elems.length;++i) {
				fields.put("field" + i,elems[i]);
			}
			types.add(Type.T_RECORD(fields));
		} else {
			for(int i=0;i!=end;++i) {
				elems[dim] = types.get(i);
				addRecordTypes(elems,types,dim+1,end);
			}
		}
	}
	
	public static void main(String[] args) {
		Config config = new Config();
		config.MAX_FIELDS = 2;
		config.MAX_UNIONS = 2;
		config.MAX_TUPLES = 2;
		config.MAX_DEPTH = 2;
		
		List<Type> types = generate(config);
		for(Type t : types) {
			System.out.println(t);
		}
		
		System.out.println("Generated " + types.size() + " types");
	}
}
