import * from whiley.lang.*

int f(int x) requires x>=0, ensures $>=0 && x>=0:
    return x

void ::main(System sys,[string] args):
    sys.out.println(toString(f(10)))
