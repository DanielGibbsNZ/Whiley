import * from whiley.lang.*

define dr2point as {real x, real y}

void ::main(System.Console sys):
     p = {x:1.0,y:2.23}
     sys.out.println(Any.toString(p))

