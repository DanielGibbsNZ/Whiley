import * from whiley.lang.*

[int] f(System x, int x):
    return [1,2,3,x.get()]

int System::get():
    return 1

void ::main(System sys,[string] args):
    sys.out.println(toString(f(this),1))
