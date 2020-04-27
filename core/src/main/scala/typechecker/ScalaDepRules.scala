package stainlessfit
package core
package typechecker

import core.trees._

import util.Utils._
import util.RunContext
import parser.FitParser

import Printer.asString

import Derivation._
import TypeOperators._
import ScalaDepSugar._
import interpreter.Interpreter

trait ScalaDepRules {
  // TODO: Add freshen whenever we bind
  implicit val rc: RunContext

  def withExistsIfFree(id: Identifier, tpe: Tree, t: Tree): Tree =
    if (id.isFreeIn(t)) ExistsType(tpe, Bind(id, t)) else t

  val InferNat1 = Rule("InferNat1", {
    case g @ InferGoal(c, e @ NatLiteral(n)) =>
      TypeChecker.debugs(g, "InferNat1")
      Some((List(), _ =>
        (true, InferJudgment("InferNat1", c, e, SingletonType(NatType, e)))))
    case g =>
      None
  })

  val InferLet1 = Rule("InferLet1", {
    case g @ InferGoal(c, e @ LetIn(None, v, Bind(id, body))) =>
      TypeChecker.debugs(g, "InferLet1")
      val c0 = c.incrementLevel
      val gv = InferGoal(c0, v)
      val fgb: List[Judgment] => Goal =
        {
          case InferJudgment(_, _, _, tyv) :: Nil =>
            val (c1, bodyF) = c0.bindAndFreshen(id, tyv, body)
            InferGoal(c1, bodyF)
          case _ =>
            ErrorGoal(c0, None)
        }
      Some((
        List(_ => gv, fgb),
        {
          case InferJudgment(_, _, _, tyv) :: InferJudgment(_, _, _, tyb) :: Nil =>
            val ty = withExistsIfFree(id, tyv, tyb)
            (true, InferJudgment("InferLet1", c, e, ty))
          case _ =>
            emitErrorWithJudgment("InferLet1", g, None)
        }
      ))

    case _ => None
  })

  val InferLet2 = Rule("InferLet2", {
    case g @ InferGoal(c, e @ LetIn(Some(tyv), v, Bind(id, body))) =>
      TypeChecker.debugs(g, "InferLet2")
      val c0 = c.incrementLevel
      val gv = CheckGoal(c0, v, tyv)

      val (c1, bodyF) = c0.bindAndFreshen(id, SingletonType(tyv, v), body)
      val g2: Goal = InferGoal(c1, bodyF)

      Some((
        List(_ => gv, _ => g2),
        {
          case _ :: InferJudgment(_, _, _, tyb) :: _ =>
            val ty = withExistsIfFree(id, tyv, tyb)
            (true, InferJudgment("InferLet2", c, e, ty))
          case _ =>
            emitErrorWithJudgment("InferLet2", g, None)
        }
      ))

    case _ => None
  })

  val InferLambda1 = Rule("InferLambda1", {
    case g @ InferGoal(c, e @ Lambda(Some(ty1), Bind(id, body))) =>
      TypeChecker.debugs(g, "InferLambda1")
      val (c1, bodyF) = c.incrementLevel.bindAndFreshen(id, ty1, body)
      val gb = InferGoal(c1, bodyF)
      Some((
        List(_ => gb),
        {
          case InferJudgment(_, _, _, tyb) :: _ =>
            (true, InferJudgment("InferLambda1", c, e,
              SingletonType(PiType(ty1, Bind(id, tyb)), e)))
          case _ =>
            // Returning Top is sound but a bit misleading
            // (true, InferJudgment(c, e, TopType))
            emitErrorWithJudgment("InferLambda1", g, None)
        }
      ))

    case g =>
      None
  })

  def widen(t: Tree): Tree = t match {
    case SingletonType(PiType(ty1, Bind(id, ty2)), f) =>
      PiType(ty1, Bind(id, SingletonType(ty2, App(f, Var(id)))))
    case SingletonType(ty, f) =>
      widen(ty)
    case _ =>
      t
  }

  val InferApp1 = Rule("InferApp1", {
    case g @ InferGoal(c, e @ App(t1, t2)) =>
      TypeChecker.debugs(g, "InferApp1")
      val c0 = c.incrementLevel
      val g1 = InferGoal(c0, t1)
      val fg2: List[Judgment] => Goal = {
        case InferJudgment(_, _, _, ty) :: _ =>
          widen(ty) match {
            case PiType(ty2, Bind(_, _)) => CheckGoal(c0, t2, ty2)
            case wty => ErrorGoal(c0,
              Some(s"Expected a Pi-type for ${asString(t1)}, found ${asString(ty)} instead (widened as ${asString(wty)}")
            )
          }
        case _ =>
          ErrorGoal(c0, None)
      }
      Some((
        List(_ => g1, fg2), {
          case  InferJudgment(_, _, _, ty) ::
                CheckJudgment(_, _, _, _) :: _ =>
            val PiType(_, Bind(x, tyb)) = widen(ty)
            (true, InferJudgment("InferApp1", c, e, tyb.replace(x, t2)))

          case _ =>
            emitErrorWithJudgment("InferApp1", g, None)
        }
      ))

    case _ => None
  })

