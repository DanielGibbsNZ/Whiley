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
import wyil.lang.*;
import wyil.util.*;

/**
 * Responsible for locating whiley modules on the WHILEYPATH, and retaining
 * information about them which can be used to compile other whiley files. This
 * is a critical component of the Whiley compiler.
 * 
 * @author David J. Pearce
 * 
 */
public class ModuleLoader {
	/**
	 * The Closed World Assumption indicates whether or not we should attempt to
	 * compile source files that we encouter.
	 */
	private boolean closedWorldAssumption = true;
	
	/**
     * The whiley path is a list of directories which must be
     * searched in ascending order for whiley files.
     */
	private ArrayList<String> whileypath;
	
	/**
     * A map from module names in the form "xxx.yyy" to module objects. This is
     * the master cache of modules which have been loaded during the compilation
     * process. Once a module has been entered into the moduletable, it will not
     * be loaded again.
     */
	private HashMap<ModuleID, Module> moduletable = new HashMap<ModuleID, Module>();  
	
	/**
     * A map from module names in the form "xxx.yyy" to skeleton objects. This
     * is required to permit preregistration of source files during compilation.
     */
	private HashMap<ModuleID, Skeleton> skeletontable = new HashMap<ModuleID, Skeleton>();

	/**
	 * The module reader is responsible for actually reading module files off
	 * the file system and parsing them. Depending upon what the target platform
	 * is, the actual loader will vary.
	 */
	private final ClassFileLoader moduleReader;
	
	/**
	 * A Package object contains information about a particular package,
	 * including the following information:
	 * 
	 * 1) where it can be found on the file system (either a directory).
	 * 
	 * 2) what modules it contains.
	 * 
	 */
	private static final class Package {
		
		/**
         * The modules field contains the names of those modules contained in
         * this package.
         */
		public final HashSet<String> modules = new HashSet<String>();
				
		/**
		 * The locations list contains the list of locations that have been
		 * identified for a particular package. Each location identifies either
		 * a jar file, or a directory. The order of locations found is
		 * important --- those which come first have higher priority.
		 */
		public final ArrayList<File> locations = new ArrayList<File>();
	}

	/**
	 * Provides basic information regarding what names are defined within a
	 * module. It represents the minimal knowledge regarding a module that we
	 * can have. Skeletons are used early on in the compilation process to help
	 * with name resolution.
	 * 
	 * @author David J. Pearce
	 * 
	 */
	public abstract static class Skeleton {
		protected final ModuleID mid;

		public Skeleton(ModuleID mid) {
			this.mid = mid;
		}
		
		public ModuleID id() {
			return mid;
		}

		public abstract boolean hasName(String name);
	}
	
	/**
     * The packages map maps each package to its PackageInfo record. This means
     * we can quickly identify packages will have already been loaded.
     */
	private final HashMap<PkgID, Package> packages = new HashMap<PkgID, Package>();
	
	/**
     * The failed packages set is a collection of packages which have been
     * requested, but are known not to exist. The purpose of this cache is
     * simply to speek up package resolution.
     */
	private final HashSet<PkgID> failedPackages = new HashSet<PkgID>();
	
	/**
	 * The logger is used to log messages from the module loader.
	 */
	private Logger logger;
	
	public ModuleLoader(Collection<String> whileypath, ClassFileLoader loader,
			Logger logger) {
		this.logger = logger;
		this.whileypath = new ArrayList<String>(whileypath);
		this.moduleReader = loader;
	}
	
	public ModuleLoader(Collection<String> whileypath, ClassFileLoader loader) {
		this.logger = Logger.NULL;
		this.whileypath = new ArrayList<String>(whileypath);
		this.moduleReader = loader;
	}
	
	public void setClosedWorldAssumption(boolean flag) {
		closedWorldAssumption = flag;
	}
	
	/**
	 * Set the logger for this module loader.
	 * @param logger
	 */
	public void setLogger(Logger logger) {
		this.logger = logger;
	}
		
	/**
	 * This function checks whether the supplied package exists or not.
	 * 
	 * @param pkg
	 *            The package whose existence we want to check for.
	 * 
	 * @return true if the package exists, false otherwise.
	 */
	public boolean isPackage(PkgID pkg) {
		try {
			return resolvePackage(pkg) != null;
		} catch(ResolveError e) {
			return false;
		}
	}	
	
	public void preregister(Skeleton skeleton, String filename) {		
		skeletontable.put(skeleton.id(), skeleton);
		File parent = new File(filename).getParentFile();
		if(parent == null) {
			// this is necessary, since parent might be null if this is the
			// default package.
			parent = new File(".");
		} 
		addPackageItem(skeleton.id().pkg(),skeleton.id().module(),parent);		
	}
	
