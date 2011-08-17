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

package wyc.stages;

import java.util.*;

import static wyil.util.SyntaxError.*;
import wyil.ModuleLoader;
import wyil.util.*;
import wyil.lang.*;
import wyc.lang.*;
import wyc.lang.WhileyFile.*;
import wyc.lang.Stmt;
import wyc.lang.Stmt.*;
import wyc.lang.Expr.*;
import wyc.stages.WhileyLexer.Ampersand;
import wyc.util.*;

public class NameResolution {
	private final ModuleLoader loader;	
	private String filename;
	private ModuleID module;
	
	public NameResolution(ModuleLoader loader) {
		this.loader = loader;
	}
	
	public void resolve(WhileyFile wf) {
		ArrayList<PkgID> imports = new ArrayList<PkgID>();
		
		module = wf.module;
		filename = wf.filename;
		
		imports.add(module.pkg().append(module.module()));
		imports.add(module.pkg().append("*"));
		imports.add(new PkgID(new String[]{"whiley","lang"}).append("*"));
						
		for(Decl d : wf.declarations) {			
			try {
				if(d instanceof ImportDecl) {
					ImportDecl impd = (ImportDecl) d;
					imports.add(0,new PkgID(impd.pkg));
				} else if(d instanceof FunDecl) {
					resolve((FunDecl)d,imports);
				} else if(d instanceof TypeDecl) {
					resolve((TypeDecl)d,imports);					
				} else if(d instanceof ConstDecl) {
					resolve((ConstDecl)d,imports);					
				}
			} catch(ResolveError ex) {
				syntaxError(ex.getMessage(),filename,d);
			}
		}				
	}
	
	protected void resolve(ConstDecl td, ArrayList<PkgID> imports) {
		resolve(td.constant,new HashMap<String,Set<Expr>>(), imports);		
	}
	
	protected void resolve(TypeDecl td, ArrayList<PkgID> imports) throws ResolveError {
		try {
			resolve(td.type, imports);	
			if (td.constraint != null) {
				HashMap<String, Set<Expr>> environment = new HashMap<String, Set<Expr>>();
				environment.put("$", Collections.EMPTY_SET);
				addExposedNames(new Expr.Variable("$", td.constraint
						.attribute(Attribute.Source.class),
						new Attributes.Alias(null)), td.type, environment);
				resolve(td.constraint, environment, imports);
			}
		} catch (ResolveError e) {												
			// Ok, we've hit a resolution error.
			syntaxError(e.getMessage(), filename,  td);			
		}
	}	
	
	protected void resolve(FunDecl fd, ArrayList<PkgID> imports) {
		HashMap<String,Set<Expr>> environment = new HashMap<String,Set<Expr>>();
		
		// method parameter types
		for (WhileyFile.Parameter p : fd.parameters) {
			try {
				resolve(p.type, imports);
				environment.put(p.name(),Collections.EMPTY_SET);
			} catch (ResolveError e) {												
				// Ok, we've hit a resolution error.
				syntaxError(e.getMessage(), filename, p, e);
			}
		}
		
		if (fd instanceof MethDecl) {
			MethDecl md = (MethDecl) fd;
			if(md.receiver != null) {		
				environment.put("this",Collections.EMPTY_SET);
			}
		}
		
		// method return and throw types
		try {
			resolve(fd.ret, imports);
			resolve(fd.throwType, imports);
		} catch (ResolveError e) {
			// Ok, we've hit a resolution error.
			syntaxError(e.getMessage(), filename, fd.ret);
		}
		
		// method receiver type (if applicable)
		if(fd instanceof MethDecl) {			
			MethDecl md = (MethDecl) fd;			
			try {			
				resolve(md.receiver, imports);			
			} catch (ResolveError e) {
				// Ok, we've hit a resolution error.
				syntaxError(e.getMessage(),filename,md.receiver);
			}
		}
		
		if (fd.precondition != null) {
			resolve(fd.precondition, environment, imports);
		}

		if (fd.postcondition != null) {
			environment.put("$", Collections.EMPTY_SET);
			resolve(fd.postcondition, environment, imports);
			environment.remove("$");
		}

		List<Stmt> stmts = fd.statements;
		for (int i=0;i!=stmts.size();++i) {
			resolve(stmts.get(i), environment, imports);							
		}
	}
	
