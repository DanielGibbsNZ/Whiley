import * from whiley.lang.*

define pos as int
define neg as int

define intlist as pos|neg|[int]

int f(intlist x):
    if x is int:
        return x
    return 1 

void ::main(System sys,[string] args):
    x = f([1,2,3])
    sys.out.println(Any.toString(x))
    x = f(123)
    sys.out.println(Any.toString(x))

