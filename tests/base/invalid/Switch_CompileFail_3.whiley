int f(int x):
    switch x:
        default:
            return 0
        default:
            return 1
    return 10

void System::main([string] args):
    out.println(str(f(1)))
    out.println(str(f(2)))
    out.println(str(f(3)))
    out.println(str(f(-1)))
