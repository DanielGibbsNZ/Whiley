import * from whiley.lang.System

define nat as int where $ >= 0
define natpair as (nat,nat)

int min(natpair p):
    x,y = p
    if x > y:
        return y
    else:
        return x

void ::main(System.Console sys):
    p = (0,-1)
    x = min(p)
