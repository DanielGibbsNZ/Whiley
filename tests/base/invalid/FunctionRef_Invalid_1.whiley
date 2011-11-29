import * from whiley.lang.*

int f(int x):
    return x + 1

int g(void(int) func):
    return func(1)
    
void ::main(System sys,[string] args):
    sys.out.println(toString(g(&f)))