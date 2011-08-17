#!/bin/sh

# GENERATE DEFINITE INVALID FILES

FILES=$(ls *.whiley)

echo "// This file is part of the Whiley-to-Java Compiler (wyjc)."
echo "//"
echo "// The Whiley-to-Java Compiler is free software; you can redistribute"
echo "// it and/or modify it under the terms of the GNU General Public"
echo "// License as published by the Free Software Foundation; either"
echo "// version 3 of the License, or (at your option) any later version."
echo "//"
echo "// The Whiley-to-Java Compiler is distributed in the hope that it"
echo "// will be useful, but WITHOUT ANY WARRANTY; without even the"
echo "// implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR"
echo "// PURPOSE.  See the GNU General Public License for more details."
echo "//"
echo "// You should have received a copy of the GNU General Public"
echo "// License along with the Whiley-to-Java Compiler. If not, see"
echo "// <http://www.gnu.org/licenses/>"
echo "//"
echo "// Copyright 2010, David James Pearce."
echo ""
echo "package wyjc.testing.tests;"
echo ""
echo "import org.junit.*;"
echo "import wyjc.testing.TestHarness;"
echo ""
echo "public class ExtendedValidTests extends TestHarness {"
echo " public ExtendedValidTests() {"
echo "  super(\"tests/ext/valid\",\"tests/ext/valid\",\"sysout\");"
echo " }"
echo ""

for f in $FILES 
do
    nf=$(echo $f | sed -e "s/.whiley//")
    echo -n " @Test public void $nf"
    echo "_RuntimeTest() { runTest(\"$nf\"); }"
done

echo "}"
 
