import * from whiley.lang.*

void ::main(System sys,[string] args):
     xs = {1,2,3,4}
     xs = xs ∪ {5,1}
     sys.out.println(toString(xs))
