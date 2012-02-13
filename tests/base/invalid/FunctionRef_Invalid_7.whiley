import * from whiley.lang.*

define Proc as ref { real(real) func }

int Proc::func(int x):
    return x + 1

real Proc::test(real arg):
    return this.func(arg)

real id(real x):
    return x
    
void ::main(System.Console sys):
    p = new { func: &id }
    x = p.test(123)
    sys.out.println("GOT: " + Any.toString(x))
    x = p.test(12545)
    sys.out.println("GOT: " + Any.toString(x))
    x = p.test(-11)
    sys.out.println("GOT: " + Any.toString(x))
