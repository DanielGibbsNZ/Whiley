void f({int} xs) requires xs ⊆ {1,2,3}:
    debug str(xs)

void g({int} ys):
    f(ys ∩ {1,2,3,4})

void System::main([string] args):
    g({1,2,3,4})
    g({2})
    g({})
