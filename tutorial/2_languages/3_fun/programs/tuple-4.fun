// testing two tuple arguments to a function

let f Pair(a,b) Pair(x,y) = a Pair(x,y) + b Pair(x,y)
in f Pair(fun Pair(x,y) -> x * y, fun Pair(x,y) -> x + y) Pair(10,20)
