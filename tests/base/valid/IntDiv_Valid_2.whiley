import * from whiley.lang.*

int f(int x):
    return x / 3

public void ::main(System sys,[string] args):
    sys.out.println(Any.toString(f(10)))