	public void register(Module module) {			
		moduletable.put(module.id(), module);	
	}		
	
	/**
	 * This methods attempts to resolve the correct package for a named item,
	 * given a list of imports. Resolving the correct package may require
	 * loading modules as necessary from the whileypath and/or compiling modules
	 * for which only source code is currently available.
	 * 
	 * @param name
	 *            A module name without package specifier.
	 * @param imports
	 *            A list of import declarations to search through. Imports are
	 *            searched in order of appearance.
	 * @return The resolved package.
	 * @throws ModuleNotFoundException
	 *             if it couldn't resolve the name
	 */
	public NameID resolveAsName(String name, List<Import> imports)
			throws ResolveError {	
		
		for (Import imp : imports) {
			if(imp.matchName(name)) {
				for(ModuleID mid : matchImport(imp)) {
					try {														
						Skeleton mi = loadSkeleton(mid);					
						if (mi.hasName(name)) {
							return new NameID(mid,name);
						} 					
					} catch(ResolveError rex) {
						// ignore. This indicates we simply couldn't resolve
                        // this module. For example, if it wasn't a whiley class
                        // file.						
					}
				}
			}
		}
		
		throw new ResolveError("name not found: " + name);
	}
	
	public NameID resolveAsName(List<String> names, List<Import> imports) throws ResolveError {
		if(names.size() == 1) {
			return resolveAsName(names.get(0),imports);
		} else if(names.size() == 2) {
			String name = names.get(1);
			ModuleID mid = resolveAsModule(names.get(0),imports);
			Skeleton mi = loadSkeleton(mid);					
			if (mi.hasName(name)) {
				return new NameID(mid,name);
			} 
		} else {
			String name = names.get(names.size()-1);
			String module = names.get(names.size()-2);
			PkgID pkg = new PkgID(names.subList(0,names.size()-2));
			ModuleID mid = new ModuleID(pkg,module);
			Skeleton mi = loadSkeleton(mid);					
			if (mi.hasName(name)) {
				return new NameID(mid,name);
			}
		}
		
		String name = null;
		for(String n : names) {
			if(name != null) {
				name = name + "." + n;
			} else {
				name = n;
			}			
		}
		throw new ResolveError("name not found: " + name);
	}
	
	/**
	 * This method attempts to resolve the given name as a module name, given a
	 * list of imports.
	 * 
	 * @param name
	 * @param imports
	 * @return
	 * @throws ResolveError
	 */
	public ModuleID resolveAsModule(String name, List<Import> imports)
			throws ResolveError {
		
		for (Import imp : imports) {
			for(ModuleID mid : matchImport(imp)) {				
				if(mid.module().equals(name)) {
					return mid;
				}
			}
		}
		
		
		throw new ResolveError("module not found: " + name);
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
		if(m != null) {
			return m; // module was previously loaded and cached
		}
			
		// module has not been previously loaded.
		Package pkg = resolvePackage(module.pkg());		
		// check for error
		if(pkg == null) {			
			throw new ResolveError("Unable to find package: " + module.pkg());
		} else {
			try {
				// ok, now look for module inside package.
				m = loadModule(module,pkg);
				if(m == null) {					
					throw new ResolveError("Unable to find module: " + module);
				}
			} catch(IOException io) {				
				throw new ResolveError("Unable to find module: " + module,io);
			}
		}
		
		return m;		
	}
	
	/**
	 * This method attempts to load a whiley module skeleton. A skeleton
	 * provides signature information about a module. For example, the signature
	 * of all methods. However, a skeleton does not provide access to a methods
	 * body. The skeleton is looked up in the internal skeleton table. If not
	 * found there, then the WHILEYPATH is searched. A resolve error is thrown
	 * if the module cannot be found or otherwise loaded.
	 * 
	 * @param module
	 *            The module skeleton to load
	 * @return the loaded module
	 */
	public Skeleton loadSkeleton(ModuleID module) throws ResolveError {
		Skeleton skeleton = skeletontable.get(module);
		if(skeleton != null) {
			return skeleton;
		} 		
		Module m = moduletable.get(module);
		if(m != null) {
			return m; // module was previously loaded and cached
		}
		
		// module has not been previously loaded.
		Package pkg = resolvePackage(module.pkg());
		// check for error
		if(pkg == null) {
			throw new ResolveError("Unable to find package: " + module.pkg());
		} else {
			try {
				// ok, now look for module inside package.
				skeleton = loadSkeleton(module,pkg);
				if(skeleton == null) {
					throw new ResolveError("Unable to find module: " + module);
				}
			} catch(IOException io) {
				throw new ResolveError("Unagle to find module: " + module,io);
			}
		}
		
		return skeleton;		
	}
	
