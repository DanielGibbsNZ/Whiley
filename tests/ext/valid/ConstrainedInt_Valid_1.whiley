// this is a comment!
define cr1nat as int where $ < 10

string f(cr1nat x):
    y = x
    return str(y)

void System::main([string] args):
    out.println(f(9))
