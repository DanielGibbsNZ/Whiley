void f({int} xs, {int} ys) requires xs ⊂ ys:
    debug "XS IS A SUBSET"

void g({int} xs, {int} ys) requires xs ⊂ ys:
    f(ys,xs)

void System::main([string] args):
    g({1,2},{1,2,3})
    g({1},{1,2,3})
