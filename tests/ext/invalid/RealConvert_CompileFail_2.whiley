

real f(real x, int y) requires x>=y:
    return 0.0

void ::main(System.Console sys):
    x = f(1.0,1)
    f(x,1)
