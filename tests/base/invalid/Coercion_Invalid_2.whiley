import * from whiley.lang.*

define List1 as { int|null data, null|List1 next }
define List2 as { int data, null|List2 next }
define List3 as { null data, null|List3 next }
define List4 as List3|List2

List4 f(List1 r):
    return r

void ::main(System sys,[string] args):
    list = { data: 1, next: null}
    list = { data: null, next: list}
    ans = f(list)
    sys.out.println(toString(ans))