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

package wyjc.io;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.zip.*;

import wyjc.attributes.WhileyDefine;
import wyjc.attributes.WhileyType;
import wyjc.attributes.WhileyVersion;
import wyil.*;
import static wyil.util.SyntaxError.*;
import wyil.util.*;
import wyil.lang.*;
import wyil.lang.Code.*;
import static wyil.lang.Block.*;
import wyjc.runtime.BigRational;
import wyjvm.attributes.Code.Handler;
import wyjvm.io.BinaryInputStream;
import wyjvm.io.BinaryOutputStream;
import wyjvm.lang.*;
import static wyjvm.lang.JvmTypes.*;

/**
 * The purpose of the class file builder is to construct a jvm class file from a
 * given WhileyFile.
 * 
 * @author djp
 */
public class ClassFileBuilder {
	protected int CLASS_VERSION = 49;
	protected int WHILEY_MINOR_VERSION;
	protected int WHILEY_MAJOR_VERSION;
	protected ModuleLoader loader;	
	protected String filename;
	protected JvmType.Clazz owner;
	
	public ClassFileBuilder(ModuleLoader loader, int whileyMajorVersion, int whileyMinorVersion) {
		this.loader = loader;
		this.WHILEY_MINOR_VERSION = whileyMinorVersion;
		this.WHILEY_MAJOR_VERSION = whileyMajorVersion;
	}

	public ClassFile build(Module module) {
		owner = new JvmType.Clazz(module.id().pkg().toString(),
				module.id().module().toString());
		ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
		modifiers.add(Modifier.ACC_PUBLIC);
		modifiers.add(Modifier.ACC_FINAL);
		ClassFile cf = new ClassFile(49, owner, JAVA_LANG_OBJECT,
				new ArrayList<JvmType.Clazz>(), modifiers);
	
		this.filename = module.filename();
		
		boolean addMainLauncher = false;		
				
		for(Module.ConstDef cd : module.constants()) {	
			// FIXME: this is an ugly hack for now
			ArrayList<BytecodeAttribute> attrs = new ArrayList<BytecodeAttribute>();
			for(Attribute a : cd.attributes()) {
				if(a instanceof BytecodeAttribute) {
					attrs.add((BytecodeAttribute)a);
				}
			}
			WhileyDefine wd = new WhileyDefine(cd.name(),cd.constant(),attrs);
			cf.attributes().add(wd);
		}
		
		for(Module.TypeDef td : module.types()) {
			// FIXME: this is an ugly hack for now
			ArrayList<BytecodeAttribute> attrs = new ArrayList<BytecodeAttribute>();
			for(Attribute a : td.attributes()) {
				if(a instanceof BytecodeAttribute) {
					attrs.add((BytecodeAttribute)a);
				}
			}
			Type t = td.type();			
			WhileyDefine wd = new WhileyDefine(td.name(),t,attrs);
			cf.attributes().add(wd);
		}
		
		HashMap<Constant,Integer> constants = new HashMap<Constant,Integer>();
		for(Module.Method method : module.methods()) {				
			if(method.name().equals("main")) { 
				addMainLauncher = true;
			}			
			cf.methods().addAll(build(method, constants));			
		}		
			
		buildConstants(constants,cf);		
				
		if(addMainLauncher) {
			cf.methods().add(buildMainLauncher(owner));
		}
		
		cf.attributes().add(
				new WhileyVersion(WHILEY_MAJOR_VERSION, WHILEY_MINOR_VERSION));
		
		return cf;
	}	
	
	public void buildConstants(HashMap<Constant,Integer> constants, ClassFile cf) {						
		buildCoercions(constants,cf);
		buildValues(constants,cf);
	}
	
	public void buildCoercions(HashMap<Constant,Integer> constants, ClassFile cf) {
		HashSet<Constant> done = new HashSet<Constant>();
		HashMap<Constant,Integer> original = constants;
		// this could be a little more efficient I think!!		
		while(done.size() != constants.size()) {
			// We have to clone the constants map, since it may be expanded as a
			// result of buildCoercion(). This will occur if the coercion
			// constructed requires a helper coercion that was not in the
			// original constants map.  
			HashMap<Constant,Integer> nconstants = new HashMap<Constant,Integer>(constants);		
			for(Map.Entry<Constant,Integer> entry : constants.entrySet()) {
				Constant e = entry.getKey();
				if(!done.contains(e) && e instanceof Coercion) {
					Coercion c = (Coercion) e;
					buildCoercion(c.from,c.to,entry.getValue(),nconstants,cf);
				} 
				done.add(e);
			}
			constants = nconstants;
		}
		original.putAll(constants);
	}
	
	public void buildValues(HashMap<Constant,Integer> constants, ClassFile cf) {
		int nvalues = 0;
		ArrayList<Bytecode> bytecodes = new ArrayList<Bytecode>();
		
		for(Map.Entry<Constant,Integer> entry : constants.entrySet()) {			
			Constant c = entry.getKey();
			if(c instanceof ValueConst) {
				nvalues++;
				Value constant = ((ValueConst)c).value;
				int index = entry.getValue();

				// First, create the static final field that will hold this constant 
				String name = "constant$" + index;
				ArrayList<Modifier> fmods = new ArrayList<Modifier>();
				fmods.add(Modifier.ACC_PRIVATE);
				fmods.add(Modifier.ACC_STATIC);
				fmods.add(Modifier.ACC_FINAL);
				JvmType type = convertType(constant.type());
				ClassFile.Field field = new ClassFile.Field(name, type, fmods);
				cf.fields().add(field);

				// Now, create code to intialise this field
				translate(constant,0,bytecodes);
				bytecodes.add(new Bytecode.PutField(owner, name, type, Bytecode.STATIC));
			} 
		}
		
		if(nvalues > 0) {
			// create static initialiser method, but only if we really need to.
			bytecodes.add(new Bytecode.Return(null));

			ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
			modifiers.add(Modifier.ACC_PUBLIC);
			modifiers.add(Modifier.ACC_STATIC);
			modifiers.add(Modifier.ACC_SYNTHETIC);
			JvmType.Function ftype = new JvmType.Function(new JvmType.Void());
			ClassFile.Method clinit = new ClassFile.Method("<clinit>", ftype, modifiers);
			cf.methods().add(clinit);

			// finally add code for staticinitialiser method
			wyjvm.attributes.Code code = new wyjvm.attributes.Code(bytecodes,new ArrayList(),clinit);
			clinit.attributes().add(code);				
		}
	}
		
	public ClassFile.Method buildMainLauncher(JvmType.Clazz owner) {
		ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
		modifiers.add(Modifier.ACC_PUBLIC);
		modifiers.add(Modifier.ACC_STATIC);
		modifiers.add(Modifier.ACC_SYNTHETIC);
		
		// Make the initial system process.
		JvmType.Function ft1 =
		    new JvmType.Function(T_VOID, new JvmType.Array(JAVA_LANG_STRING));
		ClassFile.Method cm = new ClassFile.Method("main", ft1, modifiers);
		JvmType.Array strArr = new JvmType.Array(JAVA_LANG_STRING);
		ArrayList<Bytecode> codes = new ArrayList<Bytecode>();
		ft1 = new JvmType.Function(WHILEYPROCESS);

		codes.add(new Bytecode.Invoke(WHILEYPROCESS, "newSystemProcess", ft1,
		    Bytecode.STATIC));
		codes.add(new Bytecode.Dup(WHILEYPROCESS));
		codes.add(new Bytecode.Dup(WHILEYPROCESS));
		codes.add(new Bytecode.Store(1, WHILEYPROCESS));
		
		// Get the System::main method out.
		Type.Fun wyft = Type.T_FUN(WHILEY_SYSTEM_T,
				Type.T_VOID, Type.T_LIST(Type.T_STRING));
		JvmType.Function ftype =
		    new JvmType.Function(JAVA_LANG_REFLECT_METHOD, JAVA_LANG_STRING,
		        JAVA_LANG_STRING);
		codes.add(new Bytecode.LoadConst(owner.toString()));
	  codes.add(new Bytecode.LoadConst(nameMangle("main", wyft)));
		codes.add(new Bytecode.Invoke(WHILEYIO, "functionRef", ftype,
		    Bytecode.STATIC));
		
		// Create the System::main arguments list.
		codes.add(new Bytecode.LoadConst(2));
		codes.add(new Bytecode.New(JAVA_LANG_OBJECT_ARRAY));
		codes.add(new Bytecode.Dup(JAVA_LANG_OBJECT_ARRAY));
		
		// Save the command line arguments into an ArrayList.
		codes.add(new Bytecode.LoadConst(1));
		codes.add(new Bytecode.Load(0, strArr));
		JvmType.Function ft2 =
		    new JvmType.Function(WHILEYLIST, new JvmType.Array(JAVA_LANG_STRING));
		codes.add(new Bytecode.Invoke(WHILEYUTIL, "fromStringList", ft2,
		    Bytecode.STATIC));
		
		// Save the ArrayList into the arguments list.
		codes.add(new Bytecode.ArrayStore(JAVA_LANG_OBJECT_ARRAY));
		
		// Call the send method.
		ftype = new JvmType.Function(T_VOID, WHILEYMESSAGER,
				JAVA_LANG_REFLECT_METHOD, JAVA_LANG_OBJECT_ARRAY);
		codes.add(new Bytecode.Invoke(WHILEYMESSAGER, "sendAsync", ftype,
		    Bytecode.VIRTUAL));
		
		// Add return.
		codes.add(new Bytecode.Return(null));

		wyjvm.attributes.Code code =
		    new wyjvm.attributes.Code(codes, new ArrayList<Handler>(), cm);
		cm.attributes().add(code);

		return cm;
	}
	
	public List<ClassFile.Method> build(Module.Method method,
			HashMap<Constant, Integer> constants) {
		ArrayList<ClassFile.Method> methods = new ArrayList<ClassFile.Method>();
		int num = 1;
		for(Module.Case c : method.cases()) {
			methods.add(build(num++,c,method,constants));
		}
		return methods;
	}
	
	public ClassFile.Method build(int caseNum, Module.Case mcase,
			Module.Method method, HashMap<Constant,Integer> constants) {		
		
		ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
		if(method.isPublic()) {
			modifiers.add(Modifier.ACC_PUBLIC);
		}
		modifiers.add(Modifier.ACC_STATIC);		
		JvmType.Function ft = convertFunType(method.type());		
		String name = nameMangle(method.name(),method.type());
		/* need to put this back somehow?
		if(method.cases().size() > 1) {
			name = name + "$" + caseNum;
		}
		*/
				
		ClassFile.Method cm = new ClassFile.Method(name,ft,modifiers);		
		for(Attribute a : mcase.attributes()) {
			if(a instanceof BytecodeAttribute) {
				// FIXME: this is a hack
				cm.attributes().add((BytecodeAttribute)a);
			}
		}
				
		ArrayList<Bytecode> codes = translate(mcase,constants);
		wyjvm.attributes.Code code = new wyjvm.attributes.Code(codes,new ArrayList(),cm);
		cm.attributes().add(code);		
		
		return cm;
	}
	
	public ArrayList<Bytecode> translate(Module.Case mcase, HashMap<Constant,Integer> constants) {
		ArrayList<Bytecode> bytecodes = new ArrayList<Bytecode>();				
		translate(mcase.body(),mcase.locals().size(),constants,bytecodes);				
		return bytecodes;
	}

	/**
	 * Translate the given block into bytecodes.
	 * 
	 * @param blk
	 *            --- wyil block to be translated.
	 * @param freeSlot
	 *            --- identifies the first unsused bytecode register.
	 * @param bytecodes
	 *            --- list to insert bytecodes into *
	 */
	public void translate(Block blk, int freeSlot, HashMap<Constant,Integer> constants,
			ArrayList<Bytecode> bytecodes) {
		for (Entry s : blk) {
			freeSlot = translate(s, freeSlot, constants, bytecodes);
		}
	}
	