  val InferVar1 = Rule("InferVar1", {
    case g @ InferGoal(c, Var(id)) =>
      TypeChecker.debugs(g, "InferVar1")
      Some((List(), _ =>
        c.getTypeOf(id) match {
          case None => emitErrorWithJudgment("InferVar1", g, Some(s"${asString(id)} is not in context"))
          case Some(ty) => (true, InferJudgment("InferVar1", c, Var(id), SingletonType(ty, Var(id))))
        }
      ))

    case g =>
      None
  })

  val InferPair1 = Rule("InferPair1", {
    case g @ InferGoal(c, e @ Pair(t1, t2)) =>
      TypeChecker.debugs(g, "InferPair1")
      val inferFirst = InferGoal(c.incrementLevel, t1)
      val inferSecond = InferGoal(c.incrementLevel, t2)
      Some((List(_ => inferFirst, _ => inferSecond),
        {
          case InferJudgment(_, _, _, ty1) :: InferJudgment(_, _, _, ty2) :: Nil =>
            val inferredType = SigmaType(ty1, Bind(Identifier.fresh("X"), ty2))
            (true, InferJudgment("InferPair1", c, e, SingletonType(inferredType, e)))
          case _ =>
            emitErrorWithJudgment("InferPair1", g, None)
        }
      ))
    case g =>
      None
  })

  val InferNil = Rule("InferNil", {
    case g @ InferGoal(c, e) if e == LNil() =>
      TypeChecker.debugs(g, "InferNil")
      Some((List(), _ => (true, InferJudgment("InferNil", c, e, LNilType))))

    case g =>
      None
  })

  val InferCons = Rule("InferCons", {
    case g @ InferGoal(c, e @ LCons(tHead, tTail)) =>
      TypeChecker.debugs(g, "InferCons")
      val c0 = c.incrementLevel
      val g1 = InferGoal(c0, tHead)
      val g2 = InferGoal(c0, tTail)
      val g3: List[Judgment] => Goal = {
        case _ :: InferJudgment(_, _, _, tyTail) :: Nil =>
          NormalizedSubtypeGoal(c0, tyTail, LList)
        case _ =>
          ErrorGoal(c0, None)
      }
      Some((List(_ => g1, _ => g2, g3), {
        case InferJudgment(_, _, _, tyHead) :: InferJudgment(_, _, _, tyTail) :: SubtypeJudgment(_, _, _, _) :: Nil =>
          (true, InferJudgment("InferCons", c, e, SingletonType(LConsType(tyHead, tyTail), e)))
        case _ =>
          emitErrorWithJudgment("InferCons", g, None)
      }))

    case g =>
      None
  })

  val InferChoose = Rule("InferChoose", {
    case g @ InferGoal(c, e @ ChooseWithPath(ty, tPath)) =>
      TypeChecker.debugs(g, "InferChoose")
      Some((List(), _ => (true, InferJudgment("InferChoose", c, e, SingletonType(ty, e)))))

    case g =>
      None
  })

  val CheckInfer = Rule("CheckInfer", {
    case g @ CheckGoal(c, t, ty) =>
      TypeChecker.debugs(g, "CheckInfer")
      val c0 = c.incrementLevel
      val gInfer = InferGoal(c0, t)
      val fgsub: List[Judgment] => Goal = {
        case InferJudgment(_, _, _, ty2) :: _ =>
          NormalizedSubtypeGoal(c0, ty2, ty)
        case _ =>
          ErrorGoal(c0, None)
      }
      Some((List(_ => gInfer, fgsub),
        {
          case InferJudgment(_, _, _, ty2) :: SubtypeJudgment(_, _, _, _) :: _ =>
            (true, CheckJudgment("CheckInfer", c, t, ty))
          case _ =>
            emitErrorWithJudgment("CheckInfer", g, None)
        }
      ))
    case g =>
      None
  })

  val ContextSanity = {
    val MaxLevel = 40

    def error(g: Goal, msg: String) =
      Some((List(), (_: List[Judgment]) =>
        emitErrorWithJudgment("ContextSanity", g, Some(msg))))

    def hasBadBinding(c: Context, e: Tree)(implicit rc: RunContext): Boolean = {
      var sane = true
      e.replaceMany {
        case Bind(id, _) if c.termVariables.contains(id) =>
          sane = false
          None
        case _ => None
      }
      !sane
    }
    def checkBindings(g: Goal, t: Tree) =
      if (hasBadBinding(g.c, t)) error(g, "Has a bad binding") else None

    def checkDepth(g: Goal) =
      if (g.c.level > MaxLevel) error(g, s"Exceeds the maximum level ($MaxLevel)") else None

    Rule("ContextSanity", {
      case g @ InferGoal(c, t) =>
        checkDepth(g).orElse(checkBindings(g, t))
      case g @ NormalizationGoal(c, ty, _, _) =>
        checkDepth(g).orElse(checkBindings(g, ty))
      case g =>
        checkDepth(g)
    })
  }

