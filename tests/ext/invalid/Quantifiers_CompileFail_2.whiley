void f({int} ls) requires no { i in ls | i <= 0}:
    print str(ls)

void System::main([string] args):
    f({0,1,2,3})
