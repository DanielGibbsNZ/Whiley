import * from whiley.lang.*

int f({real} xs):
    return |xs|

void ::main(System sys,[string] args):
    ys = {{1,2},{1}}
    xs = {1,2,3,4}
    x = f(xs ∩ ys)
    sys.out.println(Any.toString(x))