	public void resolve(Stmt s, HashMap<String,Set<Expr>> environment, ArrayList<PkgID> imports) {
		try {
			if(s instanceof Assign) {
				resolve((Assign)s, environment, imports);
			} else if(s instanceof Assert) {
				resolve((Assert)s, environment, imports);
			} else if(s instanceof Return) {
				resolve((Return)s, environment, imports);
			} else if(s instanceof Debug) {
				resolve((Debug)s, environment, imports);
			} else if(s instanceof Skip || s instanceof Break) {
				// do nothing
			} else if(s instanceof Throw) {
				resolve((Throw)s, environment, imports);
			} else if(s instanceof IfElse) {
				resolve((IfElse)s, environment, imports);
			} else if(s instanceof Switch) {
				resolve((Switch)s, environment, imports);
			} else if(s instanceof While) {
				resolve((While)s, environment, imports);
			} else if(s instanceof For) {
				resolve((For)s, environment, imports);
			} else if(s instanceof Invoke) {
				resolve((Invoke)s, environment, imports);
			} else if(s instanceof Spawn) {
				resolve((UnOp)s, environment, imports);
			} else {
				syntaxError("unknown statement encountered: "
						+ s.getClass().getName(), filename, s);				
			}
		} catch (ResolveError e) {
			// Ok, we've hit a resolution error.
			syntaxError(e.getMessage(), filename, s);			
		}
	}	

	protected void resolve(Assign s, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) {
		if(s.lhs instanceof Variable) {
			Variable v = (Variable) s.lhs;
			environment.put(v.var, Collections.EMPTY_SET);
		} else if(s.lhs instanceof TupleGen) {
			TupleGen tg = (TupleGen) s.lhs;
			for(Expr e : tg.fields) {
				if(e instanceof Variable) {
					Variable v = (Variable) e;
					environment.put(v.var, Collections.EMPTY_SET);
				} else {
					syntaxError("variable expected",filename,e);
				}
			}
		} else {
			resolve(s.lhs, environment, imports);
		}
		resolve(s.rhs, environment, imports);	
	}

	protected void resolve(Assert s, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) {
		resolve(s.expr, environment, imports);		
	}

	protected void resolve(Return s, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) {
		if(s.expr != null) {
			resolve(s.expr, environment, imports);
		}
	}
	
	protected void resolve(Debug s, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) {
		resolve(s.expr, environment, imports);		
	}

	protected void resolve(Throw s, HashMap<String, Set<Expr>> environment,
			ArrayList<PkgID> imports) {
		resolve(s.expr, environment, imports);
	}
	
	protected void resolve(IfElse s, HashMap<String, Set<Expr>> environment,
			ArrayList<PkgID> imports) {
		resolve(s.condition, environment, imports);
		for (Stmt st : s.trueBranch) {
			resolve(st, environment, imports);
		}
		if (s.falseBranch != null) {
			for (Stmt st : s.falseBranch) {
				resolve(st, environment, imports);
			}			
		}
	}
	
	protected void resolve(Switch s, HashMap<String, Set<Expr>> environment,
			ArrayList<PkgID> imports) {		
		
		resolve(s.expr, environment, imports);
		
		for(Stmt.Case c : s.cases){					
			if(c.value != null) {
				resolve(c.value,environment,imports);
			}
			for (Stmt st : c.stmts) {
				resolve(st, environment, imports);
			}			
		}		
	}
	
	protected void resolve(While s, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) {
		resolve(s.condition, environment, imports);
		if (s.invariant != null) {
			resolve(s.invariant, environment, imports);
		}
		environment = new HashMap<String,Set<Expr>>(environment);
		for (Stmt st : s.body) {
			resolve(st, environment, imports);
		}
	}
	
