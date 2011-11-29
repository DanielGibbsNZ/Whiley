import * from whiley.lang.*

define i8 as int where $ >=-128 && $ <= 127

[i8] f(int x) requires x == 0 || x == 256:
    return [x]

void ::main(System sys,[string] args):
    bytes = f(256)
    debug toString(bytes)

