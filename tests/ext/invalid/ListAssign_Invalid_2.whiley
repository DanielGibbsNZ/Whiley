import * from whiley.lang.*

void ::main(System sys,[string] args):
    arr1 = [1,2,4]
    arr2 = arr1
    arr2[2] = 3
    assert arr2[2] != |arr1|
    debug toString(arr1)
    debug toString(arr2)