	private Skeleton loadSkeleton(ModuleID module, Package pkg) throws ResolveError, IOException {		
		String filename = module.fileName();	
		String jarname = filename.replace(File.separatorChar,'/') + ".class";

		for(File location : pkg.locations) {			
			if (location.getName().endsWith(".jar")) {
				// location is a jar file
				JarFile jf = new JarFile(location);				
				JarEntry je = jf.getJarEntry(jarname);
				if (je == null) { continue; }  								
				Module mi = readModuleInfo(module, jarname, jf.getInputStream(je));
				if(mi != null) { return mi; }
			} else {
				File classFile = new File(location.getPath(),filename + ".class");					
				File srcFile = new File(location.getPath(),filename + ".whiley");

				if(classFile.exists()) {															
					// Here, there is no sourcefile, but there is a
					// classfile.
					// So, no need to compile --- just load the class file!
					Module mi = readModuleInfo(module, classFile.getPath(), new FileInputStream(classFile));
					if(mi != null) {
						return mi;
					}
				}			
			}
		}

		throw new ResolveError("unable to find module: " + module);
	}

	/**
	 * This method attempts to read a whiley module from a given package. A
	 * resolve error is thrown if the module cannot be found or otherwise
	 * loaded.
	 * 
	 * @param module
	 *            The module to load
	 * @param pkgIngo
	 *            Information about the including package, in particular where
	 *            it can be located on the file system.
	 * @return
	 */
	private Module loadModule(ModuleID module, Package pkg)
			throws ResolveError, IOException {			
		String filename = module.fileName();	
		String jarname = filename.replace(File.separatorChar,'/') + ".class";
		
		for(File location : pkg.locations) {			
			if (location.getName().endsWith(".jar")) {
				// location is a jar file				
				JarFile jf = new JarFile(location);				
				JarEntry je = jf.getJarEntry(jarname);
				if (je == null) { continue; }  								
				return readModuleInfo(module, jarname, jf.getInputStream(je));
			} else {
				File classFile = new File(location.getPath(),filename + ".class");									
				if(classFile.exists()) {															
					// Here, there is no sourcefile, but there is a classfile.
					// So, no need to compile --- just load the class file!
					return readModuleInfo(module, jarname, new FileInputStream(classFile));					
				}			
			}
		}
				
		throw new ResolveError("unable to find module: " + module);
	}
	
	/**
	 * This method takes a given import declaration, and expands it to find all
	 * matching modules.
	 * 
	 * @param imp
	 * @return
	 */
	private List<ModuleID> matchImport(Import imp) {
		ArrayList<ModuleID> matches = new ArrayList<ModuleID>();
		for (PkgID pid : matchPackage(imp.pkg)) {
			try {
				Package pkgInfo = resolvePackage(pid);
				for (String n : pkgInfo.modules) {					
					if (imp.matchModule(n)) {
						matches.add(new ModuleID(pid, n));
					}
				}
			} catch (ResolveError ex) {
				// dead code
			}
		}
		return matches;
	}
	
	/**
	 * This method takes a given package id from an import declaration, and
	 * expands it to find all matching packages. Note, the package id may
	 * contain various wildcard characters to match multiple actual packages.
	 * 
	 * @param imp
	 * @return
	 */
	private List<PkgID> matchPackage(PkgID pkg) {
		ArrayList<PkgID> matches = new ArrayList<PkgID>();
		try {
			// TODO: this needs to be correct to support more interesting regexes.
			Package p = resolvePackage(pkg);
			matches.add(pkg);
		} catch(ResolveError er) {}
		return matches;
	}
	
