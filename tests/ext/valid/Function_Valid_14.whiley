import println from whiley.lang.*

define nat as int where $ >= 0

nat abs(int item):
    return Math.abs(item)

nat nop(nat item) ensures item == $:
    // requires proper postcondition in Math.abs()
    return Math.abs(item)


void ::main(System.Console sys):
    xs = abs(-123)
    sys.out.println(Any.toString(xs))
    xs = nop(1)
    sys.out.println(Any.toString(xs))
