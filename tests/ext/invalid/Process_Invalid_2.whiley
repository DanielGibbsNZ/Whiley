

define state as {int x, int y} where x < y
define pState as process state

int pState::send(int x):
    debug Any.toString(x)
    return -1

void ::main(System.Console sys):
    x = (spawn {x:2,y:2}).send(1)
    debug Any.toString(x)
