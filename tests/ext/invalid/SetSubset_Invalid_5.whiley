

void f({int} xs, {int} ys) requires xs ⊂ ys:
    debug "XS IS A SUBSET"

void g({int} xs, {int} ys):
    f(xs,ys)

void ::main(System.Console sys):
    g({1},{1,2,3})
    g({1,4},{1,2,3})
