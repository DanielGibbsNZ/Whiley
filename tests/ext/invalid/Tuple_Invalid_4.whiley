import * from whiley.lang.System

define nat as int where $ >= 0
define natpair as (nat,int)

int min(natpair p):
    x,y = p
    if x > y:
        return y
    else:
        return x

void ::main(System.Console sys):
    p = (-1,0)
    x = min(p)
