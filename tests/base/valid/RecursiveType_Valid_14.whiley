import * from whiley.lang.*

define Expr as real | { Expr lhs, int data } | [Expr]
define SubExpr as real | { SubExpr lhs, int data }

string Any.toString(Expr e):
    if e is SubExpr:
        if e is real:
            return Any.toString(e)
        else:
            return Any.toString(e.data) + "->" + toString(e.lhs)
    else:
        return Any.toString(-1)

void ::main(System sys,[string] args):
    se1 = 0.1234
    se2 = {lhs: se1, data: 1}
    se3 = {lhs: se2, data: 45}
    e1 = [se1]
    e2 = [e1,se1,se2]
    sys.out.println(Any.toString(se1))
    sys.out.println(Any.toString(se2))
    sys.out.println(Any.toString(se3))
    sys.out.println(Any.toString(e1))
    sys.out.println(Any.toString(e2))
