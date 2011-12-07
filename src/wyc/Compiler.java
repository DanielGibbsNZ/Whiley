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

package wyc;

import java.io.*;
import java.util.*;

import wyil.*;
import wyil.io.ModuleReader;
import wyil.lang.*;
import wyil.util.*;
import wyc.lang.*;
import wyc.stages.*;

public class Compiler implements Logger {		
	protected NameResolver resolver;	
	protected ArrayList<Transform> stages;

	public Compiler(NameResolver resolver, List<Transform> stages) {
		this.resolver = resolver;
		this.stages = new ArrayList<Transform>(stages);
	}
	
	/**
	 * The logout output stream is used to write log information about the
	 * status of compilation. The default stream just discards everything.
	 */
	protected PrintStream logout = new PrintStream(new OutputStream() {
		public void write(byte[] b) { /* don't do anything! */
		}

		public void write(byte[] b, int x, int y) { /* don't do anything! */
		}

		public void write(int x) { /* don't do anything! */
		}
	});
	
	public void setLogOut(OutputStream logout) {
		this.logout = new PrintStream(logout);
	}

	public List<WhileyFile> compile(List<File> files) throws Exception {
		Runtime runtime = Runtime.getRuntime();
		long start = System.currentTimeMillis();		
		long memory = runtime.freeMemory();
		
		ArrayList<WhileyFile> wyfiles = new ArrayList<WhileyFile>();
		for (File f : files) {
			WhileyFile wf = innerParse(f);			
			wyfiles.add(wf);			
			resolver.preregister(wf.skeleton());			
		}
				
		for (WhileyFile m : wyfiles) {
			resolveNames(m);			
		}
		
		List<Module> modules = buildModules(wyfiles);				
		for(Module m : modules) {
			resolver.register(m);
		}		
		
		finishCompilation(modules);		
		
		long endTime = System.currentTimeMillis();
		logTotalTime("Compiled " + files.size() + " file(s)",endTime-start, memory - runtime.freeMemory());
		
		return wyfiles;
	}
		
	/**
	 * This method simply parses a whiley file into an abstract syntax tree. It
	 * makes little effort to check whether or not the file is syntactically
	 * correct. In particular, it does not determine the correct type of all
	 * declarations, expressions, etc.
	 * 
	 * @param file
	 * @return
	 * @throws IOException
	 */
	public WhileyFile innerParse(File file) throws IOException {
		Runtime runtime = Runtime.getRuntime();
		long start = System.currentTimeMillis();		
		long memory = runtime.freeMemory();
		WhileyLexer wlexer = new WhileyLexer(file.getPath());		
		List<WhileyLexer.Token> tokens = new WhileyFilter().filter(wlexer.scan());		

		WhileyParser wfr = new WhileyParser(file.getPath(), tokens);
		logTimedMessage("[" + file + "] Parsing complete",
				System.currentTimeMillis() - start, memory - runtime.freeMemory());		
		return wfr.read(); 
	}		
	
	/**
	 * This method puts the given module through the second half of the
	 * compilation pipeline. In particular, it propagates and generates types
	 * for all expressions used within the module, as well as checking for
	 * definite assignment and performing verification checking.
	 * 
	 * @param wf
	 */
	public void finishCompilation(List<Module> modules) throws Exception {				
		// Register the updated file
		for(Module module : modules) {
			resolver.register(module);
		}
		
		for(Transform stage : stages) {
			for(Module module : modules) {
				process(module,stage);
			}
		}		
	}
	
	protected void process(Module module, Transform stage) throws Exception {
		Runtime runtime = Runtime.getRuntime();
		long start = System.currentTimeMillis();		
		long memory = runtime.freeMemory();
		String name = name(stage.getClass().getSimpleName());		
		
		try {						
			stage.apply(module);			
			logTimedMessage("[" + module.filename() + "] applied "
					+ name, System.currentTimeMillis() - start, memory - runtime.freeMemory());
			System.gc();
		} catch (RuntimeException ex) {
			logTimedMessage("[" + module.filename() + "] failed on "
					+ name + " (" + ex.getMessage() + ")",
					System.currentTimeMillis() - start, memory - runtime.freeMemory());
			throw ex;
		} catch (IOException ex) {
			logTimedMessage("[" + module.filename() + "] failed on "
					+ name + " (" + ex.getMessage() + ")",
					System.currentTimeMillis() - start, memory - runtime.freeMemory());
			throw ex;
		}
	}
	
	public static String name(String camelCase) {
		boolean firstTime = true;
		String r = "";
		for(int i=0;i!=camelCase.length();++i) {
			char c = camelCase.charAt(i);
			if(!firstTime && Character.isUpperCase(c)) {
				r += " ";
			} 
			firstTime=false;
			r += Character.toLowerCase(c);;
		}
		return r;
	}
	
	protected void resolveNames(WhileyFile m) {
		Runtime runtime = Runtime.getRuntime();
		long start = System.currentTimeMillis();		
		long memory = runtime.freeMemory();		
		new NameResolution(resolver).resolve(m);
		logTimedMessage("[" + m.filename + "] resolved names",
				System.currentTimeMillis() - start, memory - runtime.freeMemory());		
		
	}
	
	protected List<Module> buildModules(List<WhileyFile> files) {		
		Runtime runtime = Runtime.getRuntime();
		long start = System.currentTimeMillis();		
		long memory = runtime.freeMemory();		
		List<Module> modules = new ModuleBuilder(resolver).resolve(files);
		logTimedMessage("built modules",
				System.currentTimeMillis() - start, memory - runtime.freeMemory());
		return modules;		
	}	
	
	/**
	 * This method is just a helper to format the output
	 */
	public void logTimedMessage(String msg, long time, long memory) {
		logout.print(msg);
		logout.print(" ");
		double mem = memory;
		mem = mem / (1024*1024);
		memory = (long) mem;
		String stats = " [" + Long.toString(time) + "ms";
		if(memory > 0) {
			stats += "+" + Long.toString(memory) + "mb]";
		} else if(memory < 0) {
			stats += Long.toString(memory) + "mb]";
		} else {
			stats += "]";
		}
		for (int i = 0; i < (90 - msg.length() - stats.length()); ++i) {
			logout.print(".");
		}		
		logout.println(stats);
	}	
	
	public void logTotalTime(String msg, long time, long memory) {
		memory = memory / 1024;
		
		for (int i = 0; i <= 90; ++i) {
			logout.print("=");
		}
		
		logout.println();
		
		logout.print(msg);
		logout.print(" ");

		double mem = memory;
		mem = mem / (1024*1024);
		memory = (long) mem;
		String stats = " [" + Long.toString(time) + "ms";
		if(memory > 0) {
			stats += "+" + Long.toString(memory) + "mb]";
		} else if(memory < 0) {
			stats += Long.toString(memory) + "mb]";
		} else {
			stats += "]";
		}

		for (int i = 0; i < (90 - msg.length() - stats.length()); ++i) {
			logout.print(".");
		}
		
		logout.println(stats);		
	}	
}
