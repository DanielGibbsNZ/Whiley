

// this is a comment!
define c3num as {1,2,3,4}

void f(c3num x):
    y = x
    debug Any.toString(y)

void g(int z):
    f(z)

void ::main(System.Console sys):
    g(5)