	public int translate(Entry entry, int freeSlot,
			HashMap<Constant,Integer> constants, ArrayList<Bytecode> bytecodes) {
		try {
			Code code = entry.code;
			if(code instanceof Assert) {
				 // translate((Assert)code,freeSlot,bytecodes);
			} else if(code instanceof BinOp) {
				 translate((BinOp)code,entry,freeSlot,bytecodes);
			} else if(code instanceof Convert) {
				 translate((Convert)code,freeSlot,constants,bytecodes);
			} else if(code instanceof Const) {
				translate((Const) code, freeSlot, constants, bytecodes);
			} else if(code instanceof Debug) {
				 translate((Debug)code,freeSlot,bytecodes);
			} else if(code instanceof Destructure) {
				 translate((Destructure)code,freeSlot,bytecodes);
			} else if(code instanceof DictLoad) {
				 translate((DictLoad)code,freeSlot,bytecodes);
			} else if(code instanceof End) {
				 translate((End)code,freeSlot,bytecodes);
			} else if(code instanceof ExternJvm) {
				translate((ExternJvm)code,freeSlot,bytecodes);
			} else if(code instanceof Fail) {
				 translate((Fail)code,freeSlot,bytecodes);
			} else if(code instanceof FieldLoad) {
				 translate((FieldLoad)code,freeSlot,bytecodes);
			} else if(code instanceof ForAll) {
				 freeSlot = translate((ForAll)code,freeSlot,bytecodes);
			} else if(code instanceof Goto) {
				 translate((Goto)code,freeSlot,bytecodes);
			} else if(code instanceof IfGoto) {
				translate((IfGoto) code, entry, freeSlot, bytecodes);
			} else if(code instanceof IfType) {
				translate((IfType) code, entry, freeSlot, constants, bytecodes);
			} else if(code instanceof IndirectInvoke) {
				 translate((IndirectInvoke)code,freeSlot,bytecodes);
			} else if(code instanceof IndirectSend) {
				 translate((IndirectSend)code,freeSlot,bytecodes);
			} else if(code instanceof Invoke) {
				 translate((Invoke)code,freeSlot,bytecodes);
			} else if(code instanceof Invert) {
				 translate((Invert)code,freeSlot,bytecodes);
			} else if(code instanceof Label) {
				translate((Label)code,freeSlot,bytecodes);
			} else if(code instanceof ListAppend) {
				 translate((ListAppend)code,entry,freeSlot,bytecodes);
			} else if(code instanceof ListLength) {
				 translate((ListLength)code,entry,freeSlot,bytecodes);
			} else if(code instanceof SubList) {
				 translate((SubList)code,entry,freeSlot,bytecodes);
			} else if(code instanceof ListLoad) {
				 translate((ListLoad)code,freeSlot,bytecodes);
			} else if(code instanceof Load) {
				 translate((Load)code,freeSlot,bytecodes);
			} else if(code instanceof Loop) {
				 translate((Loop)code,freeSlot,bytecodes);
			} else if(code instanceof Update) {
				 translate((Update)code,freeSlot,bytecodes);
			} else if(code instanceof NewDict) {
				 translate((NewDict)code,freeSlot,bytecodes);
			} else if(code instanceof NewList) {
				 translate((NewList)code,freeSlot,bytecodes);
			} else if(code instanceof NewRecord) {
				 translate((NewRecord)code,freeSlot,bytecodes);
			} else if(code instanceof NewSet) {
				 translate((NewSet)code,freeSlot,bytecodes);
			} else if(code instanceof NewTuple) {
				 translate((NewTuple)code,freeSlot,bytecodes);
			} else if(code instanceof Negate) {
				 translate((Negate)code,freeSlot,bytecodes);
			} else if(code instanceof ProcLoad) {
				 translate((ProcLoad)code,freeSlot,bytecodes);
			} else if(code instanceof Return) {
				 translate((Return)code,freeSlot,bytecodes);
			} else if(code instanceof Skip) {
				// do nothing
			} else if(code instanceof Send) {
				 translate((Send)code,freeSlot,bytecodes);
			} else if(code instanceof SetLength) {
				 translate((SetLength)code,entry,freeSlot,bytecodes);
			} else if(code instanceof SetUnion) {
				 translate((SetUnion)code,entry,freeSlot,bytecodes);
			} else if(code instanceof SetIntersect) {
				 translate((SetIntersect)code,entry,freeSlot,bytecodes);
			} else if(code instanceof SetDifference) {
				 translate((SetDifference)code,entry,freeSlot,bytecodes);
			} else if(code instanceof StringAppend) {
				 translate((StringAppend)code,entry,freeSlot,bytecodes);
			} else if(code instanceof StringLoad) {
				 translate((StringLoad)code,entry,freeSlot,bytecodes);
			} else if(code instanceof StringLength) {
				 translate((StringLength)code,entry,freeSlot,bytecodes);
			} else if(code instanceof SubString) {
				 translate((SubString)code,entry,freeSlot,bytecodes);
			} else if(code instanceof Store) {
				 translate((Store)code,freeSlot,bytecodes);
			} else if(code instanceof Switch) {
				 translate((Switch)code,entry,freeSlot,bytecodes);
			} else if(code instanceof Spawn) {
				 translate((Spawn)code,freeSlot,bytecodes);
			} else if(code instanceof Throw) {
				 translate((Throw)code,freeSlot,bytecodes);
			} else {
				syntaxError("unknown wyil code encountered (" + code + ")", filename, entry);
			}
			
		} catch (SyntaxError ex) {
			throw ex;
		} catch (Exception ex) {		
			syntaxError("internal failure", filename, entry, ex);
		}
		
		return freeSlot;
	}
	
	public void translate(Code.Const c, int freeSlot,
			HashMap<Constant,Integer> constants,
			ArrayList<Bytecode> bytecodes) {
		
		Value constant = c.constant;
		if (constant instanceof Value.Rational || constant instanceof Value.Bool
				|| constant instanceof Value.Null || constant instanceof Value.Byte) {
			translate(constant,freeSlot,bytecodes);					
		} else {
			int id = ValueConst.get(constant,constants);			
			String name = "constant$" + id;
			JvmType type = convertType(constant.type());
			bytecodes.add(new Bytecode.GetField(owner, name, type, Bytecode.STATIC));
		}		
	}
	
	public void translate(Code.Convert c, int freeSlot,
			HashMap<Constant, Integer> constants, ArrayList<Bytecode> bytecodes) {		
		addCoercion(c.from,c.to,freeSlot,constants,bytecodes);		
	}
	
	
	public void translate(Code.Store c, int freeSlot,			
			ArrayList<Bytecode> bytecodes) {		
		JvmType type = convertType(c.type);
		//addIncRefs(c.type,bytecodes);		
		bytecodes.add(new Bytecode.Store(c.slot, type));				
	}

	public void translate(Code.Update c, int freeSlot,ArrayList<Bytecode> bytecodes) {
		
		// Now, my general feeling is that the multistore bytecode could use
		// some work. Essentially, to simplify this process of figuring our what
		// is being updated.
		
		// First, check if this is updating the process' state
		Type type = c.type;
				
		if(c.slot == 0 && Type.isSubtype(Type.T_PROCESS(Type.T_ANY), type)) {
			Type.Process p = (Type.Process) type;
			type = p.element();
		}
		
		// Second, determine type of value being assigned
		
		ArrayList<String> fields = c.fields;
		int fi = 0;						
		Type iter = type;
		// ok, this is such an ugly hack...
		ArrayList<Type> indices = new ArrayList<Type>();
		for(int i=0;i!=c.level;++i) {
			if(Type.isSubtype(Type.T_DICTIONARY(Type.T_ANY, Type.T_ANY),iter)) {
				Type.Dictionary dict = Type.effectiveDictionaryType(iter);				
				indices.add(dict.key());
				iter = dict.value();
			} else if(Type.isSubtype(Type.T_STRING,iter)) {
				iter = Type.T_CHAR;
				indices.add(Type.T_INT);
			} else if(Type.isSubtype(Type.T_LIST(Type.T_ANY),iter)) {
				Type.List list = Type.effectiveListType(iter);
				iter = list.element();
				indices.add(Type.T_INT);
			} else {
				Type.Record rec = Type.effectiveRecordType(iter);
				String field = fields.get(fi++);
				iter = rec.fields().get(field);
			}	
		}
						
		int indexSlot = freeSlot;
		freeSlot += indices.size();
		// Third, store the value to be assigned				
		JvmType val_t = convertType(iter);		
		bytecodes.add(new Bytecode.Store(freeSlot++,val_t));
		
		for(int i=indices.size()-1;i>=0;--i) {
			JvmType t = convertType(indices.get(i));
			bytecodes.add(new Bytecode.Store(indexSlot+i,t));
		}
		
		bytecodes.add(new Bytecode.Load(c.slot, convertType(c.type)));
		
		// Fourth, finally process the assignment path and update the object in
		// question.		
		multiStoreHelper(c.type,c.level-1,fields.iterator(),indexSlot,val_t,freeSlot, bytecodes);		
		bytecodes.add(new Bytecode.Store(c.slot, convertType(c.type)));
	}

