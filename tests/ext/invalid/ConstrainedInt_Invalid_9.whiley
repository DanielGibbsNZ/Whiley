

// this is a comment!
define irf2nat as int where $ > 0

void f(irf2nat x):
    debug Any.toString(x)

void g(int x):
    f(x)

void ::main(System.Console sys):
    g(-1)
