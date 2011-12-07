import * from whiley.lang.*

// this is a comment!
define IntList as {int|[int] op}

void ::main(System sys,[string] args):
    x = {op:1}
    x.op = 1
    y = x // OK
    sys.out.println(Any.toString(y))
    x = {op:[1,2,3]} // OK
    sys.out.println(Any.toString(x))
