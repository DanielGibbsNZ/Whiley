import * from whiley.lang.*

int f(int x, int y) requires y != 0:
    return x / y

void ::main(System.Console sys):
     x = f(10,2)
     sys.out.println(Any.toString(x)  )
