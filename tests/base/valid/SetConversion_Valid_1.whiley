import * from whiley.lang.*

string f({real} ls):
    return Any.toString(ls)

void ::main(System sys,[string] args):
    sys.out.println(f({1,2,3}))
