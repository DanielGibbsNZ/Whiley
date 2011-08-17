define pos as int where $ > 0
define neg as int where $ < 0
define expr as pos|neg

string f(expr e):
    if e is pos:
        return "POSITIVE: " + str(e)
    else:
        return "NEGATIVE: " + str(e)

void System::main([string] args):
    out.println(f(-1))
    out.println(f(1))
    out.println(f(1234))
 
