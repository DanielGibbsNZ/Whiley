import * from whiley.lang.*

void ::main(System sys,[string] args):
    list = [1,2,3]
    sublist = list[1..4]
    debug toString(list)
    debug toString(sublist)
