define tac1tup as { int f1, int f2 } where f1 < f2

void System::main([string] args):
    x = { f1:1, f2:3 }
    x.f1 = 2
    assert x.f1 == x.f2
    debug str(x)
