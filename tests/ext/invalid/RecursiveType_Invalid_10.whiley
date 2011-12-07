import * from whiley.lang.*

define LinkedList as int | {LinkedList next, int data}

define posLink as {posList next, nat data}
define posList as int | posLink

nat sum(LinkedList list):
    if list is int:
        return 0
    else:
        return list.data + sum(list.next)

void ::main(System sys,[string] args):
    l = { next:1, data:1 }
    debug Any.toString(sum(l))
    l = { next:l, data:-2 }
    debug Any.toString(sum(l))    
