import * from whiley.lang.*

define i8 as int where $ >=-128 && $ <= 127

int g(int x) ensures $ > 0 && $ <= 256:
    if(x <= 0):
        return 1
    else:
        return x

{i8} f(int x):
    return {g(x)}

void ::main(System sys,[string] args):
    bytes = f(256)
    debug toString(bytes)

