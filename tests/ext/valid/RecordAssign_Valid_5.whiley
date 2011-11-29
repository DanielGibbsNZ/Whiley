import * from whiley.lang.*

define bytes as { int8 b1, int8 b2 }

bytes f(int a) requires a > 0 && a < 10:
    bs = {b1:a,b2:a+1}
    return bs

void ::main(System sys,[string] args):
    sys.out.println(toString(f(1)))
    sys.out.println(toString(f(2)))
    sys.out.println(toString(f(9)))
