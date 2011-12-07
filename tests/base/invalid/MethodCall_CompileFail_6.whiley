import * from whiley.lang.*

define wmccf6tup as {int x, int y}

wmccf6tup f(System x, int y):
    return {x:1, y:x.get()}

int System::get():
    return 1

void ::main(System sys,[string] args):
    sys.out.println(Any.toString(f(this),1))
