string f({int} xs, {int} ys) requires xs ⊆ ys:
    return "XS IS A SUBSET"

void System::main([string] args):
    out.println(f({1,2,3},{1,2,3}))
    out.println(f({1,2},{1,2,3}))
    out.println(f({1},{1,2,3}))
