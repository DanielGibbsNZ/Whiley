void f([int] x, int i) requires |x| > 0:
    if i < 0 || i >= |x|:
        i = 1
    y = x[i]
    z = x[i]
    assert y == z
    debug str(y)
    debug str(z)

void System::main([string] args):
    arr = [1,2,3]
    f(arr, 1)
    debug str(arr)    
    f(arr, 2)
    debug str(arr)
    arr = [123]
    f(arr, 3)
    debug str(arr)
    arr = [123,22,2]
    f(arr, -1)
    debug str(arr)
    f(arr, 4)
