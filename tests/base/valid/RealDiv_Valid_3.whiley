import * from whiley.lang.*

real g(real x):
     return x / 3

void ::main(System sys,[string] args):
     sys.out.println(toString(g(0.234)))
