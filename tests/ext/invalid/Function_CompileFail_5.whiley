int f(int x) requires x >= 0:
    return x

int f(int x) requires x >= 0:
    return x

void System::main([string] args):    
    debug str(f(1))
    
    
