import * from whiley.lang.*

define MyProc1 as process { int data }
define MyProc2 as process { any data }

void MyProc1::set(int d):
    this.data = d

any MyProc2::get():
    return this.data

MyProc2 System::create(any data):
    return spawn {data: data}

void ::main(System sys,[string] args):
    p2 = this.create(1.23)
    p2.set(1)
    sys.out.println(toString(p2.get()))

