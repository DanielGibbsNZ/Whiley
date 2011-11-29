import * from whiley.lang.*

[int] sort([int] items):
    if |items| > 1:
        pivot = |items| / 2
        lhs = sort(items[..pivot])
        rhs = sort(items[pivot..])
        l,r,i = (0,0,0)
        while i < |items| && l < |lhs| && r < |rhs|:
            if lhs[l] <= rhs[r]:
                items[i] = lhs[l] 
                l=l+1
            else:
                items[i] = rhs[r] 
                r=r+1
            i=i+1
        while l < |lhs|:
            items[i] = lhs[l]
            i=i+1 
            l=l+1
        while r < |rhs|:
            items[i] = rhs[r] 
            i=i+1 
            r=r+1
    return items

void ::main(System sys, [string] args):
    sys.out.println(sort([]))
    sys.out.println(sort([4,3,5,2,1]))
    sys.out.println(sort([3,4,7,1,2]))
    sys.out.println(sort([3,4,7,2]))
    sys.out.println(sort([2,3,4,2]))
    sys.out.println(sort([1,2,3,4]))
    sys.out.println(sort([1,2,3,4,5]))
