import * from whiley.lang.*

define LinkedList as null|{LinkedList next, int data}

!(null|int) f(LinkedList x):
    return x

void ::main(System.Console sys):
    sys.out.println(Any.toString(f("Hello World")))
