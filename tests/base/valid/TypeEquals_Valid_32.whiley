import * from whiley.lang.*
import * from whiley.io.File
import SyntaxError from whiley.lang.Errors

// ====================================================
// A simple calculator for expressions
// ====================================================

define ADD as 0
define SUB as 1
define MUL as 2
define DIV as 3

// binary operation
define BOp as { ADD, SUB, MUL, DIV }
define BinOp as { BOp op, Expr lhs, Expr rhs } 

// variables
define Var as { string id }

// list access
define ListAccess as { 
    Expr src, 
    Expr index
} 

// expression tree
define Expr as int |  // constant
    BinOp |            // binary operator
    [Expr] |           // list constructor
    ListAccess         // list access

// values
define Value as int | [Value]

// ====================================================
// Expression Evaluator
// ====================================================

Value evaluate(Expr e):
    if e is int:
        return e
    else if e is BinOp:
        return evaluate(e.lhs)
    else if e is [Expr]:
        return []
    else:
        src = evaluate(e.src)
        index = evaluate(e.index)
        // santity checks
        if src is [Value] && index is int && index >= 0 && index < |src|:
            return src[index]
        else:
            return 0 // dumb

void ::main(System sys, [string] args):
    e = { op: ADD, lhs: 123, rhs: 1}
    v = evaluate(e)
    sys.out.println("RESULT: " + v)
    e = [1]
    v = evaluate(e)
    sys.out.println("RESULT: " + v)