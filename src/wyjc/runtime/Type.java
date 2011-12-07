package wyjc.runtime;

import java.math.BigInteger;
import java.util.*;

import static wyil.lang.Type.K_VOID;
import static wyil.lang.Type.K_ANY;
import static wyil.lang.Type.K_META;
import static wyil.lang.Type.K_NULL;
import static wyil.lang.Type.K_BOOL;
import static wyil.lang.Type.K_BYTE;
import static wyil.lang.Type.K_CHAR;
import static wyil.lang.Type.K_INT;
import static wyil.lang.Type.K_RATIONAL;
import static wyil.lang.Type.K_STRING;
import static wyil.lang.Type.K_TUPLE;
import static wyil.lang.Type.K_SET;
import static wyil.lang.Type.K_LIST;
import static wyil.lang.Type.K_DICTIONARY;
import static wyil.lang.Type.K_PROCESS;
import static wyil.lang.Type.K_PROCESS;
import static wyil.lang.Type.K_RECORD;
import static wyil.lang.Type.K_UNION;
import static wyil.lang.Type.K_NEGATION;
import static wyil.lang.Type.K_FUNCTION;
import static wyil.lang.Type.K_EXISTENTIAL;
import static wyil.lang.Type.K_LABEL;

public class Type {
	
	public final int kind;
	public String str;
	
	private Type(int kind, String str) {
		this.kind = kind;
		this.str = str;
	}
	
	public String toString() {
		return str;
	}
	
	public static final Void VOID = new Void();
	public static final Any ANY = new Any();
	public static final Null NULL = new Null();
	public static final Bool BOOL = new Bool();
	public static final Byte BYTE = new Byte();
	public static final Char CHAR = new Char();
	public static final Integer INT = new Integer();
	public static final Rational REAL = new Rational();
	public static final Strung STRING = new Strung();
	
	private static final class Void extends Type { Void() {super(K_VOID, "void");}}
	private static final class Any extends Type { Any() {super(K_ANY, "any");}}
	private static final class Null extends Type { Null() {super(K_NULL, "null");}}
	private static final class Bool extends Type { Bool() {super(K_BOOL, "bool");}}
	private static final class Byte extends Type { Byte() {super(K_BYTE, "byte");}}
	private static final class Char extends Type { Char() {super(K_CHAR, "char");}}
	private static final class Integer extends Type { Integer() {super(K_INT, "int");}}
	private static final class Rational extends Type { Rational() {super(K_RATIONAL, "real");}}
	private static final class Strung extends Type { Strung() {super(K_STRING, "string");}}
		
	public static final class List extends Type {
		public Type element;
		
		public List(Type element, String str) {
			super(K_LIST,str);
			this.element = element;
		}
	}
	
	public static final class Set extends Type {
		public Type element;
		
		public Set(Type element, String str) {
			super(K_SET, str);
			this.element = element;
		}
	}
	
	public static final class Dictionary extends Type {
		public Type key;
		public Type value;
		
		public Dictionary(Type key, Type value, String str) {
			super(K_DICTIONARY, str);
			this.key = key;
			this.value = value;
		}
	}
	
	public static final class Record extends Type {
		public final String[] names;
		public final Type[] types;
		public Record(String[] names, Type[] types, String str) {
			super(K_RECORD, str);
			this.names = names;
			this.types = types;
		}
	}
	
	public static final class Tuple extends Type {		
		public final Type[] types;
		public Tuple(Type[] types, String str) {
			super(K_TUPLE, str);			
			this.types = types;
		}
	}
	
	public static final class Union extends Type {
		public final Type[] bounds;		
		public Union(String str, Type... bounds) {
			super(K_UNION,str);
			this.bounds = bounds;
		}
	}
	
	private static final class Leaf extends Type {
		public final String name;
		public Leaf(String name) {
			super(K_LABEL,name);
			this.name = name;
		}
	}
	
	public static final class Negation extends Type {
		public Type element;
		
		public Negation(Type element, String str) {
			super(K_NEGATION,str);
			this.element = element;
		}
	}	
	
	public static Type valueOf(String str) {
		return new TypeParser(str).parse(new HashSet<String>());
	}

	/**
	 * <p>
	 * This method connects up recursive links in a given type. In particular,
	 * it replaces all occurrences of variable <code>var</code> with
	 * <code>root</code>.
	 * </p>
	 * 
	 * <b>NOTE:</b> the resulting type may contain a cycle. For this reason, the
	 * visited relation is required to ensure termination in the presence of
	 * such cycles.
	 * 
	 * @param type
	 *            - The type currently be explored
	 * @param var
	 *            - The variable to substitute for
	 * @param root
	 *            - The root of the recursive type. Variable <code>var</code>
	 *            will be replaced with this.
	 * @param visited
	 *            - contains all of the visited nodes. This is needed to ensure
	 *            termination in the presence of cycles.
	 * @return
	 */
	private static Type substitute(Type type, String var, Type root, HashSet<Type> visited) {
		if(visited.contains(type)) {
			return type;
		} else {
			visited.add(type);
		}
		switch(type.kind) {
			case K_ANY:				
			case K_VOID:				
			case K_NULL:				
			case K_INT:				
			case K_RATIONAL:				
			case K_STRING:
				break;
			case K_LABEL:
			{
				Type.Leaf leaf = (Type.Leaf)type;
				if(leaf.name.equals(var)) {
					return root;
				} else {
					return leaf;
				}				
			}
			case K_LIST:
			{
				Type.List list = (Type.List) type;
				list.element = substitute(list.element,var,root,visited); 
				break;
			}
			case K_SET:
			{
				Type.Set set = (Type.Set) type;
				set.element = substitute(set.element,var,root,visited); 
				break;
			}
			case K_DICTIONARY:
			{
				Type.Dictionary dict = (Type.Dictionary) type;
				dict.key = substitute(dict.key,var,root,visited); 
				dict.value = substitute(dict.value,var,root,visited);
				break;
			}
			case K_RECORD:
			{
				Type.Record rec = (Type.Record) type;
				Type[] types = rec.types;
				for(int i=0;i!=types.length;++i) {
					types[i] = substitute(types[i],var,root,visited);
				}
				break;
			}
			case K_NEGATION:
			{
				Type.Negation not = (Type.Negation) type;
				not.element = substitute(not.element,var,root,visited); 
				break;
			}
			case K_UNION:
			{
				Type.Union un = (Type.Union) type;
				Type[] types = un.bounds;
				for(int i=0;i!=types.length;++i) {
					types[i] = substitute(types[i],var,root,visited);
				}
				break;
			}
		}			
		return type;
	}
	
