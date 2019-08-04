package interpreter

import trees._


import stainless.annotation._
import stainless.collection._
import stainless.lang._


object Interpreter {

  def smallStep(e: Tree): Tree = {
    e match {
      case IfThenElse(BoolLiteral(true), t1, t2) => t1
      case IfThenElse(BoolLiteral(false), t1, t2) => t2
      case IfThenElse(ErrorTree(s, t), _, _) => ErrorTree(s, t)
      case IfThenElse(t, _, _) if Tree.isValue(t) => ErrorTree("Waiting bool in if", Some(BoolType))
      case IfThenElse(t, tt, tf) => IfThenElse(smallStep(t), tt, tf)

      case Pair(ErrorTree(s, t), _) => ErrorTree(s, t)
      case Pair(_, ErrorTree(s, t)) => ErrorTree(s, t)
      case Pair(t1, t2) if Tree.isValue(t1) => Pair(t1, smallStep(t2))
      case Pair(t1, t2) => Pair(smallStep(t1), t2)

      case First(ErrorTree(s, t)) => ErrorTree(s, t)
      case First(Pair(t1, t2)) => t1
      case First(t) if Tree.isValue(t) => ErrorTree("First wait a Pair", None())
      case First(t) => First(smallStep(t))

      case Second(ErrorTree(s, t)) => ErrorTree(s, t)
      case Second(Pair(t1, t2)) => t2
      case Second(t) if Tree.isValue(t) => ErrorTree("Second wait a Pair", None())
      case Second(t) => Second(smallStep(t))

      case App(ErrorTree(s, t), _) => ErrorTree(s, t)
      case App(_, ErrorTree(s, t)) => ErrorTree(s, t)
      case App(Lambda(_, bind), v) if Tree.isValue(v) && Tree.isBind(bind) => Tree.replaceBind(bind, v)
      case App(Lambda(tp, bind: Bind), t) => App(Lambda(tp, bind), smallStep(t))
      case App(f, _) if Tree.isValue(f) => ErrorTree("App wait a lambda abstraction", None()) // f is a value and not a lambda
      case App(f, v) => App(smallStep(f), v)
      case Fix(_, Bind(id, bind)) if Tree.isBind(bind) => Tree.replaceBind(bind, Lambda(None(), Bind(Identifier(0, "_"), e)))

      case Match(ErrorTree(s, t), _, _) => ErrorTree(s, t)
      case Match(NatLiteral(BigInt(0)), t0, _) => t0
      case Match(NatLiteral(n), _, bind) if Tree.isBind(bind) => Tree.replaceBind(bind, NatLiteral(n - 1))
      case Match(t, _, _) if Tree.isValue(t) => ErrorTree("Match wait a nat", Some(NatType))
      case Match(t, t0, bind) => Match(smallStep(t), t0, bind)
      //case Match(t, t0, notBind) => ErrorTree("Match wait a bind", None())

      case EitherMatch(ErrorTree(s, t), _, _) => ErrorTree(s, t)
      case EitherMatch(LeftTree(v), bind, _) if v.isValue && Tree.isBind(bind) => Tree.replaceBind(bind, v)
      case EitherMatch(RightTree(v), _, bind) if v.isValue && Tree.isBind(bind) => Tree.replaceBind(bind, v)
      case EitherMatch(t, _, _) if Tree.isValue(t) => ErrorTree("EitherMatch Wait a Left or a Right with binds.", None())
      case EitherMatch(t, b1, b2) => EitherMatch(smallStep(t), b1, b2)

      case LetIn(tp, ErrorTree(s, t), bind) => ErrorTree(s, t)
      case LetIn(tp, v, bind) if Tree.isValue(v) && Tree.isBind(bind) => Tree.replaceBind(bind, v)
      case LetIn(tp, t, bind) if Tree.isValue(t) => ErrorTree("LetIn should have a bind", None())
      case LetIn(tp, t, bind) => LetIn(tp, smallStep(t), bind)

      case Primitive(Not, Cons(BoolLiteral(b), Nil())) => BoolLiteral(!b)
      case Primitive(And, Cons(BoolLiteral(b1), Cons(BoolLiteral(b2), Nil()))) => BoolLiteral(b1 && b2)
      case Primitive(Or, Cons(BoolLiteral(b1), Cons(BoolLiteral(b2), Nil()))) => BoolLiteral(b1 || b2)

      case Primitive(Neq, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) => BoolLiteral(n1 != n2)
      case Primitive(Eq, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) => BoolLiteral(n1 == n2)
      case Primitive(Lt, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) => BoolLiteral(n1 < n2)
      case Primitive(Gt, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) => BoolLiteral(n1 > n2)
      case Primitive(Lteq, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) => BoolLiteral(n1 <= n2)
      case Primitive(Gteq, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) => BoolLiteral(n1 >= n2)

      case Primitive(Plus, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) => NatLiteral(n1 + n2)
      case Primitive(Minus, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) => NatLiteral(n1 - n2)
      case Primitive(Mul, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) => NatLiteral(n1 * n2)
      case Primitive(Div, Cons(NatLiteral(n1), Cons(NatLiteral(n2), Nil()))) if n2 != 0 => NatLiteral(n1 / n2)

      case Primitive(p, Cons(x, Nil())) if !Tree.isValue(x) => Primitive(p, Cons(smallStep(x), Nil()))
      case Primitive(p, Cons(x, Cons(y, Nil()))) if !Tree.isValue(x) => Primitive(p, Cons(smallStep(x), Cons(y, Nil())))
      case Primitive(p, Cons(x, Cons(y, Nil()))) if !Tree.isValue(y) => Primitive(p, Cons(x, Cons(smallStep(y), Nil())))
      case Primitive(_, _) => ErrorTree("Bad Primitive operations", None())

      case LeftTree(e) => LeftTree(smallStep(e))
      case RightTree(e) => RightTree(smallStep(e))
      case Inst(t1, t2) => t1

      case Unfold(ErrorTree(s, t), _) => ErrorTree(s, t)
      case Unfold(Fold(tp, v), bind) if v.isValue && bind.isBind => Tree.replaceBind(bind, v)
      case Unfold(Fold(tp, v), bind) if v.isValue => ErrorTree("Unfold should have a bind", None())
      case Unfold(Fold(tp, t), bind) => Unfold(Fold(tp, smallStep(t)), bind)

      case _ => e
    }
  }

  def evaluate(e: Tree, fuel: BigInt): Tree = {
    require(fuel >= 0)
    decreases(fuel)
    if(Tree.isValue(e) || Tree.isError(e)) e
    else if(fuel == 0) ErrorTree("No more fuel", Some(BottomType))
    else evaluate(smallStep(e), fuel - 1)
  }
}