  val NormSingleton = Rule("NormSingleton", {
    case g @ NormalizationGoal(c, ty @ SingletonType(tyUnderlying, t), linearExistsVars, inPositive) =>
      TypeChecker.debugs(g, "NormSingleton")
      val c0 = c.incrementLevel
      val v = Interpreter.evaluateWithContext(c, t)

      // Re-type if we performed any delta reductions during evaluation:
      // TODO: Compute this more efficiently (e.g. output from evaluateWithContext)
      val shouldRetype = c.termVariables.exists { case (id, SingletonType(_, _)) => id.isFreeIn(t); case _ => false }
      if (shouldRetype) {
        val g1 = InferGoal(c0, v)
        Some((List(_ => g1), {
          case InferJudgment(_, _, _, tyV) :: Nil =>
            (true, NormalizationJudgment("NormSingleton", c, ty, tyV))
          case _ =>
            emitErrorWithJudgment("NormSingleton", g, None)
        }))
      } else {
        val g1 = NormalizationGoal(c0, tyUnderlying, linearExistsVars, inPositive)
        Some((List(_ => g1), {
          case NormalizationJudgment(_, _, _, tyUnderlyingN) :: Nil =>
            (true, NormalizationJudgment("NormSingleton", c, ty, SingletonType(tyUnderlyingN, v)))
          case _ =>
            emitErrorWithJudgment("NormSingleton", g, None)
        }))
      }
    case g =>
      None
  })

  val NormExists1 = Rule("NormExists1", {
    case g @ NormalizationGoal(c, ty @ ExistsType(ty1 @ SingletonType(_, _), Bind(id, ty2)),
        linearExistsVars, inPositive) =>
      TypeChecker.debugs(g, "NormExists1")
      val c0 = c.incrementLevel
      val g1 = NormalizationGoal(c0, ty1, linearExistsVars, inPositive)
      val g2: List[Judgment] => Goal = {
        case NormalizationJudgment(_, _, _, tyN1) :: Nil =>
          val c1 = c0.bind(id, tyN1)
          NormalizationGoal(c1, ty2, linearExistsVars, inPositive)
        case _ =>
          ErrorGoal(c0, Some(s"Expected normalized type"))
      }
      Some((List(_ => g1, g2), {
        case NormalizationJudgment(_, _, _, _) :: NormalizationJudgment(_, _, _, tyN2) :: Nil =>
          (true, NormalizationJudgment("NormExists1", c, ty, tyN2))
        case _ =>
          emitErrorWithJudgment("NormExists1", g, None)
      }))
    case g =>
      None
  })

  // NOTE: This rule should have lower priority than `NormSubstVar`.
  val NormExists2 = Rule("NormExists2", {
    case g @ NormalizationGoal(c, ty @ ExistsType(ty1, Bind(id, ty2)), linearExistsVars, inPositive) =>
      TypeChecker.debugs(g, "NormExists2")
      val c0 = c.incrementLevel
      val g1 = NormalizationGoal(c0, ty1, linearExistsVars, inPositive)
      val g2: List[Judgment] => Goal = {
        case NormalizationJudgment(_, _, _, tyN1) :: Nil =>
          // TODO: Assert tyN1 is not singleton? (Otherwise we might want to strip the Exists as in NormSubstVar)
          val c1 = c0.bind(id, tyN1)
          NormalizationGoal(c1, ty2, linearExistsVars, inPositive)
        case _ =>
          ErrorGoal(c0, Some(s"Expected normalized type"))
      }
      Some((List(_ => g1, g2), {
        case NormalizationJudgment(_, _, _, tyN1) :: NormalizationJudgment(_, _, _, tyN2) :: Nil =>
          (true, NormalizationJudgment("NormExists2", c, ty, ExistsType(tyN1, Bind(id, tyN2))))
        case _ =>
          emitErrorWithJudgment("NormExists2", g, None)
      }))
    case g =>
      None
  })

  val NormNatMatch = Rule("NormNatMatch", {
    case g @ NormalizationGoal(c,
        ty @ NatMatchType(tScrut, tyZero, tySuccBind @ Bind(id, tySucc)),
        linearExistsVars, inPositive) =>
      TypeChecker.debugs(g, "NormNatMatch")
      val c0 = c.incrementLevel
      val tScrutN = Interpreter.evaluateWithContext(c, tScrut)
      Some(tScrutN match {
        case NatLiteral(n) if n == 0 =>
          val g1 = NormalizationGoal(c0, tyZero, linearExistsVars, inPositive)
          (List(_ => g1), {
            case NormalizationJudgment(_, _, _, tyZeroN) :: Nil =>
              (true, NormalizationJudgment("NormNatMatch", c, ty, tyZeroN))
            case _ =>
              emitErrorWithJudgment("NormNatMatch", g, None)
          })
        case NatLiteral(n) =>
          // TODO: Re-type here instead?
          val c1 = c0
            .bind(id, SingletonType(NatType, NatLiteral(n - 1)))
          val g1 = NormalizationGoal(c1, tySucc, linearExistsVars, inPositive)
          (List(_ => g1), {
            case NormalizationJudgment(_, _, _, tySuccN) :: Nil =>
              (true, NormalizationJudgment("NormNatMatch", c, ty, tySuccN))
            case _ =>
              emitErrorWithJudgment("NormNatMatch", g, None)
          })
        case _ =>
          (List(), {
            case _ =>
              (true, NormalizationJudgment("NormNatMatch", c, ty, NatMatchType(tScrutN, tyZero, tySuccBind)))
          })
      })
    case g =>
      None
  })

