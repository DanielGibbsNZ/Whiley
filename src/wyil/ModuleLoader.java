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

package wyil;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import wyjc.io.ClassFileLoader; // to be deprecated
import wyil.io.*;
import wyil.lang.*;
import wyil.util.*;
import wyil.util.path.Path;

/**
 * Responsible for locating whiley modules on the WHILEYPATH and SOURCEPATH, and
 * retaining information about them which can be used to compile other whiley
 * files. The WHILEYPATH and SOURCEPATH consist of a list of "package roots",
 * where each root can be a directory, a Jar file, or even something else.
 * 
 * <b>NOTE:</b> This class is designed to be subclassed. However, in the future,
 * it should be turned into an interface.
 * 
 * @author David J. Pearce
 * 
 */
public final class ModuleLoader {
	/**
	 * The source path is a list of locations which must be searched in
	 * ascending order for whiley files.
	 */
	private ArrayList<Path.Root> sourcepath;
	
	/**
	 * The whiley path is a list of locations which must be searched in
	 * ascending order for wyil files.
	 */
	private ArrayList<Path.Root> whileypath;
	
	/**
	 * A map from module identifiers to module objects. This is the master cache
	 * of modules which have been loaded during the compilation process. Once a
	 * module has been entered into the moduletable, it will not be loaded
	 * again.
	 */
	private HashMap<ModuleID, Module> moduletable = new HashMap<ModuleID, Module>();
	
	/**
	 * Contains a set of ModuleIDs which have been ignored. This is purely to
	 * prevent continually rereading those modules.
	 **/
	private HashSet<ModuleID> ignored = new HashSet<ModuleID>();
	
	
	/**
	 * This identifies which packages have had their contents fully resolved.
	 * All items in a resolved package must have been loaded into the filetable.
	 */
	private HashMap<PkgID, ArrayList<Path.Root>> packageroots = new HashMap<PkgID, ArrayList<Path.Root>>();

	/**
     * The failed packages set is a collection of packages which have been
     * requested, but are known not to exist. The purpose of this cache is
     * simply to speed up package resolution.
     */
	private final HashSet<PkgID> failedPackages = new HashSet<PkgID>();
	
	/**
	 * The suffix map maps suffixes to module readers for those suffixes.
	 */
	private final HashMap<String,ModuleReader> suffixMap = new HashMap<String,ModuleReader>();	
	
	/**
	 * The logger is used to log messages from the module loader.
	 */
	private Logger logger;
	
	public ModuleLoader(Collection<Path.Root> sourcepath, Collection<Path.Root> whileypath, Logger logger) {
		this.logger = logger;
		this.sourcepath = new ArrayList<Path.Root>(sourcepath);
		this.whileypath = new ArrayList<Path.Root>(whileypath);		
	}
	
	public ModuleLoader(Collection<Path.Root> sourcepath, Collection<Path.Root> whileypath) {
		this.logger = Logger.NULL;
		this.sourcepath = new ArrayList<Path.Root>(sourcepath);
		this.whileypath = new ArrayList<Path.Root>(whileypath);		
	}
	
	/**
	 * Set the logger for this module loader.
	 * @param logger
	 */
	public void setLogger(Logger logger) {
		this.logger = logger;
	}
	
	/**
	 * Associate a given module reader with a given suffix.
	 * 
	 * @param suffix
	 *            --- filename extension of reader to associate with.
	 * @param reader
	 */
	public void setModuleReader(String suffix, ModuleReader reader) {
		suffixMap.put(suffix, reader);
	}	
	
	/**
	 * Register a given module with this loader. This ensures that when requests
	 * are made for this module, this will be returned instead of searching for
	 * it on the whileypath.
	 * 
	 * @param module
	 *            --- module to register.
	 */
	public void register(Module module) {			
		moduletable.put(module.id(), module);	
	}		
	
