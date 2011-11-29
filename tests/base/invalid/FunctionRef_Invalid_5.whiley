import * from whiley.lang.*

define Proc as process { int data }

int Proc::read(int x):
    return x + 1

define Func as {
    int(int) reader
}

int id(int x):
    return x

int test(Func f, int arg):
    return f.read(arg)
    
void ::main(System sys,[string] args):
    x = test({read: &id},123)
    sys.out.println("GOT: " + toString(x))
    x = test({read: &id},12545)
    sys.out.println("GOT: " + toString(x))
    x = test({read: &id},-11)
    sys.out.println("GOT: " + toString(x))
