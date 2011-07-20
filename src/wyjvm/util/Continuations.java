// Copyright (c) 2011, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
// * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
// * Redistributions in binary form must reproduce the above copyright
// notice, this list of conditions and the following disclaimer in the
// documentation and/or other materials provided with the distribution.
// * Neither the name of the <organization> nor the
// names of its contributors may be used to endorse or promote products
// derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
// THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyjvm.util;

import static wyjvm.lang.JvmTypes.T_INT;
import static wyjvm.lang.JvmTypes.T_VOID;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import wyil.util.Pair;
import wyjvm.attributes.Code;
import wyjvm.lang.Bytecode;
import wyjvm.lang.Bytecode.Dup;
import wyjvm.lang.Bytecode.Invoke;
import wyjvm.lang.Bytecode.Label;
import wyjvm.lang.Bytecode.Load;
import wyjvm.lang.Bytecode.LoadConst;
import wyjvm.lang.Bytecode.Return;
import wyjvm.lang.Bytecode.Store;
import wyjvm.lang.Bytecode.Switch;
import wyjvm.lang.Bytecode.Throw;
import wyjvm.lang.BytecodeAttribute;
import wyjvm.lang.ClassFile;
import wyjvm.lang.ClassFile.Method;
import wyjvm.lang.JvmType;
import wyjvm.lang.JvmType.Clazz;
import wyjvm.lang.JvmType.Function;
import wyjvm.lang.JvmType.Reference;
import wyjvm.util.dfa.TypeFlowAnalysis;

public class Continuations {

	private static final Clazz PROCESS = new Clazz("wyjc.runtime", "Actor"),
	    MESSAGER = new Clazz("wyjc.runtime.concurrency", "Messager"),
	    YIELDER = new Clazz("wyjc.runtime.concurrency", "Yielder");

	public void apply(ClassFile classfile) {
		for (Method method : classfile.methods()) {
			if (!method.name().equals("main")) {
				apply(method);
			}
		}
	}

	public void apply(Method method) {
		for (BytecodeAttribute attribute : method.attributes()) {
			if (attribute instanceof Code) {
				apply(method, (Code) attribute);
				break;
			}
		}
	}

	public void apply(Method method, Code code) {
		List<Bytecode> bytecodes = code.bytecodes();

		int location = 0;

		for (int i = 0; i < bytecodes.size(); ++i) {
			Bytecode bytecode = bytecodes.get(i);

			if (bytecode instanceof Invoke) {
				Invoke invoke = (Invoke) bytecode;

				if (invoke.owner.equals(MESSAGER) && invoke.name.startsWith("send")) {
					Bytecode next =
					    i == bytecodes.size() - 1 ? null : bytecodes.get(i + 1);

					boolean push =
					    invoke.name.equals("sendSync")
					        || !(next instanceof Return || next instanceof Throw);

					// This is a bit of a hack. If the number of bytecode operations
					// needed to set up the parameters changes, this will break.
					int position = -5;
					i += position;

					bytecodes.add(++i, new Dup(PROCESS));

					if (push) {
						bytecodes.add(++i, new LoadConst(location));
						bytecodes.add(++i, new Invoke(YIELDER, "yield",
						    new Function(T_VOID, T_INT), Bytecode.VIRTUAL));
					} else {
						bytecodes.add(++i, new Invoke(YIELDER, "cleanYield",
						    new Function(T_VOID), Bytecode.VIRTUAL));
					}
					
					i -= position;

					if (push) {
						bytecodes.add(++i, new Return(null));
						bytecodes.add(++i, new Label("resume" + location++));
						bytecodes.add(++i, new Load(0, PROCESS));
						bytecodes.add(++i, new Invoke(YIELDER, "unyield", new Function(
						    T_VOID), Bytecode.VIRTUAL));
					}
				}
			}
		}

		TypeFlowAnalysis typeAnalysis = new TypeFlowAnalysis(method);
		final int setPosition = -2;
		final int getPosition = 2;
		for (int i = 0; i < bytecodes.size(); ++i) {
			Bytecode bytecode = bytecodes.get(i);
			if (bytecode instanceof Label) {
				Label label = (Label) bytecode;
				if (label.name.startsWith("resume")) {
					Map<Integer, JvmType> types;
					try {
						types = typeAnalysis.typesAt(i);
					} catch (RuntimeException rex) {
						rex.printStackTrace();
						throw rex;
					}
					
					Set<Integer> vars = types.keySet();
					vars.remove(0);

					for (int var : vars) {
						JvmType type = types.get(var);
						i += setPosition;
						bytecodes.add(++i, new Dup(PROCESS));
						bytecodes.add(++i, new Load(var, type));
						bytecodes.add(++i, new Invoke(PROCESS, "set",
						    new Function(T_VOID, T_INT, type), Bytecode.VIRTUAL));
						i -= setPosition;
					}

					for (int var : vars) {
						JvmType type = types.get(var);
						i += getPosition;
						bytecodes.add(++i, new Dup(PROCESS));

						String name;
						if (type instanceof Reference) {
							name = "getObject";
						} else {
							// This adds a terrible dependency between JvmType and Yielder,
							// but it's good enough for now.
							name = "get" + type.getClass().getSimpleName();
						}

						bytecodes.add(++i, new Invoke(PROCESS, name,
						    new Function(type, T_INT), Bytecode.VIRTUAL));
						bytecodes.add(++i, new Store(var, type));
						i -= getPosition;
					}
				}
			}
		}

		if (location > 0) {
			int i = 0;
			
			bytecodes.add(++i, new Load(0, PROCESS));
			bytecodes.add(++i, new Invoke(YIELDER, "getCurrentStateLocation",
			    new Function(T_INT), Bytecode.VIRTUAL));
			
			List<Pair<Integer, String>> cases =
			    new ArrayList<Pair<Integer, String>>(location);
			for (int j = 0; j < location; ++j) {
				cases.add(new Pair<Integer, String>(j, "resume" + j));
			}
			
			bytecodes.add(++i, new Switch("begin", cases));
			bytecodes.add(++i, new Label("begin"));
		}
	}
}