	protected void resolve(For s, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) {
		resolve(s.source, environment, imports);		
		if (s.invariant != null) {
			resolve(s.invariant, environment, imports);
		}
		environment = new HashMap<String,Set<Expr>>(environment);
		for(String var : s.variables) {
			if (environment.containsKey(var)) {
				syntaxError("variable " + var + " is alreaded defined",
						filename, s);
			}
			environment.put(var, Collections.EMPTY_SET);
		}				
		for (Stmt st : s.body) {
			resolve(st, environment, imports);
		}
	}
	protected void resolve(Expr e, HashMap<String,Set<Expr>> environment, ArrayList<PkgID> imports) {
		try {
			if (e instanceof Constant) {
				
			} else if (e instanceof Variable) {
				resolve((Variable)e, environment, imports);
			} else if (e instanceof NaryOp) {
				resolve((NaryOp)e, environment, imports);
			} else if (e instanceof Comprehension) {
				resolve((Comprehension) e, environment, imports);
			} else if (e instanceof BinOp) {
				resolve((BinOp)e, environment, imports);
			} else if (e instanceof Convert) {
				resolve((Convert)e, environment, imports);
			} else if (e instanceof ListAccess) {
				resolve((ListAccess)e, environment, imports);
			} else if (e instanceof UnOp) {
				resolve((UnOp)e, environment, imports);
			} else if (e instanceof Invoke) {
				resolve((Invoke)e, environment, imports);
			} else if (e instanceof RecordAccess) {
				resolve((RecordAccess) e, environment, imports);
			} else if (e instanceof RecordGen) {
				resolve((RecordGen) e, environment, imports);
			} else if (e instanceof TupleGen) {
				resolve((TupleGen) e, environment, imports);
			} else if (e instanceof DictionaryGen) {
				resolve((DictionaryGen) e, environment, imports);
			} else if(e instanceof TypeConst) {
				resolve((TypeConst) e, environment, imports);
			} else if(e instanceof FunConst) {
				resolve((FunConst) e, environment, imports);
			} else {				
				syntaxError("unknown expression encountered: "
							+ e.getClass().getName(), filename, e);								
			}
		} catch(ResolveError re) {
			syntaxError(re.getMessage(),filename,e,re);			
		} catch(SyntaxError se) {
			throw se;
		} catch(Exception ex) {
			syntaxError("internal failure", filename, e, ex);			
		}	
	}
	
	protected void resolve(Invoke ivk, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) throws ResolveError {
					
		for(Expr e : ivk.arguments) {						
			resolve(e, environment, imports);
		}

		if(!environment.containsKey(ivk.name)) {
			// only look for non-local function binding if there is not a local
			// variable with the same name.
			Expr target = ivk.receiver;			
			if(target != null) {
				resolve(target,environment,imports);
				try {
					ModuleID mid = loader.resolve(ivk.name,imports);
					ivk.attributes().add(new Attributes.Module(mid));	
				} catch(ResolveError e) {
					// in this case, we've been unable to resolve the method
					// being called. However, this does not necessarily indicate
					// an error --- this could be a field dereferences, followed
					// by an indirect function call.
				}
			} else {
				ModuleID mid = loader.resolve(ivk.name,imports);
				// Ok, resolve the module for this invoke
				ivk.attributes().add(new Attributes.Module(mid));		
			}
		} else if(ivk.receiver != null) {
			resolve(ivk.receiver,environment,imports);
		}
	}
	
