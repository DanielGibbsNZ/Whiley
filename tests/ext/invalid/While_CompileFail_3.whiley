import * from whiley.lang.*

void ::main(System sys,[string] args):
    i=0
    while i < |args|:
        r = r + |args[i]|
    debug toString(r)