  val NormListMatch = Rule("NormListMatch", {
    case g @ NormalizationGoal(c,
        ty @ ListMatchType(tScrut, tyNil, tyConsBind @ Bind(idHead, Bind(idTail, tyCons))),
        linearExistsVars, inPositive) =>
      TypeChecker.debugs(g, "NormListMatch")
      val c0 = c.incrementLevel
      val tScrutN = Interpreter.evaluateWithContext(c, tScrut)
      Some(tScrutN match {
        case LNil() =>
          val g1 = NormalizationGoal(c0, tyNil, linearExistsVars, inPositive)
          (List(_ => g1), {
            case NormalizationJudgment(_, _, _, tyNilN) :: Nil =>
              (true, NormalizationJudgment("NormListMatch", c, ty, tyNilN))
            case _ =>
              emitErrorWithJudgment("NormListMatch", g, None)
          })
        case LCons(tHead, tTail) =>
          // TODO: Re-type here instead?
          val c1 = c0
            .bind(idHead, SingletonType(TopType, tHead))
            .bind(idTail, SingletonType(LList, tTail))
          val g1 = NormalizationGoal(c1, tyCons, linearExistsVars, inPositive)
          (List(_ => g1), {
            case NormalizationJudgment(_, _, _, tyConsN) :: Nil =>
              (true, NormalizationJudgment("NormListMatch", c, ty, tyConsN))
            case _ =>
              emitErrorWithJudgment("NormListMatch", g, None)
          })
        case _ =>
          (List(), {
            case _ =>
              (true, NormalizationJudgment("NormListMatch", c, ty, ListMatchType(tScrutN, tyNil, tyConsBind)))
          })
      })
    case g =>
      None
  })

  val NormCons = Rule("NormCons", {
    case g @ NormalizationGoal(c, ty @ LConsType(tyHead, tyTail), linearExistsVars, inPositive) =>
      TypeChecker.debugs(g, "NormCons")
      val c0 = c.incrementLevel
      val g1 = NormalizationGoal(c0, tyHead, linearExistsVars, inPositive)
      val g2 = NormalizationGoal(c0, tyTail, linearExistsVars, inPositive)
      Some((List(_ => g1, _ => g2), {
        case NormalizationJudgment(_, _, _, tyHeadN) :: NormalizationJudgment(_, _, _, tyTailN) :: Nil =>
          (true, NormalizationJudgment("NormCons", c, ty, LConsType(tyHeadN, tyTailN)))
        case _ =>
          emitErrorWithJudgment("NormCons", g, None)
      }))
    case g =>
      None
  })

  val NormPi = Rule("NormPi", {
    case g @ NormalizationGoal(c, ty @ PiType(ty1, Bind(id, ty2)), linearExistsVars, inPositive) =>
      TypeChecker.debugs(g, "NormPi")
      val c0 = c.incrementLevel
      val g1 = NormalizationGoal(c0, ty1, linearExistsVars, inPositive = false)
      val g2: List[Judgment] => Goal = {
        case NormalizationJudgment(_, _, _, tyN1) :: Nil =>
          val c1 = c0.bind(id, tyN1)
          NormalizationGoal(c1, ty2, linearExistsVars, inPositive)
        case _ =>
          ErrorGoal(c0, None)
      }
      Some((List(_ => g1, g2), {
        case NormalizationJudgment(_, _, _, tyN1) :: NormalizationJudgment(_, _, _, tyN2) :: Nil =>
          (true, NormalizationJudgment("NormPi", c, ty, PiType(tyN1, Bind(id, tyN2))))
        case _ =>
          emitErrorWithJudgment("NormPi", g, None)
      }))
    case g =>
      None
  })

  val NormSigma = Rule("NormSigma", {
    case g @ NormalizationGoal(c, ty @ SigmaType(ty1, Bind(id, ty2)), linearExistsVars, inPositive) =>
      TypeChecker.debugs(g, "NormSigma")
      val c0 = c.incrementLevel
      val g1 = NormalizationGoal(c0, ty1, linearExistsVars, inPositive)
      val g2: List[Judgment] => Goal = {
        case NormalizationJudgment(_, _, _, tyN1) :: Nil =>
          val c1 = c0.bind(id, tyN1)
          NormalizationGoal(c1, ty2, linearExistsVars, inPositive)
        case _ =>
          ErrorGoal(c0, None)
      }
      Some((List(_ => g1, g2), {
        case NormalizationJudgment(_, _, _, tyN1) :: NormalizationJudgment(_, _, _, tyN2) :: Nil =>
          (true, NormalizationJudgment("NormSigma", c, ty, SigmaType(tyN1, Bind(id, tyN2))))
        case _ =>
          emitErrorWithJudgment("NormSigma", g, None)
      }))
    case g =>
      None
  })

  val NormBase = Rule("NormBase", {
    case g @ NormalizationGoal(c, TopType | BoolType | NatType | `UnitType` | `LList`, linearExistsVars, inPositive) =>
      TypeChecker.debugs(g, "NormBase")
      val c0 = c.incrementLevel
      Some((List(), {
        case _ =>
          (true, NormalizationJudgment("NormBase", c, g.ty, g.ty))
      }))
    case g =>
      None
  })

