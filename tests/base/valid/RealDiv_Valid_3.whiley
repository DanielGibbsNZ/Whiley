import * from whiley.lang.*

real g(real x):
     return x / 3

void ::main(System.Console sys):
     sys.out.println(Any.toString(g(0.234)))
