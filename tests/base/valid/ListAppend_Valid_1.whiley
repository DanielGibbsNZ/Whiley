import * from whiley.lang.*

void ::main(System sys,[string] args):
    r = [1,2] + [3,4]
    sys.out.println(Any.toString(r))
