real g(int x):
     return x / 3

string f(int x, int y):
    return str(g(x))

void System::main([string] args):
     out.println(f(1,2))

