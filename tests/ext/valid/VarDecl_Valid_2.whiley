import * from whiley.lang.*

string g(int z) requires z > 1:
    return Any.toString(z)

string f(int x) requires x > 0:
    y = x + 1
    return g(y)

void ::main(System sys,[string] args):
    sys.out.println(f(1))
