import * from whiley.lang.*

string f({int} xs):
    if |xs| > 0:
        return toString(xs)
    else:
        return "FAILED"

string g({int} ys):
    return f(ys ∪ {1})

void ::main(System sys,[string] args):
    sys.out.println(g({}))
    sys.out.println(g({2}))
