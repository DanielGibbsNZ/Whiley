[int] append(string input):
    rs = []
    for i in 0..|input|:
        rs = input[i] + rs
    return rs

void System::main([string] args):
    xs = append("abcdefghijklmnopqrstuvwxyz")
    out.println(str(xs))