	/**
     * This method searches the WHILEYPATH looking for a matching package.
     * 
     * @param pkg --- the package to look for
     * @return
     */
	private Package resolvePackage(PkgID pkg) throws ResolveError {			
		// First, check if we have already resolved this package.						
		Package pkgInfo = packages.get(pkg);				
		
		if(pkgInfo != null) {			
			return pkgInfo;
		} else if(failedPackages.contains(pkg)) {
			// yes, it's already been resolved but it doesn't exist.
			throw new ResolveError("package not found: " + pkg);
		}

		// package has not been previously resolved.
		String filePkg = pkg.fileName();
		
		// Second, try whileypath
		for (String dir : whileypath) {			
			// check if whileypath entry is a jarfile or a directory		
			if (!dir.endsWith(".jar")) {
				// dir is not a Jar file, so I assume it's a directory.				
				pkgInfo = lookForPackage(dir,pkg,filePkg);
				if(pkgInfo != null) {					
					packages.put(pkg,pkgInfo);
					return pkgInfo;
				}				
			} else {			
				// this is a jar file
				try {					
					JarFile jf = new JarFile(dir);
					for (Enumeration<JarEntry> e = jf.entries(); e
							.hasMoreElements();) {
						JarEntry je = e.nextElement();
						String entryName = je.getName();
						if (entryName.endsWith(".class")) {
							// now strip off ".class"
							entryName = entryName.substring(0, entryName
									.length() - 6);
							entryName = entryName.replace("/", ".");
							// strip off package information
							String pkgName = pathParent(entryName);
							String moduleName = pathChild(entryName);

							// now add to package map							
							addPackageItem(new PkgID(pkgName.split("\\.")),
									moduleName, new File(dir));
						}
					}					
					
					pkgInfo = packages.get(pkg);
					if(pkgInfo != null) {						
						return pkgInfo;
					}
						
				} catch (IOException e) {
					// jarfile listed on classpath doesn't exist!
					// So, silently ignore it (this is what javac does).
				}
			}
		}
				
		failedPackages.add(pkg);
		throw new ResolveError("package not found: " + pkg);
	}
	
	private Module readModuleInfo(ModuleID module, String filename,
			InputStream input) throws IOException {
		long time = System.currentTimeMillis();		
		
		// TODO: the following line should be removed in the near future.
		Module mi = moduleReader.read(module, filename, input);

		if (mi != null) {
			logger.logTimedMessage("Loaded " + filename,
					System.currentTimeMillis() - time);

			// observe that createModule will return null if the
			// class file is *not* from whiley source file. In such
			// case, we simply ignore the class file altogether.
			moduletable.put(module, mi);
			return mi;
		} else {
			logger.logTimedMessage("Ignored " + filename,
					System.currentTimeMillis() - time);
			return null;
		}
	} 
	
	/**
     * This traverses the directory tree, starting from dir, looking for class
     * or java files. There's probably a bug if the directory tree is cyclic!
     */
	private Package lookForPackage(String root, PkgID pkg, String filepkg) {				
		if(root.equals("")) {
			root = ".";
		}				
		
		File f = new File(root + File.separatorChar + filepkg);				
		if (f.isDirectory()) {			
			for (String file : f.list()) {				
				if (!closedWorldAssumption && file.endsWith(".whiley")) {
					// strip  off ".whiley" to get module name
					String name = file.substring(0,file.length()-7);
					addPackageItem(pkg, name, new File(root));					
				} else if (file.endsWith(".class")) {					
					// strip  off ".class" to get module name
					String name = file.substring(0,file.length()-6);					
					addPackageItem(pkg, name, new File(root));					
				}
			}
		}

		return packages.get(pkg);
	}
	
	/**
	 * This adds a module to the module table. 
	 * 
	 * @param pkg
	 *            The name of the module package
	 * @param name
	 *            The name of the module to be added
	 * @param location
	 *            The location of the enclosing package. This is either a jar
	 *            file, or a directory.
	 */
	private void addPackageItem(PkgID pkg, String name, File pkgLocation) {
		Package items = packages.get(pkg);
		if (items == null) {						
			items = new Package();			
			packages.put(pkg, items);
		} 

		// add the class in question
		items.modules.add(name);
		
		// now, add the location (if it wasn't already added)  
		if(!items.locations.contains(pkgLocation)) {			
			items.locations.add(pkgLocation);
		}		

		// Finally, add all enclosing packages of this package as
		// well. Otherwise, isPackage("whiley") can fails even when we know about
		// a particular package.		
		for(int i=0;i<=pkg.size()-1;++i) {
			PkgID p = pkg.subpkg(0,i);			
			if (packages.get(p) == null) {
				packages.put(p, new Package());
			}
		}
	}
	
	/**
	 * Given a path string of the form "xxx.yyy.zzz" this returns the parent
	 * component (i.e. "xxx.yyy"). If you supply "xxx", then the path parent is
	 * "". However, the path parent of "" is null.
	 * 
	 * @param pkg
	 * @return
	 */
	private String pathParent(String pkg) {
		if(pkg.equals("")) {
			return null;
		}
		int idx = pkg.lastIndexOf('.');
		if(idx == -1) {
			return "";
		} else {
			return pkg.substring(0,idx);
		}
	}		
	
	/**
	 * Given a path string of the form "xxx.yyy.zzz" this returns the child
	 * component (i.e. "zzz")
	 * 
	 * @param pkg
	 * @return
	 */
	private String pathChild(String pkg) {
		int idx = pkg.lastIndexOf('.');
		if(idx == -1) {
			return pkg;
		} else {
			return pkg.substring(idx+1);
		}
	}
}
