import println from whiley.lang.System
import * from whiley.lang.Int

[u8] f(int x) requires x == 0 || x == 169:
    return [x]

void ::main(System.Console sys):
    bytes = f(0)
    sys.out.println(Any.toString(bytes))

