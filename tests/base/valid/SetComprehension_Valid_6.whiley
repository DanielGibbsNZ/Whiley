import * from whiley.lang.*

{int} f({int} xs):
    return { -x | x ∈ xs } 

void ::main(System sys,[string] args):
    sys.out.println(toString(f({1,2,3,4})))
