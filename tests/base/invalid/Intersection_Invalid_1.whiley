import * from whiley.lang.*

define EmptyList as [int] & [real]

int size(EmptyList l):
    return |l|

void ::main(System.Console sys):
    list = [1]
    sys.out.println(Any.toString(size(list)))
