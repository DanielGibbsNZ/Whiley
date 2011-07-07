// expression tree
define Expr as int | real |  // constant
    [Expr] |           // list constructor
    ListAccess         // list access

// list access
define ListAccess as { 
    Expr src, 
    Expr index
} 

define Value as int | real | [Value] | null

Value evaluate(Expr e):
    if e is real || e is int:
        return e
    else if e is [Expr]:
        r = []
        for i in e:
            r = r + [evaluate(i)]
        return r
    else:
        src = evaluate(e.src)
        index = evaluate(e.index)
        // santity checks
        if src is [Expr] && index is int && index >= 0 && index < |src|:
            return src[index]
        else:
            return null // stuck

public void System::main([string] args):
    out.println(str(evaluate(1)))
    out.println(str(evaluate({src: [112,212,342], index:0})))
    out.println(str(evaluate({src: [112312,289712,31231242], index:2})))
    out.println(str(evaluate([1,2,3])))
