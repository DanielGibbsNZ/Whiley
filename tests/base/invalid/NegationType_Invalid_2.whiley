import * from whiley.lang.*

!null&!int f(int x):
    return x

void ::main(System sys, [string] args):
    sys.out.println(Any.toString(f("Hello World")))