	protected void resolve(Variable v, HashMap<String, Set<Expr>> environment,
			ArrayList<PkgID> imports) throws ResolveError {
		Set<Expr> aliases = environment.get(v.var);
		if (aliases == null) {
			// This variable access may correspond with a constant definition
			// in some module. Therefore, we must determine which module this
			// is, and then store that information for future use.
			try {
				ModuleID mid = loader.resolve(v.var, imports);
				v.attributes().add(new Attributes.Module(mid));
			} catch(ResolveError err) {
				// In this case, we may still be OK if this is a method, and the
				// this receiver contains a field with the appropriate name. At
				// this point in time, we cannot be sure whether or not this is
				// the case and we must wait until ModuleBuilder to determine
				// this.								
			}
		} else if (aliases.size() == 1) {			
			v.attributes().add(new Attributes.Alias(aliases.iterator().next()));
		} else if (aliases.size() > 1) {
			syntaxError("ambigous variable name", filename, v);
		} else {
			// following signals a local variable			
			v.attributes().add(new Attributes.Alias(null));
		}
	}
	
	protected void resolve(UnOp v, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) throws ResolveError {
		resolve(v.mhs, environment, imports);		
	}
	
	protected void resolve(BinOp v, HashMap<String,Set<Expr>> environment, ArrayList<PkgID> imports) {
		resolve(v.lhs, environment, imports);
		resolve(v.rhs, environment, imports);		
	}
	
	protected void resolve(Convert c, HashMap<String,Set<Expr>> environment, ArrayList<PkgID> imports) throws ResolveError {
		resolve(c.type, imports);
		resolve(c.expr, environment, imports);		
	}
	
	protected void resolve(ListAccess v, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) {
		resolve(v.src, environment, imports);
		resolve(v.index, environment, imports);
	}
	
	protected void resolve(NaryOp v, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) throws ResolveError {				
		for(Expr e : v.arguments) {
			resolve(e, environment, imports);
		}		
	}
	
	protected void resolve(Comprehension e, HashMap<String,Set<Expr>> environment, ArrayList<PkgID> imports) throws ResolveError {						
		HashMap<String,Set<Expr>> nenv = new HashMap<String,Set<Expr>>(environment);
		for(Pair<String,Expr> me : e.sources) {	
			if (environment.containsKey(me.first())) {
				syntaxError("variable " + me.first() + " is alreaded defined",
						filename, e);
			}
			resolve(me.second(),nenv,imports); 			
			nenv.put(me.first(),Collections.EMPTY_SET);
		}		
		if(e.value != null) {			
			resolve(e.value,nenv,imports);
		}
		if(e.condition != null) {
			resolve(e.condition,nenv,imports);
		}	
	}	
		
	protected void resolve(RecordGen sg, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) throws ResolveError {		
		for(Map.Entry<String,Expr> e : sg.fields.entrySet()) {
			resolve(e.getValue(),environment,imports);
		}			
	}

	protected void resolve(TupleGen sg, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) throws ResolveError {		
		for(Expr e : sg.fields) {
			resolve(e,environment,imports);
		}			
	}
	
	protected void resolve(DictionaryGen sg, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) throws ResolveError {		
		for(Pair<Expr,Expr> e : sg.pairs) {
			resolve(e.first(),environment,imports);
			resolve(e.second(),environment,imports);
		}			
	}
	
	protected void resolve(TypeConst tc, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) throws ResolveError {		
		resolve(tc.type,imports);			
	}
	
	protected void resolve(FunConst tc, HashMap<String,Set<Expr>> environment,
			ArrayList<PkgID> imports) throws ResolveError {		
		
		if(tc.paramTypes != null) {
			for(UnresolvedType t : tc.paramTypes) {
				resolve(t,imports);
			}
		}
		
		ModuleID mid = loader.resolve(tc.name,imports);		
		tc.attributes().add(new Attributes.Module(mid));
	}	
	
	protected void resolve(RecordAccess sg, HashMap<String,Set<Expr>> environment, ArrayList<PkgID> imports) throws ResolveError {
		resolve(sg.lhs,environment,imports);			
	}
	