  def asSingleton(ty: Tree): Tree = {
    var newBindings = List.empty[(Identifier, Tree)]
    def rec(ty: Tree): (Tree, Tree) =
      ty match {
        case SingletonType(tyUnderlying, t) =>
          (tyUnderlying, t)
        case TopType | BoolType | NatType | `UnitType` | `LList` =>
          val id = Identifier.fresh("x")
          newBindings ::= id -> ty
          (ty, Var(id))
        case PiType(ty1, Bind(id, ty2)) =>
          // TODO: To be checked
          val idF = Identifier.fresh("f")
          val tyN = PiType(ty1, Bind(id, asSingleton(ty2)))
          newBindings ::= idF -> tyN
          (tyN, Var(idF))
        case ListMatchType(_, _, _) =>
          // TODO: To be checked
          val idLM = Identifier.fresh("lm")
          newBindings ::= idLM -> ty
          (ty, Var(idLM))
        case NatMatchType(_, _, _) =>
          // TODO: To be checked
          val idNM = Identifier.fresh("nm")
          newBindings ::= idNM -> ty
          (ty, Var(idNM))
        case LConsType(ty1, ty2) =>
          val (ty1UnderlyingN, t1) = rec(ty1)
          val (ty2UnderlyingN, t2) = rec(ty2)
          (LConsType(ty1UnderlyingN, ty2UnderlyingN), LCons(t1, t2))
        case SigmaType(ty1, Bind(id, ty2)) =>
          val (ty1UnderlyingN, t1) = rec(ty1)
          val (ty2UnderlyingN, t2) = rec(ty2)
          (SigmaType(ty1UnderlyingN, Bind(id, ty2UnderlyingN)), Pair(t1, t2))
        case ExistsType(ty1, Bind(id, ty2)) =>
          newBindings ::= id -> ty1
          rec(ty2)
      }
    val (tyUnderlyingN, tN) = rec(ty)
    val tyN = SingletonType(tyUnderlyingN, tN)
    newBindings.foldLeft(tyN) { case (tyAcc, (id, ty)) => ExistsType(ty, Bind(id, tyAcc)) }
  }

  def choosesToExists(ty: Tree): Tree = {
    var pathToBinding = Map.empty[Tree, (Identifier, Tree)]
    var potentialPathVars = Set.empty[Identifier]
    def pathPrefixIdent(t: Tree): Option[Identifier] =
      t match {
        case LCons(_, tTail) => pathPrefixIdent(tTail)
        case Var(id) => Some(id)
        case _ => None
      }
    def recTerm(t: Tree): Tree =
      t match {
        case ChooseWithPath(ty, path) =>
          pathToBinding.get(path) match {
            case Some((id, _)) =>
              Var(id)
            case None =>
              pathPrefixIdent(path) match {
                case Some(pathId) if potentialPathVars.contains(pathId) =>
                  val id = Identifier.fresh("v")
                  pathToBinding += path -> (id, ty)
                  Var(id)
                case _ =>
                  t
              }
          }
        case Var(id) => t
        case Pair(t1, t2) => Pair(recTerm(t1), recTerm(t2))
        case First(t) => First(recTerm(t))
        case Second(t) => Second(recTerm(t))
        case App(f, t) => App(recTerm(f), recTerm(t))
        case LetIn(optTy, value, Bind(id, body)) =>
          LetIn(optTy, recTerm(value), Bind(id, recTerm(body)))
        case NatMatch(t, t1, Bind(id2, t2)) =>
          NatMatch(recTerm(t), recTerm(t1), Bind(id2, recTerm(t2)))
        case EitherMatch(t, Bind(id1, t1), Bind(id2, t2)) =>
          EitherMatch(recTerm(t), Bind(id1, recTerm(t1)), Bind(id2, recTerm(t2)))
        case ListMatch(t, t1, Bind(idHead, Bind(idTail, t2))) =>
          ListMatch(recTerm(t), recTerm(t1), Bind(idHead, Bind(idTail, recTerm(t2))))
        case LeftTree(t) => LeftTree(recTerm(t))
        case RightTree(t) => RightTree(recTerm(t))
        // Don't dive into terms that might use chooses referring to a different `p`:
        case FixWithDefault(_, _, _, _) => t
        case _: NatLiteral | _: BooleanLiteral | _: UnitLiteral.type | _: Lambda => t
      }
    def recType(ty: Tree): Tree =
      ty match {
        case SingletonType(tyUnderlying, t) =>
          SingletonType(recType(tyUnderlying), recTerm(t))
        case ExistsType(ty1, Bind(id, ty2)) =>
          if (ty1 == LList && id.name == "p")
            potentialPathVars += id
          val ty2N = recType(ty2)
          if (id.isFreeIn(ty2N))
            ExistsType(ty1, Bind(id, ty2N))
          else
            ty2N

        case TopType | BoolType | NatType | `UnitType` | `LList` =>
          ty
        case PiType(ty1, Bind(id, ty2)) =>
          PiType(recType(ty1), Bind(id, recType(ty2)))
        case ListMatchType(t, tyNil, Bind(id1, Bind(id2, tyCons))) =>
          ListMatchType(recTerm(t), recType(tyNil), Bind(id1, Bind(id2, recType(tyCons))))
        case NatMatchType(t, tyZero, Bind(id, tySucc)) =>
          NatMatchType(recTerm(t), recType(tyZero), Bind(id, recType(tySucc)))
        case LConsType(ty1, ty2) =>
          LConsType(recType(ty1), recType(ty2))
        case SigmaType(ty1, Bind(id, ty2)) =>
          SigmaType(recType(ty1), Bind(id, recType(ty2)))
      }
    val tyN = recType(ty)
    pathToBinding.values.foldLeft(tyN) { case (tyAcc, (id, ty)) => ExistsType(ty, Bind(id, tyAcc)) }
  }

