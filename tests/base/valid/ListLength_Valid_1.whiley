import * from whiley.lang.*

void ::main(System.Console sys):
    arr = [1,2,3]
    assert |arr| == 3
    sys.out.println(Any.toString(arr[0]))
