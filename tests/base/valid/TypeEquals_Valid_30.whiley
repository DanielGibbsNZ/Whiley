import * from whiley.lang.*

int f({int->any} xs):
    if xs is {int->string}:
        return 1
    else:
        return -1

void ::main(System sys,[string] args):
    s1 = {0->"Hello"}
    s2 = {1->"Hello"}
    s3 = {0->"Hello",1->"Hello"}
    s4 = {0->"Hello",1->"Hello",3->"Hello"}
    sys.out.println(toString(f(s1)))
    sys.out.println(toString(f(s2)))
    sys.out.println(toString(f(s3)))
    sys.out.println(toString(f(s4)))
    t1 = {0->0,1->1}
    sys.out.println(toString(f(t1)))