  // NOTE: This only matches on NormalizedSubtypeGoal, which is not a SubtypeGoal,
  //       but yields a SubtypeJudgment!
  val SubNormalize = Rule("SubNormalize", {
    case g @ NormalizedSubtypeGoal(c, ty1, ty2) =>
      TypeChecker.debugs(g, "SubNormalize")
      val c0 = c.incrementLevel
      val g1 = NormalizationGoal(c0, ty1)
      val g2 = NormalizationGoal(c0, ty2)
      val g3: List[Judgment] => Goal = {
        case NormalizationJudgment(_, _, _, tyN1) :: NormalizationJudgment(_, _, _, tyN2) :: Nil =>
          SubtypeGoal(c0, choosesToExists(asSingleton(tyN1)), choosesToExists(asSingleton(tyN2)))
        case _ =>
          ErrorGoal(c0, Some(s"Expected normalized types"))
      }
      Some((List(_ => g1, _ => g2, g3), _ => (true, SubtypeJudgment("SubNormalize", c, ty1, ty2))))
    case g =>
      None
  })

  val SubReflexive = Rule("SubReflexive", {
    case g @ SubtypeGoal(c, ty1, ty2) if Tree.areEqual(ty1, ty2) =>
      TypeChecker.debugs(g, "SubReflexive")
      Some((List(), _ => (true, SubtypeJudgment("SubReflexive", c, ty1, ty2))))
    case g =>
      None
  })

  val SubTop = Rule("SubTop", {
    case g @ SubtypeGoal(c, ty, TopType) =>
      TypeChecker.debugs(g, "SubTop")
      Some((List(), _ => (true, SubtypeJudgment("SubTop", c, ty, TopType))))
    case g =>
      None
  })

  val SubSingletonLeft = Rule("SubSingletonLeft", {
    case g @ SubtypeGoal(c, ty @ SingletonType(ty1, _), ty2) =>
      TypeChecker.debugs(g, "SubSingletonLeft")

      val subgoal = SubtypeGoal(c.incrementLevel, ty1, ty2)
      Some((List(_ => subgoal), {
        case SubtypeJudgment(_, _, _, _) :: _ =>
          (true, SubtypeJudgment("SubSingletonLeft", c, ty, ty2))
        case _ =>
          (false, ErrorJudgment("SubSingletonLeft", g, None))
      }))
    case g =>
      None
  })

  // // NOTE: This version of SubSingletonLeft normalizes `t` to always infer an up-to-date underlying type.
  // //       However, this is not fool-proof -- it might cause type checking to diverge.
  // val SubSingletonLeft = Rule("SubSingletonLeft", {
  //   case g @ SubtypeGoal(c, ty @ SingletonType(_, t), ty2) =>
  //     TypeChecker.debugs(g, "SubSingletonLeft")
  //
  //     val c0 = c.incrementLevel
  //     val v = Interpreter.evaluateWithContext(c0, t)
  //     val g1 = InferGoal(c0, v)
  //     val g2: List[Judgment] => Goal = {
  //       case InferJudgment(_, _, _, SingletonType(ty1N, _)) :: Nil =>
  //         SubtypeGoal(c0, ty1N, ty2)
  //       case _ =>
  //         ErrorGoal(c0, Some("Expected re-typed term to have singleton type"))
  //     }
  //     Some((List(_ => g1, g2), {
  //       case _ :: SubtypeJudgment(_, _, _, _) :: Nil =>
  //         (true, SubtypeJudgment("SubSingletonLeft", c, ty, ty2))
  //       case _ =>
  //         (false, ErrorJudgment("SubSingletonLeft", g, None))
  //     }))
  //   case g =>
  //     None
  // })

  val SubPi = Rule("SubPi", {
    case g @ SubtypeGoal(c,
      tya @ PiType(tya1, Bind(ida, tya2)),
      tyb @ PiType(tyb1, Bind(idb, tyb2))) =>
      TypeChecker.debugs(g, "SubPi")

      val c0 = c.incrementLevel
      val g1 = SubtypeGoal(c0, tyb1, tya1)
      val g2 = NormalizedSubtypeGoal(c0.bind(ida, tyb1), tya2, tyb2.replace(idb, ida))
      Some((List(_ => g1, _ => g2), {
        case SubtypeJudgment(_, _, _, _) :: SubtypeJudgment(_, _, _, _) :: Nil =>
          (true, SubtypeJudgment("SubPi", c, tya, tyb))
        case _ =>
          emitErrorWithJudgment("SubPi", g, None)
      }))
    case g =>
      None
  })

  val SubNatMatch = Rule("SubNatMatch", {
    case g @ SubtypeGoal(c,
      tya @ NatMatchType(t, tyZero, Bind(id, tySucc)),
      tyb
    ) =>
      TypeChecker.debugs(g, "SubNatMatch")

      val c0 = c.incrementLevel
      val g1 = SubtypeGoal(c0, tyZero, tyb)
      val g2 = SubtypeGoal(c0.bind(id, NatType), tySucc, tyb)
      Some((List(_ => g1, _ => g2), {
        case SubtypeJudgment(_, _, _, _) :: SubtypeJudgment(_, _, _, _) :: Nil =>
          (true, SubtypeJudgment("SubNatMatch", c, tya, tyb))
        case _ =>
          emitErrorWithJudgment("SubNatMatch", g, None)
      }))

    case g =>
      None
  })


