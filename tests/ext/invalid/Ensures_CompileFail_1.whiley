

int f() ensures 2*$==1:
    return 1

void ::main(System.Console sys):
    debug Any.toString(f())