	private static final class TypeParser {
		private int index;
		private String str;
		public TypeParser(String str) { 
			this.str = str;
		}
		public Type parse(HashSet<String> typeVars) {
			int start = index;
			
			skipWhiteSpace();
			ArrayList<Type> terms = new ArrayList();
			terms.add(parseTerm(typeVars));
			while(index < str.length() && str.charAt(index) == '|') {
				// union type
				match("|");
				terms.add(parse(typeVars));				
				skipWhiteSpace();
			}
			if(terms.size() == 1) {
				return terms.get(0);
			} else {				
				return new Union(str.substring(start,index),terms.toArray(new Type[terms.size()]));				
			}
		}
		public Type parseTerm(HashSet<String> typeVars) {
			skipWhiteSpace();
			char lookahead = str.charAt(index);
			int start = index;
			
			switch (lookahead) {
			case 'a':
				match("any");
				return ANY;
			case 'v':
				match("void");
				return VOID;
			case 'n':
				match("null");
				return NULL;
			case 'b':
				if(str.charAt(index+1) == 'o') {
					match("bool");
					return BOOL;
				} else {
					match("byte");
					return BYTE;
				}				
			case 'c':
				match("char");
				return CHAR;
			case 'i':
				match("int");
				return INT;
			case 'r':
				match("real");
				return REAL;
			case 's':
				match("string");
				return STRING;
			case '[':
			{				
				match("[");
				Type elem = parse(typeVars);
				match("]");
				return new List(elem, str.substring(start,index));
			}
			case '(':
			{				
				match("(");
				ArrayList<Type> elems = new ArrayList<Type>();
				elems.add(parse(typeVars));
				while(index < str.length() && str.charAt(index) == ',') {
					match(",");
					elems.add(parse(typeVars));
				}
				match(")");
				return new Tuple(elems.toArray(new Type[elems.size()]),
						str.substring(start, index));
			}
			case '{':
			{
				match("{");
				Type elem = parse(typeVars);
				skipWhiteSpace();
				if(index < str.length() && str.charAt(index) == '-') {
					// dictionary
					match("->");
					Type value = parse(typeVars);
					match("}");
					return new Dictionary(elem,value, str.substring(start,index));
				} else if(index < str.length() && str.charAt(index) != '}') {
					// record
					HashMap<String,Type> fields = new HashMap<String,Type>();					
					String id = parseIdentifier();
					fields.put(id, elem);
					skipWhiteSpace();
					while(index < str.length() && str.charAt(index) == ',') {
						match(",");
						elem = parse(typeVars);
						id = parseIdentifier();
						fields.put(id, elem);
						skipWhiteSpace();
					}
					match("}");
					
					String[] names = new String[fields.size()];
					Type[] types = new Type[fields.size()];
					ArrayList<String> tmp = new ArrayList<String>(fields.keySet());
					Collections.sort(tmp);
					for(int i=0;i!=names.length;++i) {
						String name = tmp.get(i); 
						names[i] = name;
						types[i] = fields.get(name);
					}
										
					return new Record(names,types, str.substring(start,index));					
				}
				match("}");
				return new Set(elem, str.substring(start,index));
			}
			case '!': {
				match("!");
				Type elem = parse(typeVars);
				return new Negation(elem,str.substring(start, index));
			}				
			default:
			{
				// this case is either a syntax error, or it's a recursive type.
				String var = parseIdentifier();
				
				if(typeVars.contains(var)) {
					return new Leaf(var);
				} else {
					typeVars = new HashSet<String>(typeVars);
					typeVars.add(var);
					match("<");
					Type t = parse(typeVars);
					match(">");
					t.str = str.substring(start,index);
					return substitute(t,var,t, new HashSet<Type>());
				}				
			}
			}
		}
		private String parseIdentifier() {
			skipWhiteSpace();
			int start = index;
			while (index < str.length()
					&& Character.isJavaIdentifierPart(str.charAt(index))) {
				index++;
			}
			return str.substring(start,index);
		}
		private void skipWhiteSpace() {
			while (index < str.length()
					&& Character.isWhitespace(str.charAt(index))) {
				index++;
			}
		}		

		private void match(String match) {
			skipWhiteSpace();
			if ((str.length() - index) < match.length()
					|| !str.startsWith(match, index)) {
				String failed = str.substring(index, index + match.length());
				throw new IllegalArgumentException(
						"invalid type string (expected " + match + ", found "
								+ failed + "): " + str);
			}
			index += match.length();
		}		
	}
	
}
