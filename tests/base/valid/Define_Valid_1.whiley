import * from whiley.lang.*

define point as {int x,int y}
define listint as [int]
define setint as {int}

void ::main(System sys,[string] args):
     si = {1,2,3}
     li = [1,2,3]     
     p = {x:1,y:2}
     x = p.x     
     sys.out.println(toString(x))
     sys.out.println(toString(|si|))
     sys.out.println(toString(li[0]))
