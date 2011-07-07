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

package wyil.transforms;

import java.util.*;

import wyil.ModuleLoader;
import wyil.Transform;
import wyil.lang.*;
import wyil.lang.Code.*;
import static wyil.util.SyntaxError.*;

public class FunctionCheck implements Transform {
	private final ModuleLoader loader;
	private String filename;

	public FunctionCheck(ModuleLoader loader) {
		this.loader = loader;
	}
	
	public Module apply(Module module) {
		filename = module.filename();
		
		for(Module.Method method : module.methods()) {
			check(method);
		}
		return module;
	}
		
	public void check(Module.Method method) {		
		if (method.type().receiver() == null) {
			for (Module.Case c : method.cases()) {
				check(c.body(), method);
			}
		}
	}
	
	protected void check(Block block,  Module.Method method) {		
		for (int i = 0; i != block.size(); ++i) {
			Block.Entry stmt = block.get(i);
			Code code = stmt.code;
			if (code instanceof Code.Send || code instanceof Code.IndirectSend) {
				// external message send
				syntaxError("cannot send message from function", filename, stmt);
			} else if(code instanceof Code.Invoke && ((Code.Invoke)code).type.receiver() != null) {
				// internal message send
				syntaxError("cannot call method message from function", filename, stmt);
			} else if(code instanceof Code.Spawn) {
				syntaxError("cannot spawn process from function",filename,stmt);
			} else if(code instanceof Code.ProcLoad){ 
				syntaxError("cannot access process from function",filename,stmt);				
			}
		}	
	}
}
