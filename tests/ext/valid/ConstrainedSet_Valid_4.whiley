import * from whiley.lang.*

define pintset as {int} where |$| > 1

void ::main(System.Console sys):
    p = {1,2}
    sys.out.println(Any.toString(p))
