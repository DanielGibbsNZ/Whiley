// Copyright (c) 2011, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//    * Redistributions of source code must retain the above copyright
//      notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above copyright
//      notice, this list of conditions and the following disclaimer in the
//      documentation and/or other materials provided with the distribution.
//    * Neither the name of the <organization> nor the
//      names of its contributors may be used to endorse or promote products
//      derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyil.util.type;

import static wyil.lang.Type.K_ANY;
import static wyil.lang.Type.K_UNION;
import static wyil.lang.Type.K_VOID;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;

import wyautl.lang.*;

import wyil.lang.NameID;
import wyil.lang.Type;

/**
 * Contains various important algorithms for manipulating types. These were
 * split out from wyil.lang.Type, as that class is already quite large. The most
 * important algorithms contained in this class are:
 * <ul>
 * <li>
 * <p>
 * <b>Type Intersection</b>. Here, the intersection of two types is calculated.
 * This is necessary for determining the updated type after a runtime type test.
 * Essentially, we intersect the original type with the tested type to produce
 * something new.
 * </p>
 * </li>
 * <li>
 * <p>
 * <b>Type Simplification</b>. Here, a number of simplification rules are
 * provided which reduce non-sensical forms into something sensible. For
 * example, <code>int|int</code> is a non-sensical form, and should be
 * simplified to just <code>int</code>.
 * </p>
 * </li>
 * </ul>
 * <b>NOTE:</b> the algorithms contained in this file are ugly and difficult to
 * follow. Do not touch them unless you absolutely know what you're doing!
 * 
 * @author David J. Pearce
 * 
 */
public final class TypeAlgorithms {

	/**
	 * The data comparator is used in the type canonicalisation process. It is
	 * used to compare the supplementary data of states in automata
	 * representing types. Supplementary data is used for record kinds and 
	 */
	public static final Comparator<Automaton.State> DATA_COMPARATOR = new Comparator<Automaton.State>() {
		public int compare(Automaton.State s1, Automaton.State s2) {
			// PRE-CONDITION s1.kind == s2.kind
			if(s1.kind == Type.K_RECORD) {
				Type.Record.State fields1 = (Type.Record.State) s1.data;
				Type.Record.State fields2 = (Type.Record.State) s2.data;
				int fields1_size = fields1.size();
				int fields2_size = fields2.size();
				if (fields1_size < fields2_size
						|| (!fields1.isOpen && fields2.isOpen)) {
					return -1;
				} else if (fields1_size > fields2_size
						|| (fields1.isOpen && !fields2.isOpen)) {
					return 1;
				}
				// ASSERT: fields1_size == fields2_size
				for(int i=0;i!=fields1_size;++i) {
					String str1 = fields1.get(i);
					String str2 = fields2.get(i);
					int c = str1.compareTo(str2);
					if(c != 0) {
						return c;
					}
				}
				return 0;
			} else if(s1.kind == Type.K_NOMINAL){
				NameID nid1 = (NameID) s1.data;
				NameID nid2 = (NameID) s2.data;
				return nid1.toString().compareTo(nid2.toString());
			} else if(s1.kind == Type.K_LIST || s1.kind == Type.K_SET){
				Boolean nid1 = (Boolean) s1.data;
				Boolean nid2 = (Boolean) s2.data;				
				return nid1.toString().compareTo(nid2.toString());
			} else {
				String str1 = (String) s1.data;
				String str2 = (String) s2.data;
				return str1.compareTo(str2);
			}
		}
	};
	
	/**
	 * <p>
	 * Contractive types are types which cannot accept value because they have
	 * an <i>unterminated cycle</i>. An unterminated cycle has no leaf nodes
	 * terminating it. For example, <code>X<{X field}></code> is contractive,
	 * where as <code>X<{null|X field}></code> is not.
	 * </p>
	 * 
	 * <p>
	 * This method returns true if the type is contractive, or contains a
	 * contractive subcomponent. For example, <code>null|X<{X field}></code> is
	 * considered contracted.
	 * </p>
	 * 
	 * @param type --- type to test for contractivity.
	 * @return
	 */
	public static boolean isContractive(Automaton automaton) {		
		BitSet contractives = new BitSet(automaton.size());
		// TODO: optimise away the need to initialise the contractives
		contractives.set(0,automaton.size(),true);
		// initially all nodes are considered contracive.
		return findContractives(automaton,contractives);
	}
	
	private static boolean findContractives(Automaton automaton, BitSet contractives) {		
		boolean changed = true;
		boolean contractive = false;
		while(changed) {
			changed=false;
			contractive = false;
			for(int i=0;i!=automaton.size();++i) {
				boolean oldVal = contractives.get(i);
				boolean newVal = isContractive(i,contractives,automaton);
				if(oldVal && !newVal) {
					contractives.set(i,newVal);
					changed = true;
				}
				contractive |= newVal;
			}
		}
		return contractive;
	}
	
	private static boolean isContractive(int index, BitSet contractives,
			Automaton automaton) {
		Automaton.State state = automaton.states[index];
		int[] children = state.children;
		if(children.length == 0) {
			return false;
		}
		if(state.deterministic) {
			for(int child : children) {
				if(child == index || contractives.get(child)) {
					return true;
				}
			}
			return false;
		} else {			
			boolean r = true;
			for(int child : children) {				
				if(child == index) { 
					return true;
				}
				r &= contractives.get(child);									
			}
			return r;
		}
	}
	
	/**
	 * <p>
	 * This simplification rule removes spurious components by repeated
	 * application of various rewrite rules. The following provides
	 * representatives of the main rules considered.
	 * </p>
	 * <ul>
	 * <li><code>{void f}</code> => <code>void</code>.</li>
	 * <li><code>T | void</code> => <code>T</code>.</li>
	 * <li><code>T | any</code> => <code>any</code>.</li>
	 * <li><code>X<T | X></code> => <code>T</code>.</li>
	 * <li><code>!!T</code> => <code>T</code>.</li>
	 * <li><code>!any</code> => <code>void</code>.</li>
	 * <li><code>!void</code> => <code>any</code>.</li>
	 * <li><code>!(T_1 | T_2)</code> => <code>!T_1 & !T_2</code>.</li>
	 * <li><code>(T_1 | T_2) | T_3</code> => <code>(T_1 | T_2 | T_3)</code>.</li>
	 * <li><code>T_1 | T_2</code> where <code>T_1 :> T_2</code> =>
	 * <code>T_1</code>.
	 * <code>void</code>.
	 * </ul>
	 * <p>
	 * <b>NOTE:</b> applications of this rewrite rule may leave states which are
	 * unreachable from the root. Therefore, the resulting automaton should be
	 * extracted after rewriting to eliminate such states.
	 * </p>
	 * 
	 */
	public static void simplify(Automaton automaton) {		
		boolean changed = true;
		while(changed) {				
			changed = false;			
			changed |= simplifyContractives(automaton);						
			for(int i=0;i!=automaton.size();++i) {				
				changed |= simplify(i,automaton);				
			}			
		}	
	}	
	
	private static boolean simplifyContractives(Automaton automaton) {
		BitSet contractives = new BitSet(automaton.size());
		// initially all nodes are considered contractive.
		// TODO: optimise away the need to initialise the contractives
		contractives.set(0,automaton.size(),true);
		boolean changed = findContractives(automaton, contractives);

		for (int i = contractives.nextSetBit(0); i >= 0; i = contractives
				.nextSetBit(i + 1)) {
			automaton.states[i] = new Automaton.State(Type.K_VOID);
		}

		return changed;
	}
	