	protected void resolve(UnresolvedType t, ArrayList<PkgID> imports) throws ResolveError {
		if(t instanceof UnresolvedType.List) {
			UnresolvedType.List lt = (UnresolvedType.List) t;
			resolve(lt.element,imports);
		} else if(t instanceof UnresolvedType.Set) {
			UnresolvedType.Set st = (UnresolvedType.Set) t;
			resolve(st.element,imports);
		} else if(t instanceof UnresolvedType.Dictionary) {
			UnresolvedType.Dictionary st = (UnresolvedType.Dictionary) t;
			resolve(st.key,imports);
			resolve(st.value,imports);
		} else if(t instanceof UnresolvedType.Record) {
			UnresolvedType.Record tt = (UnresolvedType.Record) t;
			for(Map.Entry<String,UnresolvedType> e : tt.types.entrySet()) {
				resolve(e.getValue(),imports);
			}
		} else if(t instanceof UnresolvedType.Tuple) {
			UnresolvedType.Tuple tt = (UnresolvedType.Tuple) t;
			for(UnresolvedType e : tt.types) {
				resolve(e,imports);
			}
		} else if(t instanceof UnresolvedType.Named) {
			// This case corresponds to a user-defined type. This will be
			// defined in some module (possibly ours), and we need to identify
			// what module that is here, and save it for future use.
			UnresolvedType.Named dt = (UnresolvedType.Named) t;						
			ModuleID mid = loader.resolve(dt.name, imports);			
			t.attributes().add(new Attributes.Module(mid));
		} else if(t instanceof UnresolvedType.Existential) {
			UnresolvedType.Existential dt = (UnresolvedType.Existential) t;						
			t.attributes().add(new Attributes.Module(module));
		} else if(t instanceof UnresolvedType.Union) {
			UnresolvedType.Union ut = (UnresolvedType.Union) t;
			for(UnresolvedType b : ut.bounds) {
				resolve(b,imports);
			}
		} else if(t instanceof UnresolvedType.Process) {	
			UnresolvedType.Process ut = (UnresolvedType.Process) t;
			resolve(ut.element,imports);			
		} else if(t instanceof UnresolvedType.Fun) {	
			UnresolvedType.Fun ut = (UnresolvedType.Fun) t;
			resolve(ut.ret,imports);
			if(ut.receiver != null) {
				resolve(ut.receiver,imports);
			}
			for(UnresolvedType p : ut.paramTypes) {
				resolve(p,imports);
			}
		}  
	}

	/**
	 * The purpose of the exposed names method is capture the case when we have
	 * a define statement like this:
	 * 
	 * <pre>
	 * define tup as {int x, int y} where x < y
	 * </pre>
	 * 
	 * In this case, <code>x</code> and <code>y</code> are "exposed" --- meaning
	 * they're real names are different in some way. In this case, the aliases
	 * we have are: x->$.x and y->$.y
	 * 
	 * @param src
	 * @param t
	 * @param environment
	 */
	private static void addExposedNames(Expr src, UnresolvedType t,
			HashMap<String, Set<Expr>> environment) {
		// Extended this method to handle lists and sets etc, is very difficult.
		// The primary problem is that we need to expand expressions involved
		// names exposed in this way into quantified
		// expressions.		
		if(t instanceof UnresolvedType.Record) {
			UnresolvedType.Record tt = (UnresolvedType.Record) t;
			for(Map.Entry<String,UnresolvedType> e : tt.types.entrySet()) {
				Expr s = new Expr.RecordAccess(src, e
						.getKey(), src.attribute(Attribute.Source.class));
				addExposedNames(s,e.getValue(),environment);
				Set<Expr> aliases = environment.get(e.getKey());
				if(aliases == null) {
					aliases = new HashSet<Expr>();
					environment.put(e.getKey(),aliases);
				}
				aliases.add(s);
			}
		} else if (t instanceof UnresolvedType.Process) {			
			UnresolvedType.Process ut = (UnresolvedType.Process) t;
			addExposedNames(new Expr.UnOp(Expr.UOp.PROCESSACCESS, src),
					ut.element, environment);
		}
	}
}
