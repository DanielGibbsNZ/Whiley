import * from whiley.lang.*

void ::main(System sys,[string] args):
     xs = {1,2,3}
     if 1 ∈ xs:
         sys.out.println(toString(1))
    if 5 in xs:
        sys.out.println(toString(5))
  

