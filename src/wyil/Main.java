package wyil;

import java.io.*;
import java.util.Arrays;

import wyil.io.WyilFilePrinter;
import wyil.io.WyilFileReader;
import wyil.lang.WyilFile;
import wyjvm.io.BinaryInputStream;
import wyjvm.io.BinaryOutputStream;

public class Main {
	public static void main(String[] args) {		
		try {
			WyilFile wf = new WyilFileReader(args[0]).read();
			new WyilFilePrinter(System.out).apply(wf);
		} catch(IOException e) {
			System.err.println("I/O error - " + e.getMessage());
		}
	}
}