	/**
	 * This method attempts to load a whiley module. The module is searched for
	 * on the WHILEYPATH. A resolve error is thrown if the module cannot be
	 * found or otherwise loaded.
	 * 
	 * @param module
	 *            The module to load
	 * @return the loaded module
	 */
	public Module loadModule(ModuleID module) throws ResolveError {		
		Module m = moduletable.get(module);
						
		if (m != null) {
			return m; // module was previously loaded and cached
		} else if (ignored.contains(module)) {
			throw new ResolveError("Unable to find module: " + module);
		}
			
		// module has not been previously loaded.
		resolvePackage(module.pkg());						
		
		try {
			// ok, now look for module inside package roots.
			Path.Entry entry = null;
			for(Path.Root root : packageroots.get(module.pkg())) {
				entry = root.lookup(module);
				if(entry != null) {
					break;
				}
			}			
			if(entry == null) {
				throw new ResolveError("Unable to find module: " + module);
			}
			m = readModuleInfo(entry);
			if(m == null) {
				throw new ResolveError("Unable to find module: " + module);
			}
			return m;						
		} catch(RuntimeException e) {
			throw e;
		} catch(Exception e) {				
			throw new ResolveError("Unable to find module: " + module,e);
		}	
	}	
	
	public Set<ModuleID> loadPackage(PkgID pid) throws ResolveError {
		resolvePackage(pid);
		HashSet<ModuleID> contents = new HashSet<ModuleID>();
		try {
			for(Path.Root root : packageroots.get(pid)) {			
				for (Path.Entry e : root.list(pid)) {
					contents.add(e.id());
				}
			}
		} catch(Exception e) {
			throw new ResolveError("unknown failure",e);
		}
		return contents;
	}
	
	/**
	 * This method searches the WHILEYPATH looking for a matching package. If
	 * the package is found, it's contents are loaded. Otherwise, a resolve
	 * error is thrown.
	 * 
	 * @param pkg
	 *            --- the package to look for
	 * @return
	 */
	public void resolvePackage(PkgID pkg) throws ResolveError {							
		// First, check if we have already resolved this package.						
		if(packageroots.containsKey(pkg)) {
			return;
		} else if(failedPackages.contains(pkg)) {			
			// yes, it's already been resolved but it doesn't exist.
			throw new ResolveError("package not found: " + pkg);
		}

		ArrayList<Path.Root> roots = new ArrayList<Path.Root>();
		try {
			// package not been previously resolved, so first try sourcepath.
			for (Path.Root c : sourcepath) {
				if(c.exists(pkg)) {					
					roots.add(c);
				}				
			}
			// second, try whileypath.
			for (Path.Root c : whileypath) {
				if(c.exists(pkg)) {					
					roots.add(c);
				}
			}
		} catch(RuntimeException e) {
			throw e;
		} catch(Exception e) {							
			// silently ignore.
		}
				
		if(!roots.isEmpty()) {	
			packageroots.put(pkg,roots);
		} else {
			failedPackages.add(pkg);
			throw new ResolveError("package not found: " + pkg);
		}
	}	
	
	private Module readModuleInfo(Path.Entry entry) throws Exception {
		Runtime runtime = Runtime.getRuntime();
		long time = System.currentTimeMillis();
		long memory = runtime.freeMemory();
		ModuleReader reader = suffixMap.get(entry.suffix());
		ModuleID mid = entry.id();
		
		Module mi = reader.read(mid, entry.contents());
		
		if(mi != null) {
			logger.logTimedMessage("Loaded " + entry.location() + ":" + mid,
					System.currentTimeMillis() - time, memory - runtime.freeMemory());
			moduletable.put(mi.id(), mi);
		} else {
			
			logger.logTimedMessage("Ignored " + entry.location() + ":" + mid,
					System.currentTimeMillis() - time, memory - runtime.freeMemory());
			ignored.add(mid);
		}
		return mi;
	}
}