	public void multiStoreHelper(Type type, int level,
			Iterator<String> fields, int indexSlot, JvmType val_t, int freeSlot, 
			ArrayList<Bytecode> bytecodes) {
		
		// This method is major ugly. I'm sure there must be a better way of
		// doing this. Probably, if I change the multistore bytecode, that would
		// help.
		
		if(Type.isSubtype(Type.T_PROCESS(Type.T_ANY), type)) {			
			Type.Process pt = (Type.Process) type;
			bytecodes.add(new Bytecode.Dup(WHILEYPROCESS));
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT);		
			bytecodes.add(new Bytecode.Invoke(WHILEYPROCESS, "getState", ftype,
					Bytecode.VIRTUAL));							
			addReadConversion(pt.element(),bytecodes);
			multiStoreHelper(pt.element(),level,fields,indexSlot,val_t,freeSlot,bytecodes);						
			ftype = new JvmType.Function(WHILEYPROCESS,JAVA_LANG_OBJECT);		
			bytecodes.add(new Bytecode.Invoke(WHILEYPROCESS, "setState", ftype,
					Bytecode.VIRTUAL));
			
		} else if(Type.isSubtype(Type.T_DICTIONARY(Type.T_ANY, Type.T_ANY),type)) {
			Type.Dictionary dict = Type.effectiveDictionaryType(type);				
			
			if(level != 0) {				
				bytecodes.add(new Bytecode.Dup(WHILEYMAP));				
				bytecodes.add(new Bytecode.Load(indexSlot,convertType(dict.key())));
				
				JvmType.Function ftype = new JvmType.Function(
						JAVA_LANG_OBJECT, WHILEYMAP, JAVA_LANG_OBJECT);
				bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "get", ftype,
					Bytecode.STATIC));				
				addReadConversion(dict.value(),bytecodes);
				multiStoreHelper(dict.value(),level-1,fields,indexSlot+1,val_t,freeSlot,bytecodes);
				bytecodes.add(new Bytecode.Load(indexSlot,convertType(dict.key())));
				bytecodes.add(new Bytecode.Swap());
			} else {
				bytecodes.add(new Bytecode.Load(indexSlot,convertType(dict.key())));
				bytecodes.add(new Bytecode.Load(indexSlot+1, val_t));	
				addWriteConversion(dict.value(),bytecodes);
			}
						
			JvmType.Function ftype = new JvmType.Function(WHILEYMAP,
					WHILEYMAP,JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);						
			bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "put", ftype,
					Bytecode.STATIC));			
						
		} else if(Type.isSubtype(Type.T_STRING,type)) {
			
			// assert: level must be zero here
			bytecodes.add(new Bytecode.Load(indexSlot, BIG_INTEGER));
			bytecodes.add(new Bytecode.Load(indexSlot+1, val_t));
			addWriteConversion(Type.T_INT,bytecodes);			

			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_STRING,
					JAVA_LANG_STRING,BIG_INTEGER,T_CHAR);			
			bytecodes.add(new Bytecode.Invoke(WHILEYUTIL, "set", ftype,
					Bytecode.STATIC));						
			
		} else if(Type.isSubtype(Type.T_LIST(Type.T_ANY),type)) {
			Type.List list = Type.effectiveListType(type);				
										
			if(level != 0) {
				bytecodes.add(new Bytecode.Dup(WHILEYLIST));											
				bytecodes.add(new Bytecode.Load(indexSlot,BIG_INTEGER));				
				JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
						WHILEYLIST,BIG_INTEGER);
				bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "get", ftype,
						Bytecode.STATIC));				
				addReadConversion(list.element(),bytecodes);
				multiStoreHelper(list.element(),level-1,fields,indexSlot+1,val_t,freeSlot,bytecodes);				
				bytecodes.add(new Bytecode.Load(indexSlot,BIG_INTEGER));
				bytecodes.add(new Bytecode.Swap());
			} else {				
				bytecodes.add(new Bytecode.Load(indexSlot,BIG_INTEGER));
				bytecodes.add(new Bytecode.Load(indexSlot+1, val_t));
				addWriteConversion(list.element(),bytecodes);
			}
			
			JvmType.Function ftype = new JvmType.Function(WHILEYLIST,
					WHILEYLIST,BIG_INTEGER,JAVA_LANG_OBJECT);			
			bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "set", ftype,
					Bytecode.STATIC));							
		} else {
			Type.Record rec = Type.effectiveRecordType(type);			
			String field = fields.next();			
			if(level != 0) {				
				bytecodes.add(new Bytecode.Dup(WHILEYRECORD));				
				bytecodes.add(new Bytecode.LoadConst(field));				
				JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,WHILEYRECORD,JAVA_LANG_STRING);
				bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"get",ftype,Bytecode.STATIC));
				addReadConversion(rec.fields().get(field),bytecodes);
				multiStoreHelper(rec.fields().get(field),level-1,fields,indexSlot,val_t,freeSlot,bytecodes);				
				bytecodes.add(new Bytecode.LoadConst(field));
				bytecodes.add(new Bytecode.Swap());
			} else {
				bytecodes.add(new Bytecode.LoadConst(field));				
				bytecodes.add(new Bytecode.Load(indexSlot, val_t));
				addWriteConversion(rec.fields().get(field),bytecodes);
			}
			
			JvmType.Function ftype = new JvmType.Function(WHILEYRECORD,WHILEYRECORD,JAVA_LANG_STRING,JAVA_LANG_OBJECT);						
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"put",ftype,Bytecode.STATIC));					
		}
	}
	
	public void translate(Code.Return c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		if (c.type == Type.T_VOID) {
			bytecodes.add(new Bytecode.Return(null));
		} else {
			bytecodes.add(new Bytecode.Return(convertType(c.type)));
		}
	}

	public void translate(Code.Throw c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {			
		bytecodes.add(new Bytecode.New(WHILEYEXCEPTION));
		bytecodes.add(new Bytecode.DupX1());
		bytecodes.add(new Bytecode.Swap());				
		JvmType.Function ftype = new JvmType.Function(T_VOID,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYEXCEPTION, "<init>", ftype,
				Bytecode.SPECIAL));		
		bytecodes.add(new Bytecode.Throw());
	}
	
	public void translate(Code.Switch c, Block.Entry entry, int freeSlot,
			ArrayList<Bytecode> bytecodes) {

		ArrayList<Pair<Integer, String>> cases = new ArrayList();
		boolean canUseSwitchBytecode = true;
		for (Pair<Value, String> p : c.branches) {
			// first, check whether the switch value is indeed an integer.
			Value v = (Value) p.first();
			if (!(v instanceof Value.Integer)) {
				canUseSwitchBytecode = false;
				break;
			}
			// second, check whether integer value can fit into a Java int
			Value.Integer vi = (Value.Integer) v;
			int iv = vi.value.intValue();
			if (!BigInteger.valueOf(iv).equals(vi.value)) {
				canUseSwitchBytecode = false;
				break;
			}
			// ok, we're all good so far
			cases.add(new Pair(iv, p.second()));
		}

		if (canUseSwitchBytecode) {
			JvmType.Function ftype = new JvmType.Function(T_INT);
			bytecodes.add(new Bytecode.Invoke(BIG_INTEGER, "intValue", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Switch(c.defaultTarget, cases));
		} else {
			// ok, in this case we have to fall back to series of the if
			// conditions. Not ideal.  
			bytecodes.add(new Bytecode.Store(freeSlot, convertType(c.type)));
			for (Pair<Value, String> p : c.branches) {
				Value value = p.first();
				String target = p.second();
				translate(value,freeSlot+1,bytecodes);
				bytecodes.add(new Bytecode.Load(freeSlot, convertType(c.type)));				
				// Now, construct fake bytecode to do the comparison.
				// FIXME: bug if types require some kind of coercion
				Code.IfGoto ifgoto = Code.IfGoto(value.type(),Code.COp.EQ,target);			
				translate(ifgoto,entry,freeSlot+1,bytecodes);
			}
			bytecodes.add(new Bytecode.Goto(c.defaultTarget));
		}
	}
	
	public void translate(Code.IfGoto c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {	
				
		JvmType type = convertType(c.type);
		if(c.type == Type.T_BOOL) {
			// boolean is a special case, since it is not implemented as an
			// object on the JVM stack. Therefore, we need to use the "if_cmp"
			// bytecode, rather than calling .equals() and using "if" bytecode.
			switch(c.op) {
			case EQ:				
				bytecodes.add(new Bytecode.IfCmp(Bytecode.IfCmp.EQ, type, c.target));
				break;			
			case NEQ:				
				bytecodes.add(new Bytecode.IfCmp(Bytecode.IfCmp.NE, type, c.target));				
				break;			
			}
		} else if(c.type == Type.T_CHAR || c.type == Type.T_BYTE) {
			int op;
			switch(c.op) {
			case EQ:				
				op = Bytecode.IfCmp.EQ;
				break;
			case NEQ:				
				op = Bytecode.IfCmp.NE;
				break;
			case LT:				
				op = Bytecode.IfCmp.LT;
				break;
			case LTEQ:				
				op = Bytecode.IfCmp.LE;
				break;
			case GT:				
				op = Bytecode.IfCmp.GT;
				break;
			case GTEQ:				
				op = Bytecode.IfCmp.GE;
				break;
			default:
				syntaxError("unknown if condition encountered",filename,stmt);
				return;
			}
			bytecodes.add(new Bytecode.IfCmp(op, T_BYTE,c.target));
		} else {
			// Non-primitive case. Just use the Object.equals() method, followed
			// by "if" bytecode.			
			int op;
			switch(c.op) {
			case EQ:
			{				
				if(Type.isSubtype(c.type, Type.T_NULL)) {
					// this indicates an interesting special case. The left
					// handside of this equality can be null. Therefore, we
					// cannot directly call "equals()" on this method, since
					// this would cause a null pointer exception!
					JvmType.Function ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);
					bytecodes.add(new Bytecode.Invoke(WHILEYUTIL, "equals", ftype,
							Bytecode.STATIC));
				} else {
					JvmType.Function ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);
					bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "equals", ftype,
							Bytecode.VIRTUAL));								
				}
				op = Bytecode.If.NE;
				break;
			}
			case NEQ:
			{
				if (Type.isSubtype(c.type, Type.T_NULL)) {
					// this indicates an interesting special case. The left
					// handside of this equality can be null. Therefore, we
					// cannot directly call "equals()" on this method, since
					// this would cause a null pointer exception!
					JvmType.Function ftype = new JvmType.Function(T_BOOL,
							JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
					bytecodes.add(new Bytecode.Invoke(WHILEYUTIL, "equals",
							ftype, Bytecode.STATIC));
				} else {
					JvmType.Function ftype = new JvmType.Function(T_BOOL,
							JAVA_LANG_OBJECT);
					bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type,
							"equals", ftype, Bytecode.VIRTUAL));
				}
				op = Bytecode.If.EQ;
				break;
			}
			case LT:
			{							
				JvmType.Function ftype = new JvmType.Function(T_INT,type);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type, "compareTo", ftype,
						Bytecode.VIRTUAL));				
				op = Bytecode.If.LT;			
				break;
			}
			case LTEQ:
			{			
				JvmType.Function ftype = new JvmType.Function(T_INT,type);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type,
						"compareTo", ftype, Bytecode.VIRTUAL));			
				op = Bytecode.If.LE;
				break;
			}
			case GT:
			{						
				JvmType.Function ftype = new JvmType.Function(T_INT, type);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type,
						"compareTo", ftype, Bytecode.VIRTUAL));				
				op = Bytecode.If.GT;
				break;
			}
			case GTEQ:
			{						
				JvmType.Function ftype = new JvmType.Function(T_INT,type);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type,
						"compareTo", ftype, Bytecode.VIRTUAL));				
				op = Bytecode.If.GE;
				break;
			}
			case SUBSETEQ:
			{
				JvmType.Function ftype = new JvmType.Function(T_BOOL,WHILEYSET,WHILEYSET);
				bytecodes.add(new Bytecode.Invoke(WHILEYSET, "subsetEq", ftype,
						Bytecode.STATIC));
				op = Bytecode.If.NE;
				break;
			}
			case SUBSET:
			{
				JvmType.Function ftype = new JvmType.Function(T_BOOL,WHILEYSET,WHILEYSET);
				bytecodes.add(new Bytecode.Invoke(WHILEYSET, "subset", ftype,
						Bytecode.STATIC));
				op = Bytecode.If.NE;
				break;
			}
			case ELEMOF:
			{
				JvmType.Function ftype = new JvmType.Function(T_BOOL,
						JAVA_LANG_OBJECT);
				bytecodes.add(new Bytecode.Swap());
				bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "contains",
						ftype, Bytecode.INTERFACE));
				op = Bytecode.If.NE;
				break;
			}			
			
			default:
				syntaxError("unknown if condition encountered",filename,stmt);
				return;
			}
			
			// do the jump
			bytecodes.add(new Bytecode.If(op, c.target));
		}
	}
	
	public void translate(Code.IfType c, Entry stmt, int freeSlot,
			HashMap<Constant,Integer> constants, ArrayList<Bytecode> bytecodes) {						
		
		if(c.slot >= 0) {
			// In this case, we're updating the type of a local variable. To
			// make this work, we must update the JVM type of that slot as well
			// using a checkcast. 
			String exitLabel = freshLabel();
			String trueLabel = freshLabel();
					
			bytecodes.add(new Bytecode.Load(c.slot, convertType(c.type)));
			translateTypeTest(trueLabel, c.type, c.test, bytecodes, constants);

			Type gdiff = Type.leastDifference(c.type,c.test);			
			bytecodes.add(new Bytecode.Load(c.slot, convertType(c.type)));
			// now, add checkcase
			addReadConversion(gdiff,bytecodes);		
			bytecodes.add(new Bytecode.Store(c.slot,convertType(gdiff)));							
			bytecodes.add(new Bytecode.Goto(exitLabel));
			bytecodes.add(new Bytecode.Label(trueLabel));

			Type glb = Type.greatestLowerBound(c.type, c.test);
			bytecodes.add(new Bytecode.Load(c.slot, convertType(c.type)));
			// now, add checkcase
			addReadConversion(glb,bytecodes);		
			bytecodes.add(new Bytecode.Store(c.slot,convertType(glb)));			
			bytecodes.add(new Bytecode.Goto(c.target));
			bytecodes.add(new Bytecode.Label(exitLabel));
		} else {
			// This is the easy case. We're not updating the type of a local
			// variable; rather we're just type testing a value on the stack.
			translateTypeTest(c.target, c.type, c.test, bytecodes, constants);
		}
	}
	
	// The purpose of this method is to translate a type test. We're testing to
	// see whether what's on the top of the stack (the value) is a subtype of
	// the type being tested.  
	protected void translateTypeTest(String trueTarget, Type src, Type test,
			ArrayList<Bytecode> bytecodes, HashMap<Constant,Integer> constants) {		
		
		// First, try for the easy cases
		
		if (test instanceof Type.Null) {
			// Easy case		
			bytecodes.add(new Bytecode.If(Bytecode.If.NULL, trueTarget));
		} else if(test instanceof Type.Bool) {
			bytecodes.add(new Bytecode.InstanceOf(JAVA_LANG_BOOLEAN));			
			bytecodes.add(new Bytecode.If(Bytecode.If.NE, trueTarget));
		} else if(test instanceof Type.Char) {
			bytecodes.add(new Bytecode.InstanceOf(JAVA_LANG_CHARACTER));			
			bytecodes.add(new Bytecode.If(Bytecode.If.NE, trueTarget));			
		} else if(test instanceof Type.Int) {
			bytecodes.add(new Bytecode.InstanceOf(BIG_INTEGER));			
			bytecodes.add(new Bytecode.If(Bytecode.If.NE, trueTarget));
		} else if(test instanceof Type.Real) {
			bytecodes.add(new Bytecode.InstanceOf(BIG_RATIONAL));			
			bytecodes.add(new Bytecode.If(Bytecode.If.NE, trueTarget));
		} else if(test instanceof Type.Strung) {
			bytecodes.add(new Bytecode.InstanceOf(JAVA_LANG_STRING));			
			bytecodes.add(new Bytecode.If(Bytecode.If.NE, trueTarget));
			
		} else {
			// Fall-back to an external (recursive) check			
			Value constant = Value.V_TYPE(test);
			int id = ValueConst.get(constant,constants);			
			String name = "constant$" + id;

			bytecodes.add(new Bytecode.GetField(owner, name, WHILEYTYPE, Bytecode.STATIC));

			JvmType.Function ftype = new JvmType.Function(T_BOOL,convertType(src),WHILEYTYPE);
			bytecodes.add(new Bytecode.Invoke(WHILEYUTIL, "instanceOf",
					ftype, Bytecode.STATIC));
			bytecodes.add(new Bytecode.If(Bytecode.If.NE, trueTarget));
		}
	}	

	public void translate(Code.Loop c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Label(c.target + "$head"));
	}
	
	protected void translate(Code.End end,			
			int freeSlot, ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Goto(end.label + "$head"));
		bytecodes.add(new Bytecode.Label(end.label));
	}
	
	public int translate(Code.ForAll c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {	
		Type elementType;
		
		// FIXME: following is broken because we need to use the effective type.

		if (c.type instanceof Type.Set) {
			elementType = ((Type.Set) c.type).element();
		} else {
			elementType = ((Type.List) c.type).element();
		}

		JvmType.Function ftype = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "iterator",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.Store(freeSlot, JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Label(c.target + "$head"));
		ftype = new JvmType.Function(T_BOOL);
		bytecodes.add(new Bytecode.Load(freeSlot, JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "hasNext", ftype,
				Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.If(Bytecode.If.EQ, c.target));
		bytecodes.add(new Bytecode.Load(freeSlot, JAVA_UTIL_ITERATOR));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next", ftype,
				Bytecode.INTERFACE));
		addReadConversion(elementType, bytecodes);
		bytecodes.add(new Bytecode.Store(c.slot, convertType(elementType)));
		
		// we need to increase the freeSlot, since we've allocated one slot to
		// hold the register.
		
		return freeSlot + 1;
	}


	public void translate(Code.Goto c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Goto(c.target));
	}
	public void translate(Code.Label c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Label(c.label));
	}
	
	public void translate(Code.Debug c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(T_VOID,JAVA_LANG_STRING);
		bytecodes.add(new Bytecode.Invoke(WHILEYUTIL, "debug", ftype,
				Bytecode.STATIC));
	}
	
	public void translate(Code.Destructure code, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		
		if(code.type instanceof Type.Tuple) {
			Type.Tuple t = (Type.Tuple) code.type;
			List<Type> elements = t.elements();
			for(int i=0;i!=elements.size();++i) {
				Type elem = elements.get(i);
				if((i+1) != elements.size()) {
					bytecodes.add(new Bytecode.Dup(BIG_RATIONAL));
				}
				JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,T_INT);
				bytecodes.add(new Bytecode.LoadConst(i));
				bytecodes.add(new Bytecode.Invoke(WHILEYTUPLE, "get", ftype,
						Bytecode.VIRTUAL));
				addReadConversion(elem,bytecodes);
				if((i+1) != elements.size()) {
					bytecodes.add(new Bytecode.Swap());
				}
			}
		} else {
			bytecodes.add(new Bytecode.Dup(BIG_RATIONAL));
			JvmType.Function ftype = new JvmType.Function(BIG_INTEGER);
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "numerator", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Swap());
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "denominator", ftype,
					Bytecode.VIRTUAL));
		}		
	}
	
	public void translate(Code.Fail c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.New(JAVA_LANG_RUNTIMEEXCEPTION));
		bytecodes.add(new Bytecode.Dup(JAVA_LANG_RUNTIMEEXCEPTION));
		bytecodes.add(new Bytecode.LoadConst(c.msg));
		JvmType.Function ftype = new JvmType.Function(T_VOID, JAVA_LANG_STRING);
		bytecodes.add(new Bytecode.Invoke(JAVA_LANG_RUNTIMEEXCEPTION, "<init>",
				ftype, Bytecode.SPECIAL));
		bytecodes.add(new Bytecode.Throw());
	}
	public void translate(Code.ExternJvm c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.addAll(c.bytecodes);
	}
		
	public void translate(Code.Load c, int freeSlot, ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Load(c.slot, convertType(c.type)));
	}
	
	public void translate(Code.DictLoad c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {					
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,WHILEYMAP,
				JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "get", ftype,
				Bytecode.STATIC));
		addReadConversion(c.type.value(),bytecodes);	
	}
	
	public void translate(Code.ListAppend c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {						
		JvmType.Function ftype;
		if(c.dir == OpDir.UNIFORM) {
			ftype = new JvmType.Function(WHILEYLIST,WHILEYLIST,WHILEYLIST);
		} else if(c.dir == OpDir.LEFT) {			
			ftype = new JvmType.Function(WHILEYLIST,WHILEYLIST,JAVA_LANG_OBJECT);
		} else {
			ftype = new JvmType.Function(WHILEYLIST,JAVA_LANG_OBJECT,WHILEYLIST);
		}													
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "append", ftype,
				Bytecode.STATIC));			
	}
	
	public void translate(Code.ListLength c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(BIG_INTEGER,WHILEYLIST);						
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "length",
				ftype, Bytecode.STATIC));								
	}
	
	public void translate(Code.SubList c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {						
		JvmType.Function ftype = new JvmType.Function(WHILEYLIST, WHILEYLIST,
				BIG_INTEGER, BIG_INTEGER);
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "sublist", ftype,
				Bytecode.STATIC));
	}	
	
	public void translate(Code.ListLoad c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
				WHILEYLIST, BIG_INTEGER);
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "get", ftype,
				Bytecode.STATIC));
		addReadConversion(c.type.element(), bytecodes);
	}
	
	public void translate(Code.FieldLoad c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.LoadConst(c.field));
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,WHILEYRECORD,JAVA_LANG_STRING);
		bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"get",ftype,Bytecode.STATIC));				
		addReadConversion(c.fieldType(),bytecodes);
	}

	public void translate(Code.BinOp c, Block.Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {				
				
		JvmType type = convertType(c.type);
		JvmType.Function ftype = new JvmType.Function(type,type);
		
		switch(c.bop) {
		case ADD:			
			bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "add", ftype,
					Bytecode.VIRTUAL));
			break;
		case SUB:			
			bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "subtract", ftype,
					Bytecode.VIRTUAL));
			break;
		case MUL:			
			bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "multiply", ftype,
					Bytecode.VIRTUAL));
			break;
		case DIV:			
			bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "divide", ftype,
					Bytecode.VIRTUAL));			
			break;
		case REM:									
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type,
						"remainder", ftype, Bytecode.VIRTUAL));			
			break;
		case RANGE:
			ftype = new JvmType.Function(WHILEYLIST,BIG_INTEGER,BIG_INTEGER);
			bytecodes.add(new Bytecode.Invoke(WHILEYUTIL,
					"range", ftype, Bytecode.STATIC));
			break;
		case BITWISEAND:
			bytecodes.add(new Bytecode.BinOp(Bytecode.BinOp.AND,T_INT));
			break;
		case BITWISEOR:
			bytecodes.add(new Bytecode.BinOp(Bytecode.BinOp.OR,T_INT));
			break;
		case BITWISEXOR:
			bytecodes.add(new Bytecode.BinOp(Bytecode.BinOp.XOR,T_INT));
			break;
		case LEFTSHIFT:
			ftype = new JvmType.Function(type,type,BIG_INTEGER);
			bytecodes.add(new Bytecode.Invoke(WHILEYUTIL,
					"leftshift", ftype, Bytecode.STATIC));
			break;
		case RIGHTSHIFT:
			ftype = new JvmType.Function(type,type,BIG_INTEGER);
			bytecodes.add(new Bytecode.Invoke(WHILEYUTIL,
					"rightshift", ftype, Bytecode.STATIC));
			break;
		default:
			syntaxError("unknown binary expression encountered",filename,stmt);
		}		
	}

	public void translate(Code.SetUnion c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {		
		JvmType.Function ftype;
		if(c.dir == OpDir.UNIFORM) {
			ftype = new JvmType.Function(WHILEYSET,WHILEYSET,WHILEYSET);
		} else if(c.dir == OpDir.LEFT) {
			ftype = new JvmType.Function(WHILEYSET,WHILEYSET,JAVA_LANG_OBJECT);
		} else {
			ftype = new JvmType.Function(WHILEYSET,JAVA_LANG_OBJECT,WHILEYSET);
		}													
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "union", ftype,
				Bytecode.STATIC));				
	}	
	
	public void translate(Code.SetIntersect c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {		
		JvmType.Function ftype;
		if(c.dir == OpDir.UNIFORM) {
			ftype = new JvmType.Function(WHILEYSET,WHILEYSET,WHILEYSET);
		} else if(c.dir == OpDir.LEFT) {
			ftype = new JvmType.Function(WHILEYSET,WHILEYSET,JAVA_LANG_OBJECT);
		} else {
			ftype = new JvmType.Function(WHILEYSET,JAVA_LANG_OBJECT,WHILEYSET);
		}													
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "intersect", ftype,
				Bytecode.STATIC));
	}
	
	public void translate(Code.SetDifference c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {		
		JvmType.Function ftype;
		if(c.dir == OpDir.UNIFORM) {
			ftype = new JvmType.Function(WHILEYSET,WHILEYSET,WHILEYSET);
		} else {
			ftype = new JvmType.Function(WHILEYSET,WHILEYSET,JAVA_LANG_OBJECT);
		} 												
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "difference", ftype,
				Bytecode.STATIC));
	}
	
	public void translate(Code.SetLength c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {		
		JvmType.Function ftype = new JvmType.Function(BIG_INTEGER,WHILEYSET);			
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "length",
				ftype, Bytecode.STATIC));									
	}
		
	public void translate(Code.StringAppend c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {						
		JvmType.Function ftype;
		if(c.dir == OpDir.UNIFORM) {
			ftype = new JvmType.Function(JAVA_LANG_STRING,JAVA_LANG_STRING,JAVA_LANG_STRING);
		} else if(c.dir == OpDir.LEFT) {
			ftype = new JvmType.Function(JAVA_LANG_STRING,JAVA_LANG_STRING,T_CHAR);				
		} else {
			ftype = new JvmType.Function(JAVA_LANG_STRING,T_CHAR,JAVA_LANG_STRING);				
		}													
		bytecodes.add(new Bytecode.Invoke(WHILEYUTIL, "append", ftype,
				Bytecode.STATIC));
	}
	
	public void translate(Code.StringLoad c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {						
		JvmType.Function ftype = new JvmType.Function(T_INT);
		bytecodes.add(new Bytecode.Invoke(BIG_INTEGER, "intValue",
				ftype, Bytecode.VIRTUAL));
		ftype = new JvmType.Function(T_CHAR,T_INT);
		bytecodes.add(new Bytecode.Invoke(JAVA_LANG_STRING, "charAt", ftype,
				Bytecode.VIRTUAL));				
	}
	
	public void translate(Code.StringLength c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {						
		JvmType.Function ftype = new JvmType.Function(BIG_INTEGER,JAVA_LANG_STRING);						
		bytecodes.add(new Bytecode.Invoke(WHILEYUTIL, "stringlength",
				ftype, Bytecode.STATIC));								
	}
	
	public void translate(Code.SubString c, Entry stmt, int freeSlot,
			ArrayList<Bytecode> bytecodes) {						
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_STRING,JAVA_LANG_STRING,
				BIG_INTEGER, BIG_INTEGER);
		bytecodes.add(new Bytecode.Invoke(WHILEYUTIL, "substring", ftype,
				Bytecode.STATIC));
	}	
	
	public void translate(Code.Invert c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {	
		bytecodes.add(new Bytecode.LoadConst(-1));
		bytecodes.add(new Bytecode.BinOp(Bytecode.BinOp.XOR,T_INT));			
	}
	
	public void translate(Code.Negate c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {								
		JvmType type = convertType(c.type);
		JvmType.Function ftype = new JvmType.Function(type);
		bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type, "negate",
				ftype, Bytecode.VIRTUAL));		
	}
	
	public void translate(Code.Spawn c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {							
		bytecodes.add(new Bytecode.New(WHILEYPROCESS));			
		bytecodes.add(new Bytecode.DupX1());
		bytecodes.add(new Bytecode.Swap());
		// TODO: problem here ... need to swap or something				
		JvmType.Function ftype = new JvmType.Function(T_VOID,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYPROCESS, "<init>", ftype,
				Bytecode.SPECIAL));
	}
	
	public void translate(Code.ProcLoad c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {				
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT);		
		bytecodes.add(new Bytecode.Invoke(WHILEYPROCESS, "getState", ftype,
				Bytecode.VIRTUAL));
		// finally, we need to cast the object we got back appropriately.		
		Type.Process pt = (Type.Process) c.type;						
		addReadConversion(pt.element(), bytecodes);
	}
	
	protected void translate(Code.NewDict c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		construct(WHILEYMAP, freeSlot, bytecodes);
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
				JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Store(freeSlot, WHILEYMAP));
		JvmType valueT = convertType(c.type.value());

		for (int i = 0; i != c.nargs; ++i) {
			bytecodes.add(new Bytecode.Store(freeSlot + 1, valueT));
			bytecodes.add(new Bytecode.Load(freeSlot, WHILEYMAP));
			bytecodes.add(new Bytecode.Swap());
			addWriteConversion(c.type.key(), bytecodes);
			bytecodes.add(new Bytecode.Load(freeSlot + 1, valueT));
			bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "put", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(JAVA_LANG_OBJECT));
		}

		bytecodes.add(new Bytecode.Load(freeSlot, WHILEYMAP));
	}
	
	protected void translate(Code.NewList c, int freeSlot, ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.New(WHILEYLIST));		
		bytecodes.add(new Bytecode.Dup(WHILEYLIST));
		bytecodes.add(new Bytecode.LoadConst(c.nargs));
		JvmType.Function ftype = new JvmType.Function(T_VOID,T_INT);
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "<init>", ftype,
				Bytecode.SPECIAL));
		
		ftype = new JvmType.Function(WHILEYLIST, WHILEYLIST, JAVA_LANG_OBJECT);		
		for(int i=0;i!=c.nargs;++i) {			
			bytecodes.add(new Bytecode.Swap());			
			addWriteConversion(c.type.element(),bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "append", ftype,
					Bytecode.STATIC));			
		}
		
		// At this stage, we have a problem. We've added the elements into the
		// list in reverse order. For simplicity, I simply call reverse at this
		// stage. However, it begs the question how we can do better.
		//
		// We could store each value into a register and then reload them in the
		// reverse order. For very large lists, this might cause a problem I
		// suspect.
		//
		// Another option would be to have a special list initialise function
		// with a range of different constructors for different sized lists.
				
		JvmType.Clazz owner = new JvmType.Clazz("java.util","Collections");
		ftype = new JvmType.Function(T_VOID, JAVA_UTIL_LIST);		
		bytecodes.add(new Bytecode.Dup(WHILEYLIST));
		bytecodes.add(new Bytecode.Invoke(owner,"reverse",ftype,Bytecode.STATIC));			
	}
	
	public void translate(Code.NewRecord expr, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		construct(WHILEYRECORD, freeSlot, bytecodes);		
		bytecodes.add(new Bytecode.Store(freeSlot,WHILEYRECORD));
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
				JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
		
		HashMap<String,Type> fields = expr.type.fields();
		ArrayList<String> keys = new ArrayList<String>(fields.keySet());
		Collections.sort(keys);
		Collections.reverse(keys);
		for(String key : keys) {
			Type et = fields.get(key);				
			bytecodes.add(new Bytecode.Load(freeSlot,WHILEYRECORD));
			bytecodes.add(new Bytecode.Swap());
			bytecodes.add(new Bytecode.LoadConst(key));
			bytecodes.add(new Bytecode.Swap());
			addWriteConversion(et,bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"put",ftype,Bytecode.VIRTUAL));						
			bytecodes.add(new Bytecode.Pop(JAVA_LANG_OBJECT));
		}
		
		bytecodes.add(new Bytecode.Load(freeSlot,WHILEYRECORD));
	}
	
	protected void translate(Code.NewSet c, int freeSlot, ArrayList<Bytecode> bytecodes) {
		construct(WHILEYSET, freeSlot, bytecodes);		
		JvmType.Function ftype = new JvmType.Function(T_BOOL,
				JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Store(freeSlot,WHILEYSET));		
		
		for(int i=0;i!=c.nargs;++i) {
			bytecodes.add(new Bytecode.Load(freeSlot,WHILEYSET));
			bytecodes.add(new Bytecode.Swap());			
			addWriteConversion(c.type.element(),bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYSET,"add",ftype,Bytecode.VIRTUAL));
			// FIXME: there is a bug here for bool lists
			bytecodes.add(new Bytecode.Pop(JvmTypes.T_BOOL));
		}
		
		bytecodes.add(new Bytecode.Load(freeSlot,WHILEYSET));
	}
	
	protected void translate(Code.NewTuple c, int freeSlot, ArrayList<Bytecode> bytecodes) {
		construct(WHILEYTUPLE, freeSlot, bytecodes);
		JvmType.Function ftype = new JvmType.Function(T_BOOL,
				JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Store(freeSlot,WHILEYTUPLE));		
		
		ArrayList<Type> types = new ArrayList<Type>(c.type.elements());
		Collections.reverse(types);
		
		for(Type type : types) {
			bytecodes.add(new Bytecode.Load(freeSlot,WHILEYTUPLE));
			bytecodes.add(new Bytecode.Swap());			
			addWriteConversion(type,bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYTUPLE,"add",ftype,Bytecode.VIRTUAL));
			// FIXME: there is a bug here for bool lists
			bytecodes.add(new Bytecode.Pop(JvmTypes.T_BOOL));
		}
		
		bytecodes.add(new Bytecode.Load(freeSlot,WHILEYTUPLE));
		
		// At this stage, we have a problem. We've added the elements into the
		// tuple in reverse order. For simplicity, I simply call reverse at this
		// stage. However, it begs the question how we can do better.
		//
		// We could store each value into a register and then reload them in the
		// reverse order. For very large lists, this might cause a problem I
		// suspect.
		//
		// Another option would be to have a special list initialise function
		// with a range of different constructors for different sized lists.
				
		JvmType.Clazz owner = new JvmType.Clazz("java.util","Collections");
		ftype = new JvmType.Function(T_VOID, JAVA_UTIL_LIST);		
		bytecodes.add(new Bytecode.Dup(WHILEYTUPLE));
		bytecodes.add(new Bytecode.Invoke(owner,"reverse",ftype,Bytecode.STATIC));
	}
	
	public void translate(Code.Invoke c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		ModuleID mid = c.name.module();
		String mangled = nameMangle(c.name.name(), c.type);
		JvmType.Clazz owner = new JvmType.Clazz(mid.pkg().toString(),
				mid.module());
		JvmType.Function type = convertFunType(c.type);
		bytecodes
				.add(new Bytecode.Invoke(owner, mangled, type, Bytecode.STATIC));

		// now, handle the case of an invoke which returns a value that should
		// be discarded. 
		if(!c.retval && c.type.ret() != Type.T_VOID) {
			bytecodes.add(new Bytecode.Pop(convertType(c.type.ret())));
		}
	}
	
	public void translate(Code.IndirectInvoke c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {				
		
		// The main issue here, is that we have all of the parameters on the
		// stack. What we need to do is to put them into an array, so they can
		// then be passed into Method.invoke()
		//
		// To make this work, what we'll do is use a temporary register to hold
		// the array as we build it up.

		Type.Fun ft = (Type.Fun) c.type;		
		JvmType.Array arrT = new JvmType.Array(JAVA_LANG_OBJECT);		

		bytecodes.add(new Bytecode.LoadConst(ft.params().size()));
		bytecodes.add(new Bytecode.New(arrT));
		bytecodes.add(new Bytecode.Store(freeSlot,arrT));
		
		List<Type> params = ft.params();
		for(int i=params.size()-1;i>=0;--i) {
			Type pt = params.get(i);
			bytecodes.add(new Bytecode.Load(freeSlot,arrT));
			bytecodes.add(new Bytecode.Swap());
			bytecodes.add(new Bytecode.LoadConst(i));
			bytecodes.add(new Bytecode.Swap());
			addWriteConversion(pt,bytecodes);
			bytecodes.add(new Bytecode.ArrayStore(arrT));			
		}

		bytecodes.add(new Bytecode.LoadConst(null));
		bytecodes.add(new Bytecode.Load(freeSlot,arrT));
		JvmType.Clazz owner = new JvmType.Clazz("java.lang.reflect","Method");		
		JvmType.Function type = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT,arrT);		
		
		bytecodes.add(new Bytecode.Invoke(owner, "invoke", type,
				Bytecode.VIRTUAL));						
		addReadConversion(ft.ret(),bytecodes);	
	}

	public void translate(Code.Send c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		
		// The main issue here, is that we have all of the parameters + receiver
		// on the stack. What we need to do is to put them into an array, so
		// they can then be passed into Method.invoke()
		//
		// To make this work, what we'll do is use a temporary register to hold
		// the array as we build it up.

		Type.Fun ft = (Type.Fun) c.type;		
		JvmType.Array arrT = new JvmType.Array(JAVA_LANG_OBJECT);		
		bytecodes.add(new Bytecode.LoadConst(ft.params().size()+1));
		bytecodes.add(new Bytecode.New(arrT));
		bytecodes.add(new Bytecode.Store(freeSlot,arrT));
		
		// first, peal parameters off stack in reverse order
		
		List<Type> params = ft.params();
		for(int i=params.size()-1;i>=0;--i) {
			Type pt = params.get(i);
			bytecodes.add(new Bytecode.Load(freeSlot,arrT));
			bytecodes.add(new Bytecode.Swap());
			bytecodes.add(new Bytecode.LoadConst(i+1));
			bytecodes.add(new Bytecode.Swap());			
			addWriteConversion(pt,bytecodes);
			bytecodes.add(new Bytecode.ArrayStore(arrT));			
		}
		
		// finally, setup the stack for the send
		bytecodes.add(new Bytecode.Load(0, WHILEYPROCESS));
		
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_REFLECT_METHOD,
				JAVA_LANG_STRING, JAVA_LANG_STRING);
		
		bytecodes.add(new Bytecode.LoadConst(c.name.module().toString()));		
		bytecodes
				.add(new Bytecode.LoadConst(nameMangle(c.name.name(), c.type)));
		bytecodes.add(new Bytecode.Invoke(WHILEYIO, "functionRef", ftype,
				Bytecode.STATIC));
		bytecodes.add(new Bytecode.Load(freeSlot, arrT));
							
		if (c.synchronous) {			
			String name = c.retval ? "sendSync" : "sendSyncVoid";
			ftype = new JvmType.Function(T_VOID,
					WHILEYMESSAGER, JAVA_LANG_REFLECT_METHOD, JAVA_LANG_OBJECT_ARRAY);
			bytecodes.add(new Bytecode.Invoke(WHILEYMESSAGER, name, ftype,
					Bytecode.VIRTUAL));
		} else {
			ftype = new JvmType.Function(T_VOID,
					WHILEYMESSAGER, JAVA_LANG_REFLECT_METHOD, JAVA_LANG_OBJECT_ARRAY);
			bytecodes.add(new Bytecode.Invoke(WHILEYMESSAGER, "sendAsync",
					ftype, Bytecode.VIRTUAL));
		} 
	}
	
	public void translate(Code.IndirectSend c, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		// The main issue here, is that we have all of the parameters + receiver
		// on the stack. What we need to do is to put them into an array, so
		// they can then be passed into Method.invoke()
		//
		// To make this work, what we'll do is use a temporary register to hold
		// the array as we build it up.

		Type.Fun ft = (Type.Fun) c.type;		
		JvmType.Array arrT = new JvmType.Array(JAVA_LANG_OBJECT);		
		bytecodes.add(new Bytecode.LoadConst(ft.params().size()+1));
		bytecodes.add(new Bytecode.New(arrT));
		bytecodes.add(new Bytecode.Store(freeSlot,arrT));
		
		// first, peal parameters off stack in reverse order
		
		List<Type> params = ft.params();
		for(int i=params.size()-1;i>=0;--i) {
			Type pt = params.get(i);
			bytecodes.add(new Bytecode.Load(freeSlot,arrT));
			bytecodes.add(new Bytecode.Swap());
			bytecodes.add(new Bytecode.LoadConst(i+1));
			bytecodes.add(new Bytecode.Swap());			
			addWriteConversion(pt,bytecodes);
			bytecodes.add(new Bytecode.ArrayStore(arrT));			
		}
		bytecodes.add(new Bytecode.Swap());
		bytecodes.add(new Bytecode.Load(freeSlot, arrT));
							
		if (c.synchronous) {			
			String name = c.retval ? "sendSync" : "sendSyncVoid";
			JvmType.Function ftype = new JvmType.Function(T_VOID,
					JAVA_LANG_REFLECT_METHOD, JAVA_LANG_OBJECT_ARRAY);
			bytecodes.add(new Bytecode.Invoke(WHILEYMESSAGER, name, ftype,
					Bytecode.VIRTUAL));
		} else {
			JvmType.Function ftype = new JvmType.Function(T_VOID,
					JAVA_LANG_REFLECT_METHOD, JAVA_LANG_OBJECT_ARRAY);
			bytecodes.add(new Bytecode.Invoke(WHILEYMESSAGER, "sendAsync",
					ftype, Bytecode.VIRTUAL));
		} 

	}
		
	public void translate(Value v, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		if(v instanceof Value.Null) {
			translate((Value.Null)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Bool) {
			translate((Value.Bool)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Byte) {
			translate((Value.Byte)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Char) {
			translate((Value.Char)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Integer) {
			translate((Value.Integer)v,freeSlot,bytecodes);
		} else if(v instanceof Value.TypeConst) {
			translate((Value.TypeConst)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Rational) {
			translate((Value.Rational)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Strung) {
			translate((Value.Strung)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Set) {
			translate((Value.Set)v,freeSlot,bytecodes);
		} else if(v instanceof Value.List) {
			translate((Value.List)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Record) {
			translate((Value.Record)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Dictionary) {
			translate((Value.Dictionary)v,freeSlot,bytecodes);
		} else if(v instanceof Value.Tuple) {
			translate((Value.Tuple)v,freeSlot,bytecodes);
		} else if(v instanceof Value.FunConst) {
			translate((Value.FunConst)v,freeSlot,bytecodes);
		} else {
			throw new IllegalArgumentException("unknown value encountered:" + v);
		}
	}
	
	protected void translate(Value.Null e, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.LoadConst(null));
	}
	
	protected void translate(Value.Bool e, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		if (e.value) {
			bytecodes.add(new Bytecode.LoadConst(1));
		} else {
			bytecodes.add(new Bytecode.LoadConst(0));
		}
	}

	protected void translate(Value.TypeConst e, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.LoadConst(e.toString()));
		JvmType.Function ftype = new JvmType.Function(WHILEYTYPE,
				JAVA_LANG_STRING);
		bytecodes.add(new Bytecode.Invoke(WHILEYTYPE, "valueOf", ftype,
				Bytecode.STATIC));
	}
	
	protected void translate(Value.Byte e, int freeSlot, ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.LoadConst((int)e.value));		
	}
	
	protected void translate(Value.Char e, int freeSlot, ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.LoadConst(e.value));		
	}
			
	protected void translate(Value.Integer e, int freeSlot,			
			ArrayList<Bytecode> bytecodes) {		
		BigInteger num = e.value;
		
		if(num.bitLength() < 32) {			
			bytecodes.add(new Bytecode.LoadConst(num.intValue()));				
			bytecodes.add(new Bytecode.Conversion(T_INT,T_LONG));
			JvmType.Function ftype = new JvmType.Function(BIG_INTEGER,T_LONG);
			bytecodes.add(new Bytecode.Invoke(BIG_INTEGER, "valueOf", ftype,
					Bytecode.STATIC));
		} else if(num.bitLength() < 64) {			
			bytecodes.add(new Bytecode.LoadConst(num.longValue()));				
			JvmType.Function ftype = new JvmType.Function(BIG_INTEGER,T_LONG);
			bytecodes.add(new Bytecode.Invoke(BIG_INTEGER, "valueOf", ftype,
					Bytecode.STATIC));
		} else {
			// in this context, we need to use a byte array to construct the
			// integer object.
			byte[] bytes = num.toByteArray();
			JvmType.Array bat = new JvmType.Array(JvmTypes.T_BYTE);

			bytecodes.add(new Bytecode.New(BIG_INTEGER));		
			bytecodes.add(new Bytecode.Dup(BIG_INTEGER));			
			bytecodes.add(new Bytecode.LoadConst(bytes.length));
			bytecodes.add(new Bytecode.New(bat));
			for(int i=0;i!=bytes.length;++i) {
				bytecodes.add(new Bytecode.Dup(bat));
				bytecodes.add(new Bytecode.LoadConst(i));
				bytecodes.add(new Bytecode.LoadConst(bytes[i]));
				bytecodes.add(new Bytecode.ArrayStore(bat));
			}			

			JvmType.Function ftype = new JvmType.Function(T_VOID,bat);						
			bytecodes.add(new Bytecode.Invoke(BIG_INTEGER, "<init>", ftype,
					Bytecode.SPECIAL));								
		}	
	
	}
	
	protected void translate(Value.Rational e, int freeSlot,
			ArrayList<Bytecode> bytecodes) {		
		BigRational rat = e.value;
		BigInteger den = rat.denominator();
		BigInteger num = rat.numerator();
		if(rat.isInteger()) {
			// this 
			if(num.bitLength() < 32) {			
				bytecodes.add(new Bytecode.LoadConst(num.intValue()));				
				JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_INT);
				bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
						Bytecode.STATIC));
			} else if(num.bitLength() < 64) {			
				bytecodes.add(new Bytecode.LoadConst(num.longValue()));				
				JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_LONG);
				bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
						Bytecode.STATIC));
			} else {
				// in this context, we need to use a byte array to construct the
				// integer object.
				byte[] bytes = num.toByteArray();
				JvmType.Array bat = new JvmType.Array(JvmTypes.T_BYTE);

				bytecodes.add(new Bytecode.New(BIG_RATIONAL));		
				bytecodes.add(new Bytecode.Dup(BIG_RATIONAL));			
				bytecodes.add(new Bytecode.LoadConst(bytes.length));
				bytecodes.add(new Bytecode.New(bat));
				for(int i=0;i!=bytes.length;++i) {
					bytecodes.add(new Bytecode.Dup(bat));
					bytecodes.add(new Bytecode.LoadConst(i));
					bytecodes.add(new Bytecode.LoadConst(bytes[i]));
					bytecodes.add(new Bytecode.ArrayStore(bat));
				}			

				JvmType.Function ftype = new JvmType.Function(T_VOID,bat);						
				bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "<init>", ftype,
						Bytecode.SPECIAL));								
			}	
		} else if(num.bitLength() < 32 && den.bitLength() < 32) {			
			bytecodes.add(new Bytecode.LoadConst(num.intValue()));
			bytecodes.add(new Bytecode.LoadConst(den.intValue()));
			JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_INT,T_INT);
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
					Bytecode.STATIC));
		} else if(num.bitLength() < 64 && den.bitLength() < 64) {			
			bytecodes.add(new Bytecode.LoadConst(num.longValue()));
			bytecodes.add(new Bytecode.LoadConst(den.longValue()));
			JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_LONG,T_LONG);
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
					Bytecode.STATIC));
		} else {
			// First, do numerator bytes
			byte[] bytes = num.toByteArray();
			JvmType.Array bat = new JvmType.Array(JvmTypes.T_BYTE);

			bytecodes.add(new Bytecode.New(BIG_RATIONAL));		
			bytecodes.add(new Bytecode.Dup(BIG_RATIONAL));			
			bytecodes.add(new Bytecode.LoadConst(bytes.length));
			bytecodes.add(new Bytecode.New(bat));
			for(int i=0;i!=bytes.length;++i) {
				bytecodes.add(new Bytecode.Dup(bat));
				bytecodes.add(new Bytecode.LoadConst(i));
				bytecodes.add(new Bytecode.LoadConst(bytes[i]));
				bytecodes.add(new Bytecode.ArrayStore(bat));
			}		

			// Second, do denominator bytes
			bytes = den.toByteArray();			
			bytecodes.add(new Bytecode.LoadConst(bytes.length));
			bytecodes.add(new Bytecode.New(bat));
			for(int i=0;i!=bytes.length;++i) {
				bytecodes.add(new Bytecode.Dup(bat));
				bytecodes.add(new Bytecode.LoadConst(i));
				bytecodes.add(new Bytecode.LoadConst(bytes[i]));
				bytecodes.add(new Bytecode.ArrayStore(bat));
			}

			// Finally, construct BigRational object
			JvmType.Function ftype = new JvmType.Function(T_VOID,bat,bat);						
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "<init>", ftype,
					Bytecode.SPECIAL));			
		}		
	}
	
	protected void translate(Value.Strung e, int freeSlot,
			ArrayList<Bytecode> bytecodes) {		
		bytecodes.add(new Bytecode.LoadConst(e.value));
	}
	
	protected void translate(Value.Set lv, int freeSlot,
			ArrayList<Bytecode> bytecodes) {	
		bytecodes.add(new Bytecode.New(WHILEYSET));		
		bytecodes.add(new Bytecode.Dup(WHILEYSET));
		JvmType.Function ftype = new JvmType.Function(T_VOID);
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "<init>", ftype,
				Bytecode.SPECIAL));
		
		ftype = new JvmType.Function(T_BOOL, JAVA_LANG_OBJECT);		
		for (Value e : lv.values) {
			bytecodes.add(new Bytecode.Dup(WHILEYSET));
			translate(e, freeSlot, bytecodes);
			addWriteConversion(e.type(), bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYSET, "add", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(T_BOOL));
		}		
	}

	protected void translate(Value.List lv, int freeSlot,
			ArrayList<Bytecode> bytecodes) {		
		bytecodes.add(new Bytecode.New(WHILEYLIST));		
		bytecodes.add(new Bytecode.Dup(WHILEYLIST));
		bytecodes.add(new Bytecode.LoadConst(lv.values.size()));
		JvmType.Function ftype = new JvmType.Function(T_VOID,T_INT);
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "<init>", ftype,
				Bytecode.SPECIAL));
		
		ftype = new JvmType.Function(T_BOOL, JAVA_LANG_OBJECT);		
		for (Value e : lv.values) {	
			bytecodes.add(new Bytecode.Dup(WHILEYLIST));
			translate(e, freeSlot, bytecodes);
			addWriteConversion(e.type(), bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "add", ftype,
					Bytecode.VIRTUAL));			
			bytecodes.add(new Bytecode.Pop(T_BOOL));
		}				
	}

	protected void translate(Value.Tuple lv, int freeSlot,
			ArrayList<Bytecode> bytecodes) {		
		bytecodes.add(new Bytecode.New(WHILEYTUPLE));		
		bytecodes.add(new Bytecode.Dup(WHILEYTUPLE));
		bytecodes.add(new Bytecode.LoadConst(lv.values.size()));
		JvmType.Function ftype = new JvmType.Function(T_VOID,T_INT);
		bytecodes.add(new Bytecode.Invoke(WHILEYTUPLE, "<init>", ftype,
				Bytecode.SPECIAL));
		
		ftype = new JvmType.Function(T_BOOL, JAVA_LANG_OBJECT);		
		for (Value e : lv.values) {	
			bytecodes.add(new Bytecode.Dup(WHILEYTUPLE));
			translate(e, freeSlot, bytecodes);
			addWriteConversion(e.type(), bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYTUPLE, "add", ftype,
					Bytecode.VIRTUAL));			
			bytecodes.add(new Bytecode.Pop(T_BOOL));
		}				
	}
	
	protected void translate(Value.Record expr, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
				JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
		construct(WHILEYRECORD, freeSlot, bytecodes);
		for (Map.Entry<String, Value> e : expr.values.entrySet()) {
			Type et = e.getValue().type();
			bytecodes.add(new Bytecode.Dup(WHILEYRECORD));
			bytecodes.add(new Bytecode.LoadConst(e.getKey()));
			translate(e.getValue(), freeSlot, bytecodes);
			addWriteConversion(et, bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD, "put", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(JAVA_LANG_OBJECT));
		}
	}
	
	protected void translate(Value.Dictionary expr, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
				JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
		
		construct(WHILEYMAP, freeSlot, bytecodes);
		
		for (Map.Entry<Value, Value> e : expr.values.entrySet()) {
			Type kt = e.getKey().type();
			Type vt = e.getValue().type();
			bytecodes.add(new Bytecode.Dup(WHILEYMAP));			
			translate(e.getKey(), freeSlot, bytecodes);
			addWriteConversion(kt, bytecodes);
			translate(e.getValue(), freeSlot, bytecodes);
			addWriteConversion(vt, bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "put", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(JAVA_LANG_OBJECT));
		}
	}
	
	protected void translate(Value.FunConst e, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_REFLECT_METHOD,JAVA_LANG_STRING,JAVA_LANG_STRING);
		NameID nid = e.name;		
		bytecodes.add(new Bytecode.LoadConst(nid.module().toString()));
		bytecodes.add(new Bytecode.LoadConst(nameMangle(nid.name(),e.type)));
		bytecodes.add(new Bytecode.Invoke(WHILEYIO, "functionRef", ftype,Bytecode.STATIC));
	}

	protected void addCoercion(Type from, Type to, int freeSlot,
			HashMap<Constant, Integer> constants, ArrayList<Bytecode> bytecodes) {
		
		// First, deal with coercions which require a change of representation
		// when going into a union.  For example, bool must => Boolean.
		if (Type.isomorphic(to, from)) {		
			// do nothing!						
		} else if (!(to instanceof Type.Bool) && from instanceof Type.Bool) {
			// this is either going into a union type, or the any type
			buildCoercion((Type.Bool) from, to, freeSlot, bytecodes);
		} else if(from == Type.T_INT) {									
			buildCoercion((Type.Int)from, to, freeSlot,bytecodes);  
		} else if(from == Type.T_CHAR) {									
			buildCoercion((Type.Char)from, to, freeSlot,bytecodes);  
		} else if(from == Type.T_BYTE) {									
			buildCoercion((Type.Byte)from, to, freeSlot,bytecodes); 
		} else {
			// Second, check for other easy cases that we can do inline. We first
			// simplify the target in order to remove any unions on the right-hand
			// side. 
			to = simplifyCoercion(from,to);

			if (Type.isomorphic(to, from)) {		
				// do nothing!						
			} else if(from == Type.T_STRING && to instanceof Type.List) {									
				buildCoercion((Type.Strung)from, (Type.List) to, freeSlot,bytecodes); 
			} else if(to == Type.T_ANY) {
				// nothing to do here
			} else {
				// ok, it's a harder case so we use an explicit coercion function
				int id = Coercion.get(from,to,constants);
				String name = "coercion$" + id;
				JvmType.Function ft = new JvmType.Function(convertType(to), convertType(from));
				bytecodes.add(new Bytecode.Invoke(owner, name, ft, Bytecode.STATIC));
			}
		}
	}

	public void buildCoercion(Type.Bool fromType, Type toType, 
			int freeSlot, ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_BOOLEAN,T_BOOL);			
		bytecodes.add(new Bytecode.Invoke(JAVA_LANG_BOOLEAN,"valueOf",ftype,Bytecode.STATIC));			
		// done deal!
	}
	
	public void buildCoercion(Type.Byte fromType, Type toType,
			int freeSlot, ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_BYTE,T_BYTE);			
		bytecodes.add(new Bytecode.Invoke(JAVA_LANG_BYTE,"valueOf",ftype,Bytecode.STATIC));			
		// done deal!
	}
	
	public void buildCoercion(Type.Int fromType, Type toType, 
			int freeSlot, ArrayList<Bytecode> bytecodes) {
		if(!Type.isSubtype(toType,fromType)) {
			Type glb = Type.greatestLowerBound(Type.T_REAL, toType);
			if(glb == Type.T_REAL) { 
				// coercion required!
				JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,BIG_INTEGER);			
				bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL,"valueOf",ftype,Bytecode.STATIC));
			} else {				
				// must be => char
				JvmType.Function ftype = new JvmType.Function(T_INT);			
				bytecodes.add(new Bytecode.Invoke(BIG_INTEGER,"intValue",ftype,Bytecode.VIRTUAL));				
			}
		}
	}

	public void buildCoercion(Type.Char fromType, Type toType, 
			int freeSlot, ArrayList<Bytecode> bytecodes) {
		if(!Type.isSubtype(toType,fromType)) {					
			if(toType == Type.T_REAL) { 
				// coercion required!
				JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_INT);			
				bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL,"valueOf",ftype,Bytecode.STATIC));
			} else {
				bytecodes.add(new Bytecode.Conversion(T_INT, T_LONG));
				JvmType.Function ftype = new JvmType.Function(BIG_INTEGER,T_LONG);			
				bytecodes.add(new Bytecode.Invoke(BIG_INTEGER,"valueOf",ftype,Bytecode.STATIC));				
			}
		} else {
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_CHARACTER,T_CHAR);			
			bytecodes.add(new Bytecode.Invoke(JAVA_LANG_CHARACTER,"valueOf",ftype,Bytecode.STATIC));	
		}
	}
	
	public void buildCoercion(Type.Strung fromType, Type.List toType, 
			int freeSlot, ArrayList<Bytecode> bytecodes) {		
		JvmType.Function ftype = new JvmType.Function(WHILEYLIST,JAVA_LANG_STRING);
		
		if(toType.element() == Type.T_CHAR) {
			bytecodes.add(new Bytecode.Invoke(WHILEYUTIL,"str2cl",ftype,Bytecode.STATIC));	
		} else {
			bytecodes.add(new Bytecode.Invoke(WHILEYUTIL,"str2il",ftype,Bytecode.STATIC));
		}		
	}
	/**
	 * The build coercion method constructs a static final private method which
	 * accepts a value of type "from", and coerces it into a value of type "to".  
	 * 
	 * @param to
	 * @param from
	 * 
	 */
	protected void buildCoercion(Type from, Type to, int id,
			HashMap<Constant, Integer> constants, ClassFile cf) {
		ArrayList<Bytecode> bytecodes = new ArrayList<Bytecode>();
		
		int freeSlot = 1;
		bytecodes.add(new Bytecode.Load(0,convertType(from)));		
		
		// First, call simplify the coercion if possible. This will remove any
		// union types from the right-hand side.
		to = simplifyCoercion(from,to);
		
		// Second, case analysis on the various kinds of coercion
		if(from instanceof Type.Tuple && to instanceof Type.Tuple) {
			buildCoercion((Type.Tuple) from, (Type.Tuple) to, freeSlot, constants, bytecodes);
		} else if(from instanceof Type.Process && to instanceof Type.Process) {
			// TODO			
		} else if(from instanceof Type.Set && to instanceof Type.Set) {
			buildCoercion((Type.Set) from, (Type.Set) to, freeSlot, constants, bytecodes);			
		} else if(from instanceof Type.Dictionary && to instanceof Type.Set) {
			buildCoercion((Type.List) from, (Type.Set) to, freeSlot, constants, bytecodes);			
		} else if(from instanceof Type.List && to instanceof Type.Set) {
			buildCoercion((Type.List) from, (Type.Set) to, freeSlot, constants, bytecodes);			
		} else if(from instanceof Type.Dictionary && to instanceof Type.Dictionary) {
			buildCoercion((Type.Dictionary) from, (Type.Dictionary) to, freeSlot, constants, bytecodes);			
		} else if(from instanceof Type.List && to instanceof Type.Dictionary) {
			buildCoercion((Type.List) from, (Type.Dictionary) to, freeSlot, constants, bytecodes);			
		} else if(from instanceof Type.List && to instanceof Type.List) {
			buildCoercion((Type.List) from, (Type.List) to, freeSlot, constants, bytecodes);			
		} else if(to instanceof Type.Record && from instanceof Type.Record) {
			buildCoercion((Type.Record) from, (Type.Record) to, freeSlot, constants, bytecodes);
		} else if(to instanceof Type.Fun && from instanceof Type.Fun) {
			// TODO
		} else if(from instanceof Type.Union) {			
			buildCoercion((Type.Union) from, to, freeSlot, constants, bytecodes);
		} else {
			throw new RuntimeException("invalid coercion encountered: " + from + " => " + to);
		}
	
		bytecodes.add(new Bytecode.Return(convertType(to)));
		
		ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
		modifiers.add(Modifier.ACC_PRIVATE);
		modifiers.add(Modifier.ACC_STATIC);
		modifiers.add(Modifier.ACC_SYNTHETIC);
		JvmType.Function ftype = new JvmType.Function(convertType(to),convertType(from));
		String name = "coercion$" + id;
		ClassFile.Method method = new ClassFile.Method(name, ftype, modifiers);
		cf.methods().add(method);
		wyjvm.attributes.Code code = new wyjvm.attributes.Code(bytecodes,new ArrayList(),method);
		method.attributes().add(code);				
	}
		
	protected void buildCoercion(Type.Tuple fromType, Type.Tuple toType, 
			int freeSlot, HashMap<Constant, Integer> constants,
			ArrayList<Bytecode> bytecodes) {
		int oldSlot = freeSlot++;
		int newSlot = freeSlot++;		
		bytecodes.add(new Bytecode.Store(oldSlot,WHILEYTUPLE));
		construct(WHILEYTUPLE,freeSlot,bytecodes);
		bytecodes.add(new Bytecode.Store(newSlot,WHILEYTUPLE));
		List<Type> from_elements = fromType.elements();
		List<Type> to_elements = toType.elements();
		for(int i=0;i!=to_elements.size();++i) {
			Type from = from_elements.get(i);
			Type to = to_elements.get(i);
			bytecodes.add(new Bytecode.Load(newSlot,WHILEYTUPLE));			
			bytecodes.add(new Bytecode.Load(oldSlot,WHILEYTUPLE));
			bytecodes.add(new Bytecode.LoadConst(i));
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,T_INT);			
			bytecodes.add(new Bytecode.Invoke(WHILEYTUPLE,"get",ftype,Bytecode.VIRTUAL));								
			addReadConversion(from,bytecodes);							
			// now perform recursive conversion
			addCoercion(from,to,freeSlot,constants,bytecodes);							
			ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);			
			bytecodes.add(new Bytecode.Invoke(WHILEYTUPLE,"add",ftype,Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(T_BOOL));
		}
		bytecodes.add(new Bytecode.Load(newSlot,WHILEYTUPLE));
	}
	
		
	protected void buildCoercion(Type.List fromType, Type.List toType, 
			int freeSlot, HashMap<Constant, Integer> constants,
			ArrayList<Bytecode> bytecodes) {
		
		if(fromType.element() == Type.T_VOID) {
			// nothing to do, in this particular case
			return;
		}
		
		// The following piece of code implements a java for-each loop which
		// iterates every element of the input collection, and recursively
		// converts it before loading it back onto a new WhileyList.
		
		String loopLabel = freshLabel();
		String exitLabel = freshLabel();
		int iter = freeSlot++;
		int tmp = freeSlot++;
		JvmType.Function ftype = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "iterator",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.Store(iter,
				JAVA_UTIL_ITERATOR));
		construct(WHILEYLIST,freeSlot,bytecodes);
		bytecodes.add(new Bytecode.Store(tmp, WHILEYLIST));
		bytecodes.add(new Bytecode.Label(loopLabel));
		ftype = new JvmType.Function(T_BOOL);
		bytecodes.add(new Bytecode.Load(iter,JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "hasNext",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.If(Bytecode.If.EQ, exitLabel));
		bytecodes.add(new Bytecode.Load(tmp,WHILEYLIST));
		bytecodes.add(new Bytecode.Load(iter,JAVA_UTIL_ITERATOR));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next",
				ftype, Bytecode.INTERFACE));						
		addReadConversion(fromType.element(),bytecodes);
		addCoercion(fromType.element(), toType.element(), freeSlot,
				constants, bytecodes);			
		ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "add",
				ftype, Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.Pop(T_BOOL));
		bytecodes.add(new Bytecode.Goto(loopLabel));
		bytecodes.add(new Bytecode.Label(exitLabel));
		bytecodes.add(new Bytecode.Load(tmp,WHILEYLIST));
	}
	
	protected void buildCoercion(Type.List fromType, Type.Dictionary toType, 
			int freeSlot, HashMap<Constant, Integer> constants,
			ArrayList<Bytecode> bytecodes) {

		if(fromType.element() == Type.T_VOID) {
			// nothing to do, in this particular case
			return;
		}
		
		// The following piece of code implements a java for-each loop which
		// iterates every element of the input collection, and recursively
		// converts it before loading it back onto a new WhileyList. 
		String loopLabel = freshLabel();
		String exitLabel = freshLabel();
		int iter = freeSlot++;
		int source = freeSlot++;
		int target = freeSlot++;		
		bytecodes.add(new Bytecode.Store(source,JAVA_UTIL_LIST));
		bytecodes.add(new Bytecode.LoadConst(0));		
		bytecodes.add(new Bytecode.Store(iter,T_INT));
				
		construct(WHILEYMAP,freeSlot,bytecodes);
		bytecodes.add(new Bytecode.Store(target, WHILEYMAP));
		bytecodes.add(new Bytecode.Label(loopLabel));
		JvmType.Function ftype = new JvmType.Function(T_INT);		
		bytecodes.add(new Bytecode.Load(iter,JvmTypes.T_INT));
		bytecodes.add(new Bytecode.Load(source,JAVA_UTIL_LIST));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_LIST, "size",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.IfCmp(Bytecode.IfCmp.GE, T_INT, exitLabel));
		bytecodes.add(new Bytecode.Load(target,WHILEYSET));
		bytecodes.add(new Bytecode.Load(iter,T_INT));
		bytecodes.add(new Bytecode.Conversion(T_INT,T_LONG));	
		ftype = new JvmType.Function(BIG_INTEGER,T_LONG);
		bytecodes.add(new Bytecode.Invoke(BIG_INTEGER, "valueOf",
				ftype, Bytecode.STATIC));				
		bytecodes.add(new Bytecode.Load(source,WHILEYMAP));
		bytecodes.add(new Bytecode.Load(iter,T_INT));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT,T_INT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_LIST, "get",
				ftype, Bytecode.INTERFACE));						
		addReadConversion(fromType.element(),bytecodes);		
		addCoercion(fromType.element(), toType.value(), freeSlot,
				constants, bytecodes);			
		ftype = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "put",
				ftype, Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.Pop(JAVA_LANG_OBJECT));
		bytecodes.add(new Bytecode.Iinc(iter,1));
		bytecodes.add(new Bytecode.Goto(loopLabel));
		bytecodes.add(new Bytecode.Label(exitLabel));
		bytecodes.add(new Bytecode.Load(target,WHILEYMAP));		
	}
	
	protected void buildCoercion(Type.Dictionary fromType, Type.Dictionary toType, 
			int freeSlot, HashMap<Constant, Integer> constants,
			ArrayList<Bytecode> bytecodes) {
		
		// The following piece of code implements a java for-each loop which
		// iterates every element of the input collection, and recursively
		// converts it before loading it back onto a new WhileyList. 
		String loopLabel = freshLabel();
		String exitLabel = freshLabel();		
		
		int iter = freeSlot++;	
		int source = freeSlot++;
		int target = freeSlot++;		
		
		bytecodes.add(new Bytecode.Dup(WHILEYMAP));
		bytecodes.add(new Bytecode.Store(source, WHILEYMAP));
		construct(WHILEYMAP,freeSlot,bytecodes);
		bytecodes.add(new Bytecode.Store(target, WHILEYMAP));
										
		JvmType.Function ftype = new JvmType.Function(JAVA_UTIL_SET);
		bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "keySet",
				ftype, Bytecode.VIRTUAL));
		ftype = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_SET, "iterator",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.Store(iter,
				JAVA_UTIL_ITERATOR));
					
		bytecodes.add(new Bytecode.Label(loopLabel));
		ftype = new JvmType.Function(T_BOOL);
		bytecodes.add(new Bytecode.Load(iter,JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "hasNext",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.If(Bytecode.If.EQ, exitLabel));
		
		bytecodes.add(new Bytecode.Load(target,WHILEYMAP));
		bytecodes.add(new Bytecode.Load(iter,JAVA_UTIL_ITERATOR));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next",
				ftype, Bytecode.INTERFACE));							
		addReadConversion(fromType.key(),bytecodes);
		bytecodes.add(new Bytecode.Dup(convertType(fromType.key())));		
		addCoercion(fromType.key(), toType.key(), freeSlot,
				constants, bytecodes);		
		addWriteConversion(toType.key(),bytecodes);
		bytecodes.add(new Bytecode.Swap());		
		bytecodes.add(new Bytecode.Load(source,WHILEYMAP));
		bytecodes.add(new Bytecode.Swap());
		ftype = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "get",
				ftype, Bytecode.VIRTUAL));
		addReadConversion(fromType.value(),bytecodes);
		addCoercion(fromType.value(), toType.value(), freeSlot,
				constants, bytecodes);
		addWriteConversion(toType.value(),bytecodes);		
		ftype = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);		
		bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "put",
				ftype, Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.Pop(JAVA_LANG_OBJECT));
		bytecodes.add(new Bytecode.Goto(loopLabel));
		bytecodes.add(new Bytecode.Label(exitLabel));
		bytecodes.add(new Bytecode.Load(target,WHILEYMAP));
	}
	
	protected void buildCoercion(Type.List fromType, Type.Set toType,
			int freeSlot, HashMap<Constant,Integer> constants,			
			ArrayList<Bytecode> bytecodes) {
						
		if(fromType.element() == Type.T_VOID) {
			// nothing to do, in this particular case
			return;
		}				
		
		// The following piece of code implements a java for-each loop which
		// iterates every element of the input collection, and recursively
		// converts it before loading it back onto a new WhileyList. 
		String loopLabel = freshLabel();
		String exitLabel = freshLabel();
		int iter = freeSlot++;
		int tmp = freeSlot++;
		JvmType.Function ftype = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "iterator",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.Store(iter,
				JAVA_UTIL_ITERATOR));
		construct(WHILEYSET,freeSlot,bytecodes);
		bytecodes.add(new Bytecode.Store(tmp, WHILEYSET));
		bytecodes.add(new Bytecode.Label(loopLabel));
		ftype = new JvmType.Function(T_BOOL);
		bytecodes.add(new Bytecode.Load(iter,JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "hasNext",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.If(Bytecode.If.EQ, exitLabel));
		bytecodes.add(new Bytecode.Load(tmp,WHILEYSET));
		bytecodes.add(new Bytecode.Load(iter,JAVA_UTIL_ITERATOR));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next",
				ftype, Bytecode.INTERFACE));						
		addReadConversion(fromType.element(),bytecodes);
		addCoercion(fromType.element(), toType.element(), freeSlot,
				constants, bytecodes);			
		ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "add",
				ftype, Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.Pop(T_BOOL));
		bytecodes.add(new Bytecode.Goto(loopLabel));
		bytecodes.add(new Bytecode.Label(exitLabel));
		bytecodes.add(new Bytecode.Load(tmp,WHILEYSET));
	}
	
	protected void buildCoercion(Type.Set fromType, Type.Set toType,
			int freeSlot, HashMap<Constant,Integer> constants,
			ArrayList<Bytecode> bytecodes) {
		
		if(fromType.element() == Type.T_VOID) {
			// nothing to do, in this particular case
			return;
		}
		
		// The following piece of code implements a java for-each loop which
		// iterates every element of the input collection, and recursively
		// converts it before loading it back onto a new WhileyList. 
		String loopLabel = freshLabel();
		String exitLabel = freshLabel();
		int iter = freeSlot++;
		int tmp = freeSlot++;		
		JvmType.Function ftype = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "iterator",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.Store(iter,
				JAVA_UTIL_ITERATOR));
		construct(WHILEYSET,freeSlot,bytecodes);
		bytecodes.add(new Bytecode.Store(tmp, WHILEYSET));
		bytecodes.add(new Bytecode.Label(loopLabel));
		ftype = new JvmType.Function(T_BOOL);
		bytecodes.add(new Bytecode.Load(iter,JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "hasNext",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.If(Bytecode.If.EQ, exitLabel));
		bytecodes.add(new Bytecode.Load(tmp,WHILEYSET));
		bytecodes.add(new Bytecode.Load(iter,JAVA_UTIL_ITERATOR));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next",
				ftype, Bytecode.INTERFACE));
		addCheckCast(convertType(fromType.element()),bytecodes);		
		addCoercion(fromType.element(), toType.element(), freeSlot,
				constants, bytecodes);			
		ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "add",
				ftype, Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.Pop(T_BOOL));
		bytecodes.add(new Bytecode.Goto(loopLabel));
		bytecodes.add(new Bytecode.Label(exitLabel));
		bytecodes.add(new Bytecode.Load(tmp,WHILEYSET));
	}
	
	public void buildCoercion(Type.Record fromType, Type.Record toType, 
			int freeSlot, HashMap<Constant,Integer> constants,
			ArrayList<Bytecode> bytecodes) {		
		int oldSlot = freeSlot++;
		int newSlot = freeSlot++;		
		bytecodes.add(new Bytecode.Store(oldSlot,WHILEYRECORD));
		construct(WHILEYRECORD,freeSlot,bytecodes);
		bytecodes.add(new Bytecode.Store(newSlot,WHILEYRECORD));
		Map<String,Type> toFields = toType.fields();
		Map<String,Type> fromFields = fromType.fields();
		for(String key : toFields.keySet()) {
			Type to = toFields.get(key);
			Type from = fromFields.get(key);				
			bytecodes.add(new Bytecode.Load(newSlot,WHILEYRECORD));
			bytecodes.add(new Bytecode.LoadConst(key));
			bytecodes.add(new Bytecode.Load(oldSlot,WHILEYRECORD));
			bytecodes.add(new Bytecode.LoadConst(key));
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);			
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"get",ftype,Bytecode.VIRTUAL));								
			// TODO: in cases when the read conversion is a no-op, we can do
			// better here.
			addReadConversion(from,bytecodes);							
			addCoercion(from,to,freeSlot,constants,bytecodes);
			addWriteConversion(from,bytecodes);
			ftype = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);			
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"put",ftype,Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(JAVA_LANG_OBJECT));			
		}
		bytecodes.add(new Bytecode.Load(newSlot,WHILEYRECORD));		
	}
	
	public void buildCoercion(Type.Union from, Type to, 
			int freeSlot, HashMap<Constant,Integer> constants,
			ArrayList<Bytecode> bytecodes) {	
		
		String exitLabel = freshLabel();
		List<Type> bounds = new ArrayList<Type>(from.bounds());
		ArrayList<String> labels = new ArrayList<String>();				
		
		// basically, we're building a big dispatch table. I think there's no
		// question that this could be more efficient in some cases.
		for(int i=0;i!=bounds.size();++i) {
			Type bound = bounds.get(i);
			if((i+1) == bounds.size()) {
				addReadConversion(bound,bytecodes);
				addCoercion(bound,to,freeSlot,constants,bytecodes);
				bytecodes.add(new Bytecode.Goto(exitLabel));
			} else {
				String label = freshLabel();
				labels.add(label);
				bytecodes.add(new Bytecode.Dup(convertType(from)));				
				translateTypeTest(label,from,bound,bytecodes,constants);				
			}
		}
		
		for(int i=0;i<labels.size();++i) {
			String label = labels.get(i);
			Type bound = bounds.get(i);
			bytecodes.add(new Bytecode.Label(label));
			addReadConversion(bound,bytecodes);
			addCoercion(bound,to,freeSlot,constants,bytecodes);
			bytecodes.add(new Bytecode.Goto(exitLabel));
		}
		
		bytecodes.add(new Bytecode.Label(exitLabel));
	}
	
	protected Type simplifyCoercion(Type from, Type to) {

		if (to instanceof Type.Union) {
			Type.Union t2 = (Type.Union) to;

			// First, check for identical type (i.e. no coercion necessary)
			for (Type b : t2.bounds()) {
				if (Type.isomorphic(from, b)) {
					// nothing to do
					return b;
				}
			}

			// Second, check for single non-coercive match
			for (Type b : t2.bounds()) {
				if (Type.isSubtype(b, from)) {					
					return b;
				}
			}

			// Third, test for single coercive match
			for (Type b : t2.bounds()) {
				if (Type.isCoerciveSubtype(b, from)) {
					return b;
				}
			}
		}

		return to;
	}
	
	/**
	 * The read conversion is necessary in situations where we're reading a
	 * value from a collection (e.g. WhileyList, WhileySet, etc) and then
	 * putting it on the stack. In such case, we need to convert boolean values
	 * from Boolean objects to bool primitives.
	 */
	public void addReadConversion(Type et, ArrayList<Bytecode> bytecodes) {
		if(et instanceof Type.Bool) {
			bytecodes.add(new Bytecode.CheckCast(JAVA_LANG_BOOLEAN));
			JvmType.Function ftype = new JvmType.Function(T_BOOL);
			bytecodes.add(new Bytecode.Invoke(JAVA_LANG_BOOLEAN,
					"booleanValue", ftype, Bytecode.VIRTUAL));
		} else if(et instanceof Type.Byte) {
			bytecodes.add(new Bytecode.CheckCast(JAVA_LANG_BYTE));
			JvmType.Function ftype = new JvmType.Function(T_BYTE);
			bytecodes.add(new Bytecode.Invoke(JAVA_LANG_BYTE,
					"byteValue", ftype, Bytecode.VIRTUAL));
		} else if(et instanceof Type.Char) {
			bytecodes.add(new Bytecode.CheckCast(JAVA_LANG_CHARACTER));
			JvmType.Function ftype = new JvmType.Function(T_CHAR);
			bytecodes.add(new Bytecode.Invoke(JAVA_LANG_CHARACTER,
					"charValue", ftype, Bytecode.VIRTUAL));
		} else {	
			addCheckCast(convertType(et),bytecodes);			
		}
	}

	/**
	 * The write conversion is necessary in situations where we're write a value
	 * from the stack into a collection (e.g. WhileyList, WhileySet, etc). In
	 * such case, we need to convert boolean values from bool primitives to
	 * Boolean objects.
	 */
	public void addWriteConversion(Type et, ArrayList<Bytecode> bytecodes) {
		if(et instanceof Type.Bool) {
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_BOOLEAN,T_BOOL);
			bytecodes.add(new Bytecode.Invoke(JAVA_LANG_BOOLEAN,
					"valueOf", ftype, Bytecode.STATIC));
		} else if(et instanceof Type.Byte) {
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_BYTE,
					T_BYTE);
			bytecodes.add(new Bytecode.Invoke(JAVA_LANG_BYTE, "valueOf", ftype,
					Bytecode.STATIC));
		} else if(et instanceof Type.Char) {
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_CHARACTER,
					T_CHAR);
			bytecodes.add(new Bytecode.Invoke(JAVA_LANG_CHARACTER, "valueOf", ftype,
					Bytecode.STATIC));
		}
	}

	public void addCheckCast(JvmType type, ArrayList<Bytecode> bytecodes) {
		// The following can happen in situations where a variable has type
		// void. In principle, we could remove this as obvious dead-code, but
		// for now I just avoid it.
		if(type instanceof JvmType.Void) {
			return;
		} else if(!type.equals(JAVA_LANG_OBJECT)) {
			// pointless to add a cast for object
			bytecodes.add(new Bytecode.CheckCast(type));
		}
	}

	/**
	 * Return true if this type is, or maybe reference counted.
	 * 
	 * @param t
	 * @return
	 */
	public static boolean isRefCounted(Type t) {
		return t != Type.T_BOOL && t != Type.T_INT && t != Type.T_REAL
				&& t != Type.T_STRING
				&& !Type.isSubtype(Type.T_PROCESS(Type.T_ANY), t); 
	}

	/**
	 * Add bytecodes for incrementing the reference count.
	 * 
	 * @param type
	 * @param bytecodes
	 */
	public static void addIncRefs(Type type, ArrayList<Bytecode> bytecodes) {
		if(isRefCounted(type)){
			JvmType jtype = convertType(type);
			JvmType.Function ftype = new JvmType.Function(jtype,jtype);			
			bytecodes.add(new Bytecode.Invoke(WHILEYUTIL,"incRefs",ftype,Bytecode.STATIC));
		}
	}
	
	/**
	 * The construct method provides a generic way to construct a Java object.
	 * 
	 * @param owner
	 * @param freeSlot
	 * @param bytecodes
	 * @param params
	 */
	public void construct(JvmType.Clazz owner, int freeSlot,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.New(owner));		
		bytecodes.add(new Bytecode.Dup(owner));
		ArrayList<JvmType> paramTypes = new ArrayList<JvmType>();		
		JvmType.Function ftype = new JvmType.Function(T_VOID,paramTypes);
		bytecodes.add(new Bytecode.Invoke(owner, "<init>", ftype,
				Bytecode.SPECIAL));
	}		 
	
		
	public final static Type.Process WHILEY_SYSTEM_OUT_T = (Type.Process) Type
			.minimise(Type.T_PROCESS(Type.T_EXISTENTIAL(new NameID(
					new ModuleID(new PkgID("whiley", "lang"), "System"), "1"))));

	public final static Type.Process WHILEY_SYSTEM_T = (Type.Process) Type
			.minimise(Type.T_PROCESS(Type.T_RECORD(new HashMap() {
				{
					put("out", WHILEY_SYSTEM_OUT_T);
					put("rest", Type.T_EXISTENTIAL(new NameID(new ModuleID(
							new PkgID("whiley", "lang"), "System"), "1")));
				}
			})));
		
	public final static JvmType.Clazz WHILEYUTIL = new JvmType.Clazz("wyjc.runtime","Util");
	public final static JvmType.Clazz WHILEYLIST = new JvmType.Clazz("wyjc.runtime","List");
	public final static JvmType.Clazz WHILEYSET = new JvmType.Clazz("wyjc.runtime","Set");
	public final static JvmType.Clazz WHILEYTUPLE = new JvmType.Clazz("wyjc.runtime","Tuple");
	public final static JvmType.Clazz WHILEYTYPE = new JvmType.Clazz("wyjc.runtime","Type");
	public final static JvmType.Clazz WHILEYIO = new JvmType.Clazz("wyjc.runtime","IO");
	public final static JvmType.Clazz WHILEYMAP = new JvmType.Clazz("wyjc.runtime","Dictionary");
	public final static JvmType.Clazz WHILEYRECORD = new JvmType.Clazz("wyjc.runtime","Record");	
	public final static JvmType.Clazz WHILEYPROCESS = new JvmType.Clazz(
			"wyjc.runtime", "Actor");	
	public final static JvmType.Clazz WHILEYMESSAGER = new JvmType.Clazz(
			"wyjc.runtime.concurrency", "Messager");
	public final static JvmType.Clazz WHILEYEXCEPTION = new JvmType.Clazz("wyjc.runtime","Exception");	
	public final static JvmType.Clazz BIG_INTEGER = new JvmType.Clazz("java.math","BigInteger");
	public final static JvmType.Clazz BIG_RATIONAL = new JvmType.Clazz("wyjc.runtime","BigRational");
	private static final JvmType.Clazz JAVA_LANG_CHARACTER = new JvmType.Clazz("java.lang","Character");
	private static final JvmType.Clazz JAVA_LANG_SYSTEM = new JvmType.Clazz("java.lang","System");
	private static final JvmType.Array JAVA_LANG_OBJECT_ARRAY = new JvmType.Array(JAVA_LANG_OBJECT);
	private static final JvmType.Clazz JAVA_UTIL_LIST = new JvmType.Clazz("java.util","List");
	private static final JvmType.Clazz JAVA_UTIL_SET = new JvmType.Clazz("java.util","Set");
	private static final JvmType.Clazz JAVA_LANG_REFLECT_METHOD = new JvmType.Clazz("java.lang.reflect","Method");
	private static final JvmType.Clazz JAVA_IO_PRINTSTREAM = new JvmType.Clazz("java.io","PrintStream");
	private static final JvmType.Clazz JAVA_LANG_RUNTIMEEXCEPTION = new JvmType.Clazz("java.lang","RuntimeException");
	private static final JvmType.Clazz JAVA_LANG_ASSERTIONERROR = new JvmType.Clazz("java.lang","AssertionError");
	private static final JvmType.Clazz JAVA_UTIL_COLLECTION = new JvmType.Clazz("java.util","Collection");	
	
	public JvmType.Function convertFunType(Type.Fun t) {		
		Type.Fun ft = (Type.Fun) t; 
		ArrayList<JvmType> paramTypes = new ArrayList<JvmType>();
		if(ft.receiver() != null) {
			paramTypes.add(convertType(ft.receiver()));
		}
		for(Type pt : ft.params()) {
			paramTypes.add(convertType(pt));
		}
		JvmType rt = convertType(ft.ret());			
		return new JvmType.Function(rt,paramTypes);		
	}
	
	public static JvmType convertType(Type t) {
		if(t == Type.T_VOID) {
			return T_VOID;
		} else if(t == Type.T_ANY) {
			return JAVA_LANG_OBJECT;
		} else if(t == Type.T_NULL) {
			return JAVA_LANG_OBJECT;
		} else if(t instanceof Type.Bool) {
			return T_BOOL;
		} else if(t instanceof Type.Byte) {
			return T_INT;
		} else if(t instanceof Type.Char) {
			return T_CHAR;
		} else if(t instanceof Type.Int) {
			return BIG_INTEGER;
		} else if(t instanceof Type.Real) {
			return BIG_RATIONAL;
		} else if(t instanceof Type.Meta) {
			return WHILEYTYPE;
		} else if(t instanceof Type.Strung) {
			return JAVA_LANG_STRING;
		} else if(t instanceof Type.List) {
			return WHILEYLIST;
		} else if(t instanceof Type.Set) {
			return WHILEYSET;
		} else if(t instanceof Type.Dictionary) {
			return WHILEYMAP;
		} else if(t instanceof Type.Record) {
			return WHILEYRECORD;
		} else if(t instanceof Type.Process) {
			return WHILEYPROCESS;
		} else if(t instanceof Type.Tuple) {
			return WHILEYTUPLE;
		} else if(t instanceof Type.Union) {
			// There's an interesting question as to whether we need to do more
			// here. For example, a union of a set and a list could result in
			// contains ?
			Type.Record tt = Type.effectiveRecordType(t);
			if(tt != null) {
				return WHILEYRECORD;
			} else {
				return JAVA_LANG_OBJECT;
			}
		} else if(t instanceof Type.Meta) {							
			return JAVA_LANG_OBJECT;			
		} else if(t instanceof Type.Fun) {						
			return JAVA_LANG_REFLECT_METHOD;
		}else {
			throw new RuntimeException("unknown type encountered: " + t);
		}		
	}	
			
	protected int label = 0;
	protected String freshLabel() {
		return "cfblab" + label++;
	}	
	
	public static String nameMangle(String name, Type.Fun ft) {				
		try {			
			return name + "$" + typeMangle(ft);
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}
		
	public static String typeMangle(Type.Fun ft) throws IOException {		
		JavaIdentifierOutputStream jout = new JavaIdentifierOutputStream();
		BinaryOutputStream binout = new BinaryOutputStream(jout);		
		Types.BinaryWriter tm = new Types.BinaryWriter(binout);
		Type.build(tm,ft);		
		binout.close(); // force flush		
		//testMangle1(ft);
		return jout.toString();		
	}	
	
	/**
	 * A constant is some kind of auxillary functionality used in generated code, which can be reused at multiple sites.  This includes value constants, and coercion functions. 
	 * @author djp
	 *
	 */
	public abstract static class Constant {}
	public static final class ValueConst extends Constant {
		public final Value value;
		public ValueConst(Value v) {
			value = v;
		}
		public boolean equals(Object o) {
			if(o instanceof ValueConst) {
				ValueConst vc = (ValueConst) o;
				return value.equals(vc.value);
			}
			return false;
		}
		public int hashCode() {
			return value.hashCode();
		}
		public static int get(Value value, HashMap<Constant,Integer> constants) {
			ValueConst vc = new ValueConst(value);
			Integer r = constants.get(vc);
			if(r != null) {
				return r;
			} else {
				int x = constants.size();
				constants.put(vc, x);
				return x;
			}			
		}
	}
	public static final class Coercion extends Constant {
		public final Type from;
		public final Type to;
		public Coercion(Type from, Type to) {
			this.from = from;
			this.to = to;
		}
		public boolean equals(Object o) {
			if(o instanceof Coercion) {
				Coercion c = (Coercion) o;
				return from.equals(c.from) && to.equals(c.to);
			}
			return false;
		}
		public int hashCode() {
			return from.hashCode() + to.hashCode();
		}
		public static int get(Type from, Type to, HashMap<Constant,Integer> constants) {
			Coercion vc = new Coercion(from,to);
			Integer r = constants.get(vc);
			if(r != null) {
				return r;
			} else {
				int x = constants.size();
				constants.put(vc, x);
				return x;
			}			
		}
	}
	
	/*			
	public static void testMangle1(Type.Fun ft) throws IOException {
		IdentifierOutputStream jout = new IdentifierOutputStream();
		BinaryOutputStream binout = new BinaryOutputStream(jout);
		Types.BinaryWriter tm = new Types.BinaryWriter(binout);		
		Type.build(tm,ft);
		binout.close();		
		System.out.println("MANGLED: " + ft + " => " + jout.toString());
		Type.Fun type = (Type.Fun) new Types.BinaryReader(
				new BinaryInputStream(new IdentifierInputStream(
						jout.toString()))).read();
		System.out.println("UNMANGLED TO: " + type);
		if(!type.equals(ft)) {
			throw new RuntimeException("INVALID TYPE RECONSTRUCTED");
		}
	}	
	*/
}