  val SubListMatch = Rule("SubListMatch", {
    case g @ SubtypeGoal(c,
      tya @ ListMatchType(t, tyNil, Bind(idHead, Bind(idTail, tyCons))),
      tyb
    ) =>
      TypeChecker.debugs(g, "SubListMatch")

      val c0 = c.incrementLevel
      val g1 = SubtypeGoal(c0, tyNil, tyb)
      val g2 = SubtypeGoal(c0.bind(idHead, TopType).bind(idTail, LList), tyCons, tyb)
      Some((List(_ => g1, _ => g2), {
        case SubtypeJudgment(_, _, _, _) :: SubtypeJudgment(_, _, _, _) :: Nil =>
          (true, SubtypeJudgment("SubListMatch", c, tya, tyb))
        case _ =>
          emitErrorWithJudgment("SubListMatch", g, None)
      }))

    case g =>
      None
  })

  object ExistsTypes {
    def unapply(ty: Tree): Some[(List[(Identifier, Tree)], Tree)] =
      Some(ty match {
        case ExistsType(ty1, Bind(id, ExistsTypes(bindings, ty2))) =>
          ((id, ty1) :: bindings, ty2)
        case _ =>
          (List.empty, ty)
      })
  }

  val SubExistsLeft = Rule("SubExistsLeft", {
    case g @ SubtypeGoal(c,
      tya @ ExistsTypes(bindings1, ty1),
      tyb
    ) if bindings1.nonEmpty =>
      TypeChecker.debugs(g, "SubExistsLeft")

      val c0 = c.incrementLevel
      val c1 = bindings1.foldRight(c0) { case ((id, ty), cAcc) => cAcc.bind(id, ty) }
      val g1 = SubtypeGoal(c1, ty1, tyb)
      Some((
        List(_ => g1), {
          case SubtypeJudgment(_, _, _, _) :: Nil =>
            (true, SubtypeJudgment("SubExistsLeft", c, tya, tyb))
          case _ => emitErrorWithJudgment("SubExistsLeft", g, None)
        }
      ))

    case g =>
      None
  })

  def pathPrefixIdent(t: Tree): Option[Identifier] =
    t match {
      case LCons(_, tTail) => pathPrefixIdent(tTail)
      case Var(id) => Some(id)
      case _ => None
    }

  val SubExistsInst = Rule("SubExistsInst", {
    case g @ SubtypeGoal(c,
      tya @ SingletonType(ty1Underlying, t),
      tyb @ ExistsTypes(bindings2,
        SingletonType(ty2Underlying, Var(id)))
    ) if bindings2.toMap.contains(id) =>
      TypeChecker.debugs(g, "SubExistsInst")

      val ty2Base = bindings2.toMap.apply(id)

      // Replace `id` by `t`
      val ty2UnderlyingInst = ty2Underlying.replace(id, t)
      val bindings2Rest = bindings2.filterNot(_._1 == id)
      val ty2UnderlyingInstWrapped =
        bindings2Rest.foldRight(ty2UnderlyingInst) { case ((id, ty), acc) =>
          ExistsType(ty, Bind(id, acc))
        }

      val c0 = c.incrementLevel
      val g1 = SubtypeGoal(c0, tya, ty2UnderlyingInstWrapped)
      val g2 = SubtypeGoal(c0, tya, ty2Base)
      Some((
        List(_ => g1, _ => g2), {
          case SubtypeJudgment(_, _, _, _) :: SubtypeJudgment(_, _, _, _) :: Nil =>
            (true, SubtypeJudgment("SubExistsInst", c, tya, tyb))
          case _ => emitErrorWithJudgment("SubExistsInst", g, None)
        }
      ))

    case g =>
      None
  })

  def existsRightSubgoal(c: Context, tyU: Tree, tLeft: Tree, tyV: Tree, tRight: Tree, bindingsRight: Seq[(Identifier, Tree)]): (Goal, Set[Identifier]) = {
    def usedExistentialsOf(t: Tree): Set[Identifier] = {
      val bindingsRightMap = bindingsRight.toMap
      var ids = Set.empty[Identifier]
      t.replaceMany {
        case Var(id) if bindingsRightMap.contains(id) => ids += id; None
        case _ => None
      }
      ids
    }

    val tyLeft = SingletonType(tyU, tLeft)
    val tyRight = SingletonType(tyV, tRight)
    val usedExistentials = usedExistentialsOf(tyRight)
    val tyRightWrapped = bindingsRight
      .filter { case (id, _ ) => usedExistentials.contains(id) }
      .foldRight(tyRight) { case ((id, ty), acc) => ExistsType(ty, Bind(id, acc)) }
    (SubtypeGoal(c, tyLeft, tyRightWrapped), usedExistentials)
  }

  val SubExistsCons = Rule("SubExistsCons", {
    case g @ SubtypeGoal(c,
      tya @ SingletonType(tyU, LCons(ta1, ta2)),
      tyb @ ExistsTypes(bindings2, SingletonType(tyV, LCons(tb1, tb2)))
    ) if bindings2.nonEmpty =>
      TypeChecker.debugs(g, "SubExistsCons")

      (widen(tyU), widen(tyV)) match {
        case (LConsType(tyU1, tyU2), LConsType(tyV1, tyV2)) =>
          val c0 = c.incrementLevel
          val (g1, usedExistentials1) = existsRightSubgoal(c0, tyU1, ta1, tyV1, tb1, bindings2)
          val (g2, usedExistentials2) = existsRightSubgoal(c0, tyU2, ta2, tyV2, tb2, bindings2)
          assert(usedExistentials1.intersect(usedExistentials2).isEmpty)
          assert(usedExistentials1 ++ usedExistentials2 == bindings2.toMap.keys.toSet)
          Some((
            List(_ => g1, _ => g2), {
              case SubtypeJudgment(_, _, _, _) :: SubtypeJudgment(_, _, _, _) :: Nil =>
                (true, SubtypeJudgment("SubExistsCons", c, tya, tyb))
              case _ => emitErrorWithJudgment("SubExistsCons", g, None)
            }
          ))

        case _ =>
          None
      }

    case _ =>
      None
  })

