// this is a comment!
define c3num as {1,2,3,4}

void f(c3num x):
    y = x
    debug str(y)

void g(int z):
    f(z)

void System::main([string] args):
    g(5)
