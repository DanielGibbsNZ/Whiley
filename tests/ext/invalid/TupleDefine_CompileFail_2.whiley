

define point as {int x, int y} where $.x > 0 && $.y > 0

point f(point p):
    return p

void ::main(System.Console sys):
    p = {x:-1,y:1}
    p = f(p)
    debug Any.toString(p)