  val SubExistsPair = Rule("SubExistsPair", {
    case g @ SubtypeGoal(c,
      tya @ SingletonType(tyU, Pair(ta1, ta2)),
      tyb @ ExistsTypes(bindings2, SingletonType(tyV, Pair(tb1, tb2)))
    ) if bindings2.nonEmpty =>
      TypeChecker.debugs(g, "SubExistsPair")

      (widen(tyU), widen(tyV)) match {
        case (SigmaType(tyU1, Bind(id, tyU2)), SigmaType(tyV1, Bind(idRight, tyV2))) =>
          val c0 = c.incrementLevel
          // TODO: Freshen in tyV1, tb1, tyV2 and tb2?
          val c1 = c0.bind(id, tyU1)
          val (g1, usedExistentials1) = existsRightSubgoal(c0, tyU1, ta1, tyV1, tb1, bindings2)
          val (g2, usedExistentials2) = existsRightSubgoal(c1, tyU2, ta2, tyV2.replace(idRight, Var(id)), tb2.replace(idRight, Var(id)), bindings2)
          assert(usedExistentials1.intersect(usedExistentials2).isEmpty)
          assert(usedExistentials1 ++ usedExistentials2 == bindings2.toMap.keys.toSet)
          Some((
            List(_ => g1, _ => g2), {
              case SubtypeJudgment(_, _, _, _) :: SubtypeJudgment(_, _, _, _) :: Nil =>
                (true, SubtypeJudgment("SubExistsPair", c, tya, tyb))
              case _ => emitErrorWithJudgment("SubExistsPair", g, None)
            }
          ))

        case _ =>
          None
      }

    case _ =>
      None
  })

  val InferNatMatch1 = Rule("InferNatMatch1", {
    case g @ InferGoal(c, e @ NatMatch(t, t1, Bind(id, t2))) =>
      TypeChecker.debugs(g, "InferNatMatch1")
      val c0 = c.incrementLevel
      val inferScrutinee = CheckGoal(c0, t, NatType)

      val inferT1 = InferGoal(c0, t1)

      val (c1, t2F) = c0.bindAndFreshen(id, NatType, t2)
      val inferT2 = InferGoal(c1, t2F)

      Some((
        List(_ => inferScrutinee, _ => inferT1, _ => inferT2), {
          case CheckJudgment(_, _, _, _) ::
            InferJudgment(_, _, _, ty1) ::
            InferJudgment(_, _, _, ty2) :: _ =>
              (true, InferJudgment("InferNatMatch1", c, e,
                NatMatchType(t, ty1, Bind(id, ty2))))

          case _ => emitErrorWithJudgment("InferNatMatch1", g, None)
        }
      ))

    case _ => None
  })

  val InferListMatch = Rule("InferListMatch", {
    case g @ InferGoal(c, e @ ListMatch(t, t1, Bind(idHead, Bind(idTail, t2)))) =>
      TypeChecker.debugs(g, "InferListMatch")
      val c0 = c.incrementLevel
      val inferScrutinee = CheckGoal(c0, t, LList)

      val inferT1 = InferGoal(c0, t1)

      val c1 = c0.bind(idHead, TopType)
      val (c2, t2F) = c1.bindAndFreshen(idTail, LList, t2)
      val inferT2 = InferGoal(c2, t2F)

      Some((
        List(_ => inferScrutinee, _ => inferT1, _ => inferT2), {
          case CheckJudgment(_, _, _, _) ::
            InferJudgment(_, _, _, ty1) ::
            InferJudgment(_, _, _, ty2) :: _ =>
              (true, InferJudgment("InferListMatch", c, e,
                ListMatchType(t, ty1, Bind(idHead, Bind(idTail, ty2)))))

          case _ => emitErrorWithJudgment("InferListMatch", g, None)
        }
      ))

    case _ => None
  })

  val SubCons = Rule("SubCons", {
    case g @ SubtypeGoal(c,
      tya @ LConsType(_, _),
      tyb @ `LList`
    ) =>
      TypeChecker.debugs(g, "SubCons")
      Some((List(), _ => {
        (true, SubtypeJudgment("SubCons", c, tya, tyb))
      }))

    case g =>
      None
  })

  val InferFixWithDefault = Rule("InferFixWithDefault", {
    case g @ InferGoal(c, e @ FixWithDefault(ty, t @ Bind(fIn, tBody), td, _)) =>
      TypeChecker.debugs(g, "InferFixWithDefault")

      val c0 = c.incrementLevel
      val (c1, tBodyF) = c0.bindAndFreshen(fIn, ty, tBody)
      val g1 = CheckGoal(c1, tBodyF, ty)
      val g2 = CheckGoal(c0, td, ty)

      Some((
        List(_ => g1, _ => g2),
        _ =>
          (true, InferJudgment("InferFixWithDefault", c, e, SingletonType(ty, e)))))

    case _ => None
  })
}