	private static boolean simplify(int index, Automaton automaton) {		
		Automaton.State state = automaton.states[index];
		boolean changed=false;
		switch (state.kind) {
		case Type.K_NEGATION:			
			changed = simplifyNegation(index, state, automaton);
			break;
		case Type.K_UNION :			
			changed = simplifyUnion(index, state, automaton);			
			break;		
		case Type.K_LIST:
		case Type.K_SET:
			// for list and set types, we want to simplify the following cases:
			// [void+] => void
			// {void+} => void
			boolean nonEmpty = (Boolean) state.data;
			if(!nonEmpty) {
				// type of form [T], so ignore
				break;
			}
			// type of form [T+] so simplify like other compounds.
		case Type.K_RECORD:
		case Type.K_TUPLE:
		case Type.K_FUNCTION:
		case Type.K_METHOD:
		case Type.K_HEADLESS:			
			changed = simplifyCompound(index, state, automaton);			
			break;
		}				
		return changed;
	}
	
	private static boolean simplifyNegation(int index, Automaton.State state, Automaton automaton) {
		Automaton.State child = automaton.states[state.children[0]];
		if(child.kind == Type.K_NEGATION) {
			// bypass node
			Automaton.State childchild = automaton.states[child.children[0]];
			automaton.states[index] = new Automaton.State(childchild);
			return true;
		}
		return false;
	}
	
	private static boolean simplifyCompound(int index, Automaton.State state, Automaton automaton) {		
		int kind = state.kind;
		int[] children = state.children;
		boolean isOpenRecord = false;
		if(kind == Type.K_RECORD) {
			Type.Record.State data = (Type.Record.State) state.data;
			isOpenRecord = data.isOpen;
		}
		
		for(int i=0;i<children.length;++i) {
			if ((i == 0 || i == 1) && (kind == Type.K_HEADLESS || kind == Type.K_FUNCTION)) {
				// headless method and function return or throws type allowed to be void
				continue;
			} else if((i == 1 || i == 2) && kind == Type.K_METHOD) {
				// method return or throws type allowed to be void
				continue;
			} 
			Automaton.State child = automaton.states[children[i]];
			if(child.kind == Type.K_VOID && !isOpenRecord) {				
				automaton.states[index] = new Automaton.State(Type.K_VOID);
				return true;
			}
		}
		
		return false;
	}
		
	/**
	 * This method is responsible for the following rewrite rules:
	 * <ul>
	 * <li><code>T | void</code> => <code>T</code>.</li>
	 * <li><code>T | any</code> => <code>any</code>.</li>
	 * <li><code>X<T | X></code> => <code>T</code>.</li>
	 * <li><code>(T_1 | T_2) | T_3</code> => <code>(T_1 | T_2 | T_3)</code>.</li>
	 * <li><code>T_1 | T_2</code> where <code>T_1 :> T_2</code> => <code>T_1</code>.
	 * </ul>
	 * 
	 * @param index
	 *            --- index of state being worked on.
	 * @param state
	 *            --- state being worked on.
	 * @param automaton
	 *            --- automaton containing state being worked on.
	 * @return
	 */
	private static boolean simplifyUnion(int index, Automaton.State state,
			Automaton automaton) {
		return simplifyUnion_1(index, state, automaton)
				|| simplifyUnion_2(index, state, automaton);
	}
	
	/**
	 * This method applies the following rewrite rules:
	 * <ul>
	 * <li><code>T | void</code> => <code>T</code>.</li>
	 * <li><code>T | any</code> => <code>any</code>.</li>
	 * <li><code>X<T | X></code> => <code>T</code>.</li>
	 * <li><code>(T_1 | T_2) | T_3</code> => <code>(T_1 | T_2 | T_3)</code>.</li>
	 * </ul>
	 * 
	 * @param index
	 *            --- index of state being worked on.
	 * @param state
	 *            --- state being worked on.
	 * @param automaton
	 *            --- automaton containing state being worked on.
	 * @return
	 */
	private static boolean simplifyUnion_1(int index, Automaton.State state,
			Automaton automaton) {
		int[] children = state.children;
		boolean changed = false;
		for (int i = 0; i < children.length; ++i) {
			int iChild = children[i];
			if (iChild == index) {
				// contractive case
				state.children = removeIndex(i, children);
				changed = true;
			} else {
				Automaton.State child = automaton.states[iChild];
				switch (child.kind) {
					case Type.K_ANY :
						automaton.states[index] = new Automaton.State(Type.K_ANY);
						return true;
					case Type.K_VOID : {
						children = removeIndex(i, children);
						state.children = children;
						changed = true;
						break;
					}
					case Type.K_UNION :
						return flattenChildren(index, state, automaton);
				}
			}
		}
		if (children.length == 0) {
			// this can happen in the case of a union which has only itself as a
			// child.
			automaton.states[index] = new Automaton.State(Type.K_VOID);
			changed = true;
		} else if (children.length == 1) {
			// bypass this node altogether
			int child = children[0];
			automaton.states[index] = new Automaton.State(automaton.states[child]);
			changed = true;
		}
		
		return changed;
	}

	/**
	 * This method applies the following rewrite rules:
	 * <ul>
	 * <li><code>T_1 | T_2</code> where <code>T_1 :> T_2</code> => <code>T_1</code>.
	 * </ul>
	 * 
	 * @param index
	 *            --- index of state being worked on.
	 * @param state
	 *            --- state being worked on.
	 * @param automaton
	 *            --- automaton containing state being worked on.
	 * @return
	 */
	private static boolean simplifyUnion_2(int index, Automaton.State state,
			Automaton automaton) {
		boolean changed = false;
		int[] children = state.children;

		for (int i = 0; i < children.length; ++i) {
			int iChild = children[i];
			// check whether this child is subsumed
			boolean subsumed = false;
			for (int j = 0; j < children.length; ++j) {
				int jChild = children[j];
				if (i != j && isSubtype(jChild, iChild, automaton)
						&& (!isSubtype(iChild, jChild, automaton) || i > j)) {					
					subsumed = true;
				}
			}
			if (subsumed) {
				children = removeIndex(i--, children);
				state.children = children;
				changed = true;
			}
		}

		if (children.length == 1) {			
			// bypass this node altogether
			int child = children[0];
			automaton.states[index] = new Automaton.State(automaton.states[child]);
			changed = true;
		}
		
		return changed;
	}
	
	private static boolean isSubtype(int fromIndex, int toIndex,
			Automaton automaton) {
		SubtypeOperator op = new SubtypeOperator(automaton,automaton);
		return op.isSubtype(fromIndex, toIndex);
	}
	
	private final static class IntersectionPoint {
		public final int fromIndex;
		public final boolean fromSign;
		public final int toIndex;
		public final boolean toSign;
		
		public IntersectionPoint(int fromIndex, boolean fromSign, int toIndex, boolean toSign) {
			this.fromIndex = fromIndex;
			this.fromSign = fromSign;
			this.toIndex = toIndex;
			this.toSign = toSign;
		}
		
		public boolean equals(Object o) {
			if(o instanceof IntersectionPoint) {
				IntersectionPoint ip = (IntersectionPoint) o;
				return fromIndex == ip.fromIndex && fromSign == ip.fromSign
						&& toIndex == ip.toIndex && toSign == ip.toSign;
			}
			return false;
		}
		
