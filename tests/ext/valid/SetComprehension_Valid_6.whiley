import * from whiley.lang.*

{int} f({int} xs) requires no { x ∈ xs | x < 0 }, ensures no { y ∈ $ | y > 0 }:
    return { -x | x ∈ xs } 

void ::main(System sys,[string] args):
    sys.out.println(toString(f({1,2,3,4})))
