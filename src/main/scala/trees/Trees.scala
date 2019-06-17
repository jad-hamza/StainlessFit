package trees


sealed abstract class Tree

case class Var(id: Int, name: String) extends Tree

case class Bind(x: Var, body: Tree) extends Tree

case class NatLiteral(n: BigInt) extends Tree /*{
  require(n >= 0)
}*/

case class BoolLiteral(b: Boolean) extends Tree

case class IfThenElse(cond: Tree, t1: Tree, t2: Tree) extends Tree

case class Lambda(tp: Option[Tree], body: Bind) extends Tree

case class App(t1: Tree, t2: Tree) extends Tree

case class Tuple(s: Seq[Tree]) extends Tree

case class TupleSelect(t: Tree, i: Int) extends Tree

case class Fix(body: Bind) extends Tree

case class Match(t: Tree, t1: Tree, t2: Tree) extends Tree

case class EitherMatch(t: Tree, t1: Bind, t2: Bind) extends Tree

case class Left(t: Tree) extends Tree

case class Right(t: Tree) extends Tree

case class Because(t1: Tree, t2: Tree) extends Tree

case class S(n: Tree) extends Tree

case class P(n: Tree) extends Tree

case class NatEq(n: Tree, m: Tree) extends Tree

case class Add(n: Tree, m: Tree) extends Tree

case class Mul(n: Tree, m: Tree) extends Tree

case class Bottom extends Tree

case object BoolType extends Tree

case object NatType extends Tree

case class TupleType(tp: Seq[Bind]) extends Tree

case class ArrowType(t1: Tree, t2: Bind) extends Tree

case class SumType(t1: Tree, t2: Tree) extends Tree

case class Refinement(t: Tree, cond: Bind) extends Tree

case class RefinementByType(t: Tree, cond: Bind) extends Tree