		public int hashCode() {
			return fromIndex + toIndex;
		}
	}

	/**
	 * Compute the <i>intersection</i> of two types. The resulting type will
	 * only accept values which are accepted by both types being intersected. In
	 * many cases, the only valid intersection will be <code>void</code>. For
	 * example:
	 * 
	 * <pre>
	 * int & real => void
	 * int & [int] => void
	 * !int & int|[int] => [int]
	 * int|[any] & any|[int] => int|[int]
	 * </pre>
	 * 
	 * Type intersection can be thought of as <i>set intersection</i>. In this
	 * way, we're effectively taking the sets of values represented by the two
	 * types and intersecting them. In particular, the resulting type should
	 * correspond exactly to that intersection.
	 * 
	 * @param t1
	 *            --- first type to be intersected
	 * @param t2
	 *            --- second type to be intersected
	 * @return
	 */
	public static Type intersect(Type t1, Type t2) {
		Automaton a1 = Type.destruct(t1);
		Automaton a2 = Type.destruct(t2);
		return Type.construct(intersect(true,a1,true,a2));
	}
	
	private static Automaton intersect(boolean fromSign, Automaton from, boolean toSign, Automaton to) {
		HashMap<IntersectionPoint,Integer> allocations = new HashMap();		
		ArrayList<Automaton.State> nstates = new ArrayList();
		intersect(0,fromSign,from,0,toSign,to,allocations,nstates);		
		return new Automaton(nstates.toArray(new Automaton.State[nstates.size()]));		
	}
	
	private static int intersect(int fromIndex, boolean fromSign,
			Automaton from, int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {				
		
		// first, check whether we have determined this state already
		IntersectionPoint ip = new IntersectionPoint(fromIndex,fromSign,toIndex,toSign);
		Integer allocation = allocations.get(ip);
		if(allocation != null) { return allocation;}
		
		// looks like we haven't, so proceed to determine the new state.
		int myIndex = states.size();
		allocations.put(ip,myIndex);
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
				
		// now, dispatch for the appropriate case.
		if(fromState.kind == toState.kind) {			
			return intersectSameKind(fromIndex, fromSign, from, toIndex,
					toSign, to, allocations, states);			
		} else {
			return intersectDifferentKind(fromIndex, fromSign, from, toIndex,
					toSign, to, allocations, states);
		}
	}

	/**
	 * The following method intersects two nodes which have different kinds. A
	 * precondition is that space has already been allocated in states for the
	 * resulting node.
	 * 
	 * @param fromIndex
	 *            --- index of state in from position
	 * @param fromSign
	 *            --- index of state in from position
	 * @param from
	 *            --- automaton in the from position (i.e. containing state at
	 *            fromIndex).
	 * @param toIndex
	 *            --- index of state in to position
	 * @param toSign
	 *            --- index of state in to position
	 * @param to
	 *            --- automaton in the to position (i.e. containing state at
	 *            toIndex).
	 * @param allocations
	 *            --- mapping of intersection points to their index in states
	 * @param states
	 *            --- list of states which constitute the new automaton being
	 *            constructed/
	 * @return
	 */
	private static int intersectDifferentKind(int fromIndex, boolean fromSign,
			Automaton from, int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		int myIndex = states.size();
		states.add(null); // allocate space for me
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
		Automaton.State myState = null;
		
		// using invert helps reduce the number of cases to consider.
		int fromKind = invert(fromState.kind,fromSign);
		int toKind = invert(toState.kind,toSign);
				
		// TODO: tidy this mess up
		if(fromKind == Type.K_VOID || toKind == Type.K_VOID) {
			myState = new Automaton.State(Type.K_VOID);
		} else if(fromKind == Type.K_UNION) {						
			
			// (T1 | T2) & T3 => (T1&T3) | (T2&T3)
			int[] fromChildren = fromState.children;
			int[] myChildren = new int[fromChildren.length];
			for(int i=0;i!=fromChildren.length;++i) {
				int fromChild = fromChildren[i];
				myChildren[i] = intersect(fromChild,fromSign,from,toIndex,toSign,to,allocations,states);
			}					
			myState = new Automaton.State(Type.K_UNION,false,myChildren);			
		} else if(toKind == Type.K_UNION) {			
			int[] toChildren = toState.children;
			int[] myChildren = new int[toChildren.length];
			for(int i=0;i!=toChildren.length;++i) {
				int toChild = toChildren[i];
				myChildren[i] = intersect(fromIndex, fromSign, from,
						toChild, toSign, to, allocations, states);
			}			
			myState = new Automaton.State(Type.K_UNION,false,myChildren);					
		} else if (fromKind == K_INTERSECTION) {
			// !(T1 | T2) & T3 => (!T1&T3) & (!T2&T3)
			// => !(!(!T1&T3)|!(!T2&T3))
			int[] fromChildren = fromState.children;
			int[] myChildren = new int[fromChildren.length];
			for (int i = 0; i != fromChildren.length; ++i) {
				int fromChild = fromChildren[i];
				int tmpChild = intersect(fromChild, fromSign, from, toIndex,
						toSign, to, allocations, states);
				myChildren[i] = states.size();
				states.add(new Automaton.State(Type.K_NEGATION, true, tmpChild));
			}
			states.add(new Automaton.State(Type.K_UNION, false, myChildren));
			myState = new Automaton.State(Type.K_NEGATION, true,
					states.size() - 1);
		} else if (toKind == K_INTERSECTION) {
			int[] toChildren = toState.children;
			int[] myChildren = new int[toChildren.length];
			for (int i = 0; i != toChildren.length; ++i) {
				int toChild = toChildren[i];
				int tmpChild = intersect(fromIndex, fromSign, from, toChild,
						toSign, to, allocations, states);
				myChildren[i] = states.size();
				states.add(new Automaton.State(Type.K_NEGATION, true, tmpChild));
			}
			states.add(new Automaton.State(Type.K_UNION, false, myChildren));
			myState = new Automaton.State(Type.K_NEGATION, true,
					states.size() - 1);
		} else if(fromKind == Type.K_NEGATION) {
			states.remove(states.size()-1);
			int fromChild = fromState.children[0];
			return intersect(fromChild,!fromSign,from,toIndex,toSign,to,allocations,states);
		} else if(toKind == Type.K_NEGATION) {			
			states.remove(states.size()-1);
			int toChild = toState.children[0];
			return intersect(fromIndex,fromSign,from,toChild,!toSign,to,allocations,states);
		} else if(fromKind == Type.K_ANY) {				
			states.remove(states.size()-1);
			if(!toSign) {
				states.add(new Automaton.State(Type.K_NEGATION,states.size()+1));
			}
			Automata.extractOnto(toIndex,to,states);
			return myIndex;
		} else if(toKind == Type.K_ANY) {
			states.remove(states.size()-1);
			if(!fromSign) {
				states.add(new Automaton.State(Type.K_NEGATION,states.size()+1));
			}
			Automata.extractOnto(fromIndex,from,states);
			return myIndex;
		} else if(fromSign && toSign) {				
			myState = new Automaton.State(Type.K_VOID);
		} else if(fromSign) {
			states.remove(states.size()-1);
			Automata.extractOnto(fromIndex,from,states);
			return myIndex;
		} else if(toSign) {
			states.remove(states.size()-1);
			Automata.extractOnto(toIndex,to,states);
			return myIndex;
		} else {
			int childIndex = states.size();			
			states.add(null);		
			int nFromChild = states.size();			
			Automata.extractOnto(fromIndex,from,states);
			int nToChild = states.size();
			Automata.extractOnto(toIndex,to,states);
			states.set(childIndex,new Automaton.State(Type.K_UNION, nFromChild,
					nToChild));
			myState = new Automaton.State(Type.K_NEGATION, childIndex);
		}
		
		states.set(myIndex, myState);
		
		return myIndex;
	}

	/**
	 * The following method intersects two nodes which have identical kind.
	 * 
	 * @param fromIndex
	 *            --- index of state in from position
	 * @param fromSign
	 *            --- sign of state in from position
	 * @param from
	 *            --- automaton in the from position (i.e. containing state at
	 *            fromIndex).
	 * @param toIndex
	 *            --- index of state in to position
	 * @param toSign
	 *            --- sign of state in to position
	 * @param to
	 *            --- automaton in the to position (i.e. containing state at
	 *            toIndex).
	 * @param allocations
	 *            --- mapping of intersection points to their index in states
	 * @param states
	 *            --- list of states which constitute the new automaton being
	 *            constructed/
	 * @return
	 */
	private static int intersectSameKind(int fromIndex, boolean fromSign, Automaton from,
			int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {		
		Automaton.State fromState = from.states[fromIndex];		
		
		switch(fromState.kind) {
			case Type.K_VOID:
				return intersectVoid(fromIndex,fromSign,from,toIndex,toSign,to,allocations,states);
			case Type.K_ANY: 
				return intersectVoid(fromIndex,!fromSign,from,toIndex,!toSign,to,allocations,states);
			case Type.K_RECORD: 
				return intersectRecords(fromIndex,fromSign,from,toIndex,toSign,to,allocations,states);			
			case Type.K_LABEL:
			case Type.K_NOMINAL:
				return intersectNominals(fromIndex,fromSign,from,toIndex,toSign,to,allocations,states);
			case Type.K_TUPLE:
				return intersectTuples(fromIndex,fromSign,from,toIndex,toSign,to,allocations,states);																				
			case Type.K_LIST:
			case Type.K_SET:
				return intersectSetsOrLists(fromIndex,fromSign,from,toIndex,toSign,to,allocations,states);
			case Type.K_PROCESS:
			case Type.K_DICTIONARY: 										
				return intersectCompounds(fromIndex,fromSign,from,toIndex,toSign,to,null,allocations,states);							
			case Type.K_NEGATION: 
				return intersectNegations(fromIndex,fromSign,from,toIndex,toSign,to,allocations,states);								
			case Type.K_UNION: 
				return intersectUnions(fromIndex,fromSign,from,toIndex,toSign,to,allocations,states);
			case Type.K_FUNCTION:
			case Type.K_HEADLESS:
			case Type.K_METHOD: 
				return intersectFunctionsOrMethods(fromIndex,fromSign,from,toIndex,toSign,to,allocations,states);			
			default: {
				return intersectPrimitives(fromIndex,fromSign,from,toIndex,toSign,to,allocations,states);
			}		
		}	
	}
	
	// ==================================================================================
	// Primitives
	// ==================================================================================
	
	private static int intersectVoid(int fromIndex, boolean fromSign, Automaton from,
			int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		Automaton.State myState;
		
		if(!fromSign && !toSign) {
			myState = new Automaton.State(Type.K_ANY);
		} else {
			// void & void => void
			// void & !void => void
			myState = new Automaton.State(Type.K_VOID);	
		} 		
		
		states.add(myState);
		return states.size()-1;
	}
	
	private static int intersectPrimitives(int fromIndex, boolean fromSign, Automaton from,
			int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State myState;
		
		if(fromSign && toSign) {
			// e.g. INT & INT => INT
			myState = new Automaton.State(fromState.kind);			
		} else if(fromSign || toSign) {
			// e.g. !INT & INT => INT
			myState = new Automaton.State(Type.K_VOID);			 
		} else {
			// e.g. !INT & !INT => !INT
			int myIndex = states.size();
			states.add(new Automaton.State(Type.K_NEGATION,myIndex+1));
			states.add(new Automaton.State(fromState.kind));
			return myIndex;
		}
		
		states.add(myState);
		return states.size()-1;
	}
	
	// ==================================================================================
	// Negations
	// ==================================================================================
	
	private static int intersectNegations(int fromIndex, boolean fromSign, Automaton from,
			int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
	
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];
		
		int fromChild = fromState.children[0];
		int toChild = toState.children[0];				
						
		return intersect(fromChild,!fromSign,from,toChild,!toSign,to,allocations,states);		
	}

	// ==================================================================================
	// Nominals
	// ==================================================================================
	
	private static int intersectNominals(int fromIndex, boolean fromSign, Automaton from,
			int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];
		
		if(!fromState.data.equals(toState.data)) {
			int myIndex = states.size();
			if(fromSign && toSign) {
				states.add(new Automaton.State(Type.K_VOID));
				return myIndex;				
			} else if(fromSign || toSign) {
				Automata.extractOnto(fromIndex,from,states);
				return myIndex;							
			}			
		} 
		
		return intersectCompounds(fromIndex, fromSign, from, toIndex, toSign,
				to, fromState.data, allocations, states);						
	}
	
