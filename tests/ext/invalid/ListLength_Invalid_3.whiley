

int f(int x) requires x+1 > 0, ensures $ < 0:
    debug Any.toString(x)
    return -1

void ::main(System.Console sys):
    f(|sys.args|-1)