	// ==================================================================================
	// Tuples
	// ==================================================================================
	
	private static int intersectTuples(int fromIndex, boolean fromSign,
			Automaton from, int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];
		
		if(fromState.children.length != toState.children.length) {
			if(fromSign && toSign) {
				int myIndex = states.size();
				states.add(new Automaton.State(Type.K_VOID));
				return myIndex;
			} else if(fromSign || toSign) {
				int myIndex = states.size();
				Automata.extractOnto(fromIndex,from,states);
				return myIndex;		
			}
		} 
		
		return intersectCompounds(fromIndex,fromSign,from,toIndex,toSign,to,null,allocations,states);					
	}
	
	// ==================================================================================
	// Unions
	// ==================================================================================
	
	private static int intersectUnions(int fromIndex, boolean fromSign, Automaton from,
			int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states){
		if (!fromSign && !toSign) {
			return intersectUnionsNegNeg(fromIndex, from, toIndex, to,allocations,states);		
		} else {
			return intersectUnionsPosNeg(fromIndex, fromSign, from, toIndex, toSign, to, allocations,states);		
		}
	}
	
	private static int intersectUnionsPosNeg(int fromIndex, boolean fromSign,
			Automaton from, int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		// (T1|T2) & !(T3|T4) => (T1&!(T3|T4)) | (T2&!(T3|T4))

		Automaton.State fromState = from.states[fromIndex];
		int myIndex = states.size();
		states.add(null); // reserve space for me
		
		int[] fromChildren = fromState.children;
		int[] newChildren = new int[fromChildren.length];
		for (int i = 0; i != fromChildren.length; ++i) {
			int fromChild = fromChildren[i];
			newChildren[i] = intersect(fromChild, fromSign, from, toIndex,
					toSign, to, allocations, states);
		}

		Automaton.State myState = new Automaton.State(Type.K_UNION, false,
				newChildren);
		states.set(myIndex,myState);
		return myIndex;
	}	
	
	private static int intersectUnionsNegNeg(int fromIndex, Automaton from,
			int toIndex, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states){
		// !(T1|T2) & !(T3|T4) => !T1 & !T2 & !T3 & !T4 => !(T1|T2|T3|T4)
		int myIndex = states.size();
		states.add(null); // reserve space for me
		
		Automaton.State fromState = from.states[fromIndex];		
		Automaton.State toState = to.states[fromIndex];		
			
		int[] fromChildren = fromState.children;
		int[] toChildren = toState.children;
		int[] newChildren = new int[fromChildren.length+toChildren.length];
		int childIndex = states.size();
		states.add(new Automaton.State(Type.K_UNION, false, newChildren));
		for (int i = 0; i != fromChildren.length; ++i) {
			int fromChild = fromChildren[i];
			newChildren[i] = states.size();
			Automata.extractOnto(fromChild,from,states);					
		}
		for (int i = 0; i != toChildren.length; ++i) {
			int toChild = toChildren[i];
			newChildren[i+fromChildren.length] = states.size();
			Automata.extractOnto(toChild,to,states);					
		}
		
		Automaton.State myState = new Automaton.State(Type.K_NEGATION, true, childIndex);
		states.set(myIndex,myState);
		return myIndex;
	}
		
	// ==================================================================================
	// Sets and Lists
	// ==================================================================================

	private static int intersectSetsOrLists(int fromIndex, boolean fromSign, Automaton from,
			int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {					
		

		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
		
		// pos-pos
		// [T1]  & [T2]  => [T1&T2]
		// [T1+] & [T2]  => [T1&T2+]
		// [T1]  & [T2+] => [T1&T2+]
		// [T1+] & [T2+] => [T1&T2+]
		
		// pos-neg
		// [T1] & ![T2] => [T1&!T2+]
		// [T1+] & ![T2] => [T1&!T2+]
		// [T1] & ![T2+] => [T1&!T2]
		// [T1+] & ![T2+] => [T1&!T2+]
		
		boolean fromNonEmpty = (Boolean) fromState.data;
		boolean toNonEmpty = (Boolean) toState.data;
		Object myData = null;
		
		if(fromSign && toSign) {
			myData = fromNonEmpty | toNonEmpty; 			
		} else if(fromSign) {						
			myData = fromNonEmpty | !toNonEmpty; 			
		} else if(toSign) {
			myData = !fromNonEmpty | toNonEmpty; 				
		}
		
		return intersectCompounds(fromIndex,fromSign,from,toIndex,toSign,to,myData,allocations,states);					
	}

	// ==================================================================================
	// Dictionaries, Processes, Records (closed), Sets and Lists
	// ==================================================================================
	
	private static int intersectCompounds(int fromIndex, boolean fromSign,
			Automaton from, int toIndex, boolean toSign, Automaton to,
			Object myData,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		if (fromSign == toSign) {
			if (fromSign) {
				return intersectCompoundsPosPos(fromIndex, from, toIndex, to,
						myData,allocations, states);
			} else {
				return intersectCompoundsNegNeg(fromIndex, from, toIndex, to,
						allocations, states);
			}
		} else if (fromSign) {
			return intersectCompoundsPosNeg(fromIndex, from, toIndex, to,
					myData,allocations, states);
		} else {
			return intersectCompoundsPosNeg(toIndex, to, fromIndex, from,
					myData,allocations, states);
		}
	}
	
	private static int intersectCompoundsPosPos(int fromIndex, Automaton from,
			int toIndex, Automaton to,Object myData,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		int myIndex = states.size();
		states.add(null); // reserve space for me
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
		
		int[] fromChildren = fromState.children;
		int[] toChildren = toState.children;
		int[] myChildren = new int[fromChildren.length];
		for(int i=0;i!=fromChildren.length;++i) {
			int fromChild = fromChildren[i];
			int toChild = toChildren[i];
			myChildren[i] = intersect(fromChild, true, from,
					toChild, true, to, allocations, states);
		}				
		
		Automaton.State myState = new Automaton.State(fromState.kind, myData,
				true, myChildren);
		
		states.set(myIndex,myState);		
		return myIndex;
	}
	
	private static int intersectCompoundsPosNeg(int fromIndex, Automaton from,
			int toIndex, Automaton to,Object myData,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		int myIndex = states.size();
		states.add(null); // reserve space for me
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
				
		int[] fromChildren = fromState.children;
		int[] toChildren = toState.children;
		int[] tmpChildren = new int[fromChildren.length];
		for(int i=0;i!=fromChildren.length;++i) {
			tmpChildren[i] = states.size();
			Automata.extractOnto(fromChildren[i],from,states);
		}				
		int[] myChildren = new int[fromChildren.length];
		for(int i=0;i!=fromChildren.length;++i) {
			int[] myChildChildren = new int[fromChildren.length];
			for(int j=0;j!=fromChildren.length;++j) {
				if(i == j) {
					int fromChild = fromChildren[i];
					int toChild = toChildren[i];
					myChildChildren[i] = intersect(fromChild, true, from,
							toChild, false, to, allocations, states);
				} else {
					myChildChildren[j] = tmpChildren[j];
				}
			}
			myChildren[i] = states.size();
			states.add(new Automaton.State(fromState.kind, myData,
					true, myChildChildren));
		}						
		
		Automaton.State myState = new Automaton.State(Type.K_UNION, null,
				false, myChildren);
		
		states.set(myIndex,myState);		
		return myIndex;
	}
	
	private static int intersectCompoundsNegNeg(int fromIndex, Automaton from,
			int toIndex, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		int myIndex = states.size();
		states.add(null); // reserve space for me
		
		// e.g. ![int] & ![real] => !([int]|[real])
		int fromChild = states.size();
		Automata.extractOnto(fromIndex,from,states);
		int toChild = states.size();
		Automata.extractOnto(toIndex,to,states);
		states.add(new Automaton.State(Type.K_UNION,false,fromChild,toChild));
		Automaton.State myState = new Automaton.State(Type.K_NEGATION,states.size()-1);		
		
		states.set(myIndex,myState);		
		return myIndex;		
	}

	// ==================================================================================
	// Functions and Methods
	// ==================================================================================	
	private static int intersectFunctionsOrMethods(int fromIndex, boolean fromSign,
			Automaton from, int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,		
			ArrayList<Automaton.State> states) {
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
		
		if(fromState.children.length != toState.children.length) {
			if(fromSign && toSign) {
				int myIndex = states.size();
				states.add(new Automaton.State(Type.K_VOID));
				return myIndex;
			} else if(fromSign || toSign) {
				int myIndex = states.size();
				Automata.extractOnto(fromIndex,from,states);
				return myIndex;		
			}
		} 
		
		// it's fair to say that something doesn't feel right about this?
		
		if(fromSign == toSign) { 
			if(fromSign) {
				return intersectFunctionOrMethodsHelper(fromIndex,from,toIndex,toSign,to,allocations,states);
			} else {
				return intersectCompounds(fromIndex,fromSign,from,toIndex,toSign,to,null,allocations,states);				
			}
		} else if(fromSign) {
			return intersectFunctionOrMethodsHelper(fromIndex,from,toIndex,false,to,allocations,states);
		} else {
			return intersectFunctionOrMethodsHelper(toIndex,to,fromIndex,false,from,allocations,states);
		}
	}
	
	private static int intersectFunctionOrMethodsHelper(int fromIndex,
			Automaton from, int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
	
		// int(int) & any(real) => int(int|real)
	
		int myIndex = states.size();
		states.add(null); // reserve space for me
				
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
		
		int[] fromChildren = fromState.children;
		int[] toChildren = toState.children;
		int[] myChildren = new int[fromChildren.length];
		int returnIndex = 0; // index of return value in children
		
		if(fromState.kind == Type.K_METHOD) {
			// receiver is invariant
			myChildren[0] = intersect(fromChildren[0],
					true, from, toChildren[0], true, to,
					allocations, states);
			returnIndex = 1;
		}
		
		// return value is co-variant (i.e. normal, like e.g. list elements)
		myChildren[returnIndex] = intersect(fromChildren[returnIndex],
				true, from, toChildren[returnIndex], toSign, to,
				allocations, states);
		
		// throws clause is co-variant (i.e. normal, like e.g. list elements)
		myChildren[returnIndex+1] = intersect(fromChildren[returnIndex+1],
				true, from, toChildren[returnIndex+1], toSign, to,
				allocations, states);
		
		if(toSign) {
			// parameter values are harder, since they are contra-variant.
			for(int i=returnIndex+2;i<fromChildren.length;++i) {
				int fromChild = fromChildren[i];
				int toChild = toChildren[i];
				int[] childChildren = new int[2];
				myChildren[i] = states.size();					
				states.add(new Automaton.State(Type.K_UNION,null,false,childChildren));					
				childChildren[0] = states.size();
				Automata.extractOnto(fromChild,from,states);
				childChildren[1] = states.size();
				Automata.extractOnto(toChild,to,states);
			}
		} else {			
			// parameter values are harder, since they are contra-variant.
			for(int i=returnIndex+2;i<fromChildren.length;++i) {
				int fromChild = fromChildren[i];
				int toChild = toChildren[i];
				int[] childChildren = new int[2];
				myChildren[i] = states.size();					
				states.add(new Automaton.State(Type.K_UNION,null,false,childChildren));					
				childChildren[0] = states.size();
				Automata.extractOnto(fromChild,from,states);
				childChildren[1] = states.size();
				states.add(new Automaton.State(Type.K_NEGATION,null,false,states.size()+1));
				Automata.extractOnto(toChild,to,states);
			}		
		} 
		
		Automaton.State myState = new Automaton.State(fromState.kind,
				fromState.data, true, myChildren);
		
		states.set(myIndex,myState);
		return myIndex;
	}			
	
	// ==================================================================================
	// Records
	// ==================================================================================
	
	private static int intersectRecords(int fromIndex, boolean fromSign, Automaton from,
			int toIndex, boolean toSign, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		if(fromSign == toSign) {
			if(fromSign) {
				return intersectRecordsPosPos(fromIndex,from,toIndex,to,allocations,states);
			} else {
				return intersectCompoundsNegNeg(fromIndex,from,toIndex,to,allocations,states);
			}
		} else if(fromSign) {
			return intersectRecordsPosNeg(fromIndex,from,toIndex,to,allocations,states);
		} else {
			return intersectRecordsPosNeg(toIndex,to,fromIndex,from,allocations,states);
		}
	}
	
	private static int intersectRecordsPosPos(int fromIndex, Automaton from,
			int toIndex, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
		Type.Record.State fromData = (Type.Record.State) fromState.data;
		Type.Record.State toData = (Type.Record.State) toState.data;
		if (fromData.isOpen && toData.isOpen) {					
			return intersectPosPosOpenOpen(fromIndex,from,toIndex,to,allocations,states);
		} else if(toData.isOpen) {
			return intersectPosPosClosedOpen(fromIndex,from,toIndex,to,allocations,states);
		} else if(fromData.isOpen) {
			return intersectPosPosClosedOpen(toIndex,to,fromIndex,from,allocations,states);
		} else {
			// fall through to general case for compounds
			if (!fromState.data.equals(toState.data)
					|| fromState.children.length != toState.children.length) {
				int myIndex = states.size();
				states.add(new Automaton.State(Type.K_VOID));
				return myIndex;
			} else {
				return intersectCompoundsPosPos(fromIndex,from,toIndex,to,fromState.data,allocations,states);
			}	
		}		
	}
	
	private static int intersectRecordsPosNeg(int fromIndex, Automaton from,
			int toIndex, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];	
		Type.Record.State fromData = (Type.Record.State) fromState.data;
		Type.Record.State toData = (Type.Record.State) toState.data;
		if (fromData.isOpen && toData.isOpen) {					
			return intersectPosNegOpenOpen(fromIndex,from,toIndex,to,allocations,states);					
		} else if(toData.isOpen) {
			return intersectPosNegClosedOpen(fromIndex,from,toIndex,to,allocations,states);			
		} else if(fromData.isOpen) {
			return intersectNegPosClosedOpen(toIndex,to,fromIndex,from,allocations,states);			
		} else {
			// fall through to general case for compounds
			if (!fromState.data.equals(toState.data)
					|| fromState.children.length != toState.children.length) {
				// e.g. {int f} & !{int g} => {int f}
				int myIndex = states.size();
				Automata.extractOnto(fromIndex,from,states);
				return myIndex;					
			} else {
				return intersectCompoundsPosNeg(fromIndex,from,toIndex,to,fromState.data,allocations,states);
			}	
		}
	}
	
	/**
	 * Intersect two automaton representing positive, open records. For example:
	 * 
	 * <pre>
	 * {T1 f, T2 g, ...} & {T3 g, T4 h, ...} => {T1 f, (T2&T3) g, T4 h, ...}
	 * </pre>
	 * 
	 * As you can see from above, we include all fields from both types. For
	 * fields common to both we intersect their types.
	 * 
	 * @param fromIndex
	 *            --- index of state in from position
	 * @param from
	 *            --- automaton in the from position (i.e. containing state at
	 *            fromIndex).
	 * @param toIndex
	 *            --- index of state in to position
	 * @param to
	 *            --- automaton in the to position (i.e. containing state at
	 *            toIndex).
	 * @param allocations
	 *            --- mapping of intersection points to their index in states
	 * @param states
	 *            --- list of states which constitute the new automaton being
	 *            constructed.
	 * @return
	 */
	private static int intersectPosPosOpenOpen(int fromIndex, Automaton from,
			int toIndex, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		int myIndex = states.size();
		states.add(null); // reserve space for me
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];
		Type.Record.State fromData = (Type.Record.State) fromState.data;
		Type.Record.State toData = (Type.Record.State) toState.data;
		Type.Record.State myData = new Type.Record.State(true);		
		
		int fi = 0, ti = 0;
		int[] fromChildren = fromState.children;
		int[] toChildren = toState.children;
		ArrayList<Integer> myChildren = new ArrayList<Integer>();
		while (fi < fromData.size() && ti < toData.size()) {			
			int fromChild = fromChildren[fi];
			int toChild = toChildren[ti];
			String fn = fromData.get(fi);
			String tn = toData.get(ti);						
			int c = fn.compareTo(tn);
			if (c == 0) {
				myData.add(fn);
				myChildren.add(intersect(fromChild, true, from, toChild,
						true, to, allocations, states));
				fi++;ti++;
			} else if (c < 0) {
				myData.add(fn);
				myChildren.add(states.size());
				Automata.extractOnto(fromChild,from,states);
				fi++;
			} else {
				myData.add(tn);
				myChildren.add(states.size());
				Automata.extractOnto(toChild,to,states);
				ti++;
			}
		}	
		while (fi < fromData.size()) {
			String fn = fromData.get(fi);
			myData.add(fn);
			int fromChild = fromChildren[fi];
			myChildren.add(states.size());
			Automata.extractOnto(fromChild,from,states);
			fi = fi + 1;
		}
		while (ti < toData.size()) {
			String tn = toData.get(ti);
			myData.add(tn);
			int toChild = toChildren[ti];
			myChildren.add(states.size());
			Automata.extractOnto(toChild,to,states);
			ti = ti + 1;
		}
		// finally, create the new type
		int[] myChildrenArray = new int[myChildren.size()];
		for(int i=0;i!=myChildrenArray.length;++i) {
			 myChildrenArray[i] = myChildren.get(i);
		}
		
		Automaton.State myState = new Automaton.State(fromState.kind, myData,
				true, myChildrenArray);
		
		states.set(myIndex,myState);
		return myIndex;
	}
	
	/**
	 * Intersect two automaton representing positive records, where the first is
	 * closed and the second open. For example:
	 * 
	 * <pre>
	 * {T1 f, T2 g} & {T3 g, T4 h, ...} => void
	 * {T1 f, T2 g} & {T3 g, ...} => {T1 f, (T2&T3) g}
	 * </pre>
	 * 
	 * In this case, the result must include exactly those fields in the closed
	 * record. If a required field in the open record is not present in the
	 * closed record, then there is no intersection.
	 * 
	 * @param fromIndex
	 *            --- index of state in from position
	 * @param from
	 *            --- automaton in the from position (i.e. containing state at
	 *            fromIndex).
	 * @param toIndex
	 *            --- index of state in to position
	 * @param to
	 *            --- automaton in the to position (i.e. containing state at
	 *            toIndex).
	 * @param allocations
	 *            --- mapping of intersection points to their index in states
	 * @param states
	 *            --- list of states which constitute the new automaton being
	 *            constructed.
	 * @return
	 */
	private static int intersectPosPosClosedOpen(
			int fromIndex, Automaton from, int toIndex, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		
		int myIndex = states.size();
		states.add(null); // reserve space for me
				
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];
		Type.Record.State fromData = (Type.Record.State) fromState.data;
		Type.Record.State toData = (Type.Record.State) toState.data;

		int oldSize = states.size(); // in case we need to back out
		int[] myChildren = new int[fromData.size()];
		int[] fromChildren = fromState.children;
		int[] toChildren = toState.children;
		for (int fi = 0, ti = 0; fi != myChildren.length; ++fi) {
			String fn = fromData.get(fi);
			int fromChild = fromChildren[fi];
			if (ti < toData.size()) {
				String tn = toData.get(ti);
				int c = fn.compareTo(tn);
				if (c == 0) {
					int toChild = toChildren[ti++];
					myChildren[fi] = intersect(fromChild, true, from, toChild,
							true, to, allocations, states);
					continue;
				} else if (c > 0) {
					// e.g. {int f} & {int g,...}
					while (states.size() != oldSize) {
						states.remove(states.size() - 1);
					}
					states.add(new Automaton.State(Type.K_VOID));
					return states.size()-1;					
				}
			}
			myChildren[fi] = states.size();
			Automata.extractOnto(fromChild, from, states);
		}		
		
		Automaton.State myState = new Automaton.State(fromState.kind, fromData, true, myChildren);
		states.set(myIndex,myState);
		return myIndex;
	}
	
	/**
	 * Intersect two automaton representing open records, where the first is
	 * positive and the second negative. For example:
	 * 
	 * <pre>
	 * {T1 f, T2 g, ...} & !{T3 g, T4 h, ...} => {T1 f, T2&!T3 g, ...} | {T1 f, T2 g, void h, ...}
	 * </pre>
	 * 
	 * This is a fairly ticky case!
	 * 
	 * @param fromIndex
	 *            --- index of state in from position
	 * @param from
	 *            --- automaton in the from position (i.e. containing state at
	 *            fromIndex).
	 * @param toIndex
	 *            --- index of state in to position
	 * @param to
	 *            --- automaton in the to position (i.e. containing state at
	 *            toIndex).
	 * @param allocations
	 *            --- mapping of intersection points to their index in states
	 * @param states
	 *            --- list of states which constitute the new automaton being
	 *            constructed.
	 * @return
	 */
	private static int intersectPosNegOpenOpen(int fromIndex, Automaton from,
			int toIndex, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {
		// FIXME: need to do better here
		int myIndex = states.size()-1;		
		Automata.extractOnto(fromIndex,from,states);
		return myIndex; 
	}

	/**
	 * Intersect two automaton representing records, where the first is positive
	 * and closed and the second negative and open. For example:
	 * 
	 * <pre>
	 * {T1 f, T2 g} & !{T3 g, T4 h, ...} => {T1 f, T2 g}
	 * {T1 f, T2 g} & !{T3 f, T4 g, ...} => {T1 & !T3 f, T2 g} | {T1 f, T2 & !T4 g}
	 * {T1 f, T2 g, T3 h} & !{T4 f, T5 g, ...} => {T1 & !T4 f, T2 g, T3 h} | {T1 f, T2 & !T5 g, T3 h}
	 * </pre>
	 * 
	 * In this case, the result must include exactly those fields in the closed
	 * record. If the open record contains a field not present
	 * in the closed record then it does not intersect with the closed record
	 * and we're fine. Finally, in the case they have exactly the same fields
	 * then we have to distribute.
	 * 
	 * @param fromIndex
	 *            --- index of state in from position
	 * @param from
	 *            --- automaton in the from position (i.e. containing state at
	 *            fromIndex).
	 * @param toIndex
	 *            --- index of state in to position
	 * @param to
	 *            --- automaton in the to position (i.e. containing state at
	 *            toIndex).
	 * @param allocations
	 *            --- mapping of intersection points to their index in states
	 * @param states
	 *            --- list of states which constitute the new automaton being
	 *            constructed.
	 * @return
	 */
	private static int intersectPosNegClosedOpen(
			int fromIndex, Automaton from, int toIndex, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {

		int myIndex = states.size();
		states.add(null); // reserve space for me
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
		
		int[] fromChildren = fromState.children;
		int[] toChildren = toState.children;

		// first, check whether the open record has more fields than the closed.
		// If so, then there is no intersection between them and we can just
		// return the closed record.
		
		if(fromChildren.length < toChildren.length) {
			// no possible intersection so back out			
			Automata.extractOnto(fromIndex,from,states);
			return myIndex; // tad ugly perhaps			
		}
		
		Type.Record.State fromData = (Type.Record.State) fromState.data;
		Type.Record.State toData = (Type.Record.State) toState.data;
		
		// second, check whether the open record contains a field not present in
		// the closed record. If so, then there is no intersection between them
		// and we can just return the closed record.
		
		int fi=0,ti=0;
		while(ti!=toChildren.length) {
			if(fi < fromChildren.length) {
				String fn = fromData.get(fi);
				String tn = toData.get(ti);
				int c = fn.compareTo(tn);
				if(c < 0) {
					++fi;
					continue;
				} else if(c == 0){
					++ti;
					++fi;
					continue;
				}
			} 			
			// no possible intersection so back out			
			Automata.extractOnto(fromIndex,from,states);
			return myIndex; // tad ugly perhaps						
		}
		
		// finally, distribute over those fields present in the open record
		// (which are a subset of those in the closed record).
		
		int[] tmpChildren = new int[fromChildren.length];
		for(int i=0;i!=fromChildren.length;++i) {
			tmpChildren[i] = states.size();
			Automata.extractOnto(fromChildren[i],from,states);
		}				
		int[] myChildren = new int[toChildren.length];
		for(ti=0;ti!=toChildren.length;++ti) {
			int[] myChildChildren = new int[fromChildren.length];
			String tn = toData.get(ti);
			for(fi=0;fi!=fromChildren.length;++fi) {
				String fn = fromData.get(fi);
				if(fn.equals(tn)) {
					int fromChild = fromChildren[fi];
					int toChild = toChildren[ti];
					myChildChildren[fi] = intersect(fromChild, true, from,
							toChild, false, to, allocations, states);
				} else {
					myChildChildren[fi] = tmpChildren[fi];
				}
			}
			myChildren[ti] = states.size();
			states.add(new Automaton.State(fromState.kind, fromData, true,
					myChildChildren));
		}
		
		Automaton.State myState = new Automaton.State(Type.K_UNION, null, false, myChildren);
				
		states.set(myIndex,myState);
		return myIndex;
	}
	
	/**
	 * Intersect two automaton representing records, where the first is negative
	 * and closed and the second positive and open. For example:
	 * 
	 * <pre>
	 * !{int f} & {int f,...} => {int f, ...+}
	 * {T1 f, T2 g, ...} & !{T3 g, T4 h} => {T1 f, T2 g}   
	 * {T1 f, T2 g, ...} & !{T3 f, T4 g} => {T1&!T3 f, T2 g,...} | {T1 f, T2&!T4 g,...} 
	 * {T1 f, T2 g, ...} & !{T3 f, T4 g, T5 h} => {T1&!T3 f, T2 g, ...} | 
	 *                      {T1 f, T2&!T4 g, ...} | {T1 f, T2 g, void h, ...} | 
	 *                      {T1 f, T2 g, !T5 h, ...}
	 * </pre>
	 * 
	 * Here, <code>{T1 f, T2 g, void h, ...}</code> represents a record which
	 * doesn't have a field h. This use of void only really makes sense in the
	 * context of open records.
	 * 
	 * @param fromIndex
	 *            --- index of state in from position
	 * @param from
	 *            --- automaton in the from position (i.e. containing state at
	 *            fromIndex).
	 * @param toIndex
	 *            --- index of state in to position
	 * @param to
	 *            --- automaton in the to position (i.e. containing state at
	 *            toIndex).
	 * @param allocations
	 *            --- mapping of intersection points to their index in states
	 * @param states
	 *            --- list of states which constitute the new automaton being
	 *            constructed.
	 * @return
	 */
	private static int intersectNegPosClosedOpen(
			int fromIndex, Automaton from, int toIndex, Automaton to,
			HashMap<IntersectionPoint, Integer> allocations,
			ArrayList<Automaton.State> states) {		
		
		int myIndex = states.size();
		states.add(null); // reserve space for me
		
		Automaton.State fromState = from.states[fromIndex];
		Automaton.State toState = to.states[toIndex];		
		
		int[] fromChildren = fromState.children;
		int[] toChildren = toState.children;

		Type.Record.State fromData = (Type.Record.State) fromState.data;
		Type.Record.State toData = (Type.Record.State) toState.data;
		
		// check whether the open record contains a field not present in
		// the closed record. If so, then there is no intersection between them
		// and we can just return the closed record.
		
		if(!isSubset(toData,fromData)) {
			// no possible intersection so back out
			myIndex = states.size();
			Automata.extractOnto(toIndex,to,states);
			return myIndex; // tad ugly perhaps			 			
		}
		
		// finally, distribute over those fields present in the open record
		// (which are a subset of those in the closed record).
		
		int[] tmpChildren = new int[toChildren.length];
		for(int i=0;i!=toChildren.length;++i) {
			tmpChildren[i] = states.size();
			Automata.extractOnto(toChildren[i],to,states);
		}				
		int[] myChildren = new int[fromChildren.length];
		Type.Record.State myData = new Type.Record.State(true,fromData);
		for(int fi=0;fi!=fromChildren.length;++fi) {
			int[] myChildChildren = new int[fromChildren.length];			
			int ti=0;
			for(int fj=0;fj!=fromChildren.length;++fj) {
				String fn = fromData.get(fj);
				if (ti < toData.size() && toData.get(ti).equals(fn)) {
					if(fi == fj) {
						int fromChild = fromChildren[fi];
						int toChild = toChildren[ti];
						myChildChildren[fj] = intersect(fromChild, false, from,
								toChild, true, to, allocations, states);
					} else {
						myChildChildren[fj] = tmpChildren[ti];
					}
					ti++;
				} else {
					states.add(new Automaton.State(Type.K_VOID));
					myChildChildren[fj] = states.size()-1; 
				}
			}
			myChildren[fi] = states.size();
			states.add(new Automaton.State(toState.kind, myData, true,
					myChildChildren));
		}				
		
		Automaton.State myState = new Automaton.State(Type.K_UNION, null, false, myChildren);
		states.set(myIndex,myState);
		return myIndex;				
	}

	/**
	 * Check whether one sorted lists is a subset or another.
	 * 
	 * @param subset --- list to check as subset  
	 * @param superset --- list to check as superset
	 * @return
	 */
	private static boolean isSubset(ArrayList<String> subset,
			ArrayList<String> superset) {
		int subsetSize = subset.size();
		int supersetSize = superset.size();
		
		if(subsetSize > supersetSize) {
			return false;
		}
		
		int fi=0,ti=0;
		while(fi < subsetSize && ti < supersetSize) {			
			String fn = subset.get(fi);
			String tn = superset.get(ti);
			int c = fn.compareTo(tn);
			if(c < 0) {				
				++fi;
				continue;								
			} else if(c == 0){
				++ti;
				++fi;
				continue;
			} 
			return false;							
		}
		
		return subsetSize <= supersetSize;		
	}
	
	private static int invert(int kind, boolean sign) {
		if(sign) {
			return kind;
		}
		switch(kind) {
			case K_ANY:
				return K_VOID;
			case K_VOID:
				return K_ANY;
			case K_UNION:
				return K_INTERSECTION;			
			default:
				return kind;
		}		
	}

	/**
	 * The following constant is not actually a valid kind; however, it's
	 * helpful to think of it as one.
	 */
	private static final int K_INTERSECTION = -1;
	
	private static int[] removeIndex(int index, int[] children) {
		int[] nchildren = new int[children.length - 1];
		for (int j = 0; j < children.length; ++j) {
			if (j < index) {
				nchildren[j] = children[j];
			} else if (j > index) {
				nchildren[j - 1] = children[j];
			}
		}
		return nchildren;
	}

	/**
	 * This rule flattens children which have the same kind as the given state.
	 * 
	 * @param index
	 * @param state
	 * @param automaton
	 * @return
	 */
	private static boolean flattenChildren(int index, Automaton.State state,
			Automaton automaton) {
		ArrayList<Integer> nchildren = new ArrayList<Integer>();
		int[] children = state.children;
		final int kind = state.kind;

		for (int i = 0; i < children.length; ++i) {
			int iChild = children[i];
			Automaton.State child = automaton.states[iChild];
			if (child.kind == kind) {
				for (int c : child.children) {
					nchildren.add(c);
				}
			} else {
				nchildren.add(iChild);
			}
		}

		children = new int[nchildren.size()];
		for (int i = 0; i < children.length; ++i) {
			children[i] = nchildren.get(i);
		}

		automaton.states[index] = new Automaton.State(kind, false, children);

		return true;
	}
}
