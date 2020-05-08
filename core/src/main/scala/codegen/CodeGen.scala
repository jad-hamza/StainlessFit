package stainlessfit
package core
package codegen

import trees._
import util.RunContext
import extraction._
import codegen.llvm.IR.{And => IRAnd, Or => IROr, Not => IRNot, Neq => IRNeq,
  Eq => IREq, Lt => IRLt, Gt => IRGt, Leq => IRLeq, Geq => IRGeq, Nop => IRNop,
  Plus => IRPlus, Minus => IRMinus, Mul => IRMul, Div => IRDiv,
  BooleanLiteral => IRBooleanLiteral, UnitLiteral => IRUnitLiteral,
  NatType => IRNatType, UnitType => IRUnitType, _}

import codegen.llvm._
import codegen.utils.{Identifier => _, _}

class CodeGen(val rc: RunContext) {

    def genLLVM(tree: Tree, isMainModule: Boolean, moduleName: String): Module = {
        cgModule(tree, moduleName)
    }

    def cgModule(inputTree: Tree, moduleName: String): Module = {

      val (defFunctions, mainBody) = extractDefFun(inputTree)

      val fh = new FunctionHandler(rc)
      val emptyFunctions: List[(Function, LocalHandler, Tree)] = defFunctions.map(defFun => translateDefFunction(defFun, fh))
      val functions = emptyFunctions.map{ case (fun, lh, funBody) => cgFunction(fun, lh, funBody, fh) }

      val mainFunction = Function(IRNatType, Global("main"), Nil)
      val main = cgFunction(mainFunction, new LocalHandler(rc), mainBody, fh, Nil, true)

      val lambdas = fh.getLambdas()

      Module(
        moduleName,
        main,
        functions,
        lambdas)
    }

    def cgFunction(function: Function, lh: LocalHandler, body: Tree, fh: FunctionHandler,
        firstInstructions: List[Instruction] = Nil, isMain: Boolean = false): Function = {

      val initBlock = lh.newBlock("Entry") <:> firstInstructions
      val filteredBody = filterErasable(body)
      val returnType = if(isMain) typeOf(filteredBody)(lh, fh, Map.empty) else function.returnType

      val end = if(isMain) lh.freshLabel("Print.and.exit") else lh.freshLabel("End")
      val result = lh.freshLocal("result")

      val prep = preAllocate(result, function.returnType)(lh)

      val (entryBlock, phi) = codegen(filteredBody, initBlock <:> prep, Some(end), Some(result), returnType)(lh, function, fh)
      function.add(entryBlock)

      val endBlock = lh.newBlock(end)

      val (lastBlock, returnValue) = if(isMain){
        //TODO add printing of lambdas
        val resultPrinter = new ResultPrinter(rc)
        (resultPrinter.customPrint(endBlock, result, returnType, false, None)(lh, function), Value(Nat(0)))
      } else {
        (endBlock, Value(result))
      }

      val ret = Return(returnValue, function.returnType)
      function.add(lastBlock <:> ret)

      function
    }

    def extractDefFun(t: Tree): (List[DefFunction], Tree) = t match {
      case defFun @ DefFunction(_, _, _, _, nested) => {  //DefFunction(_, _, _, _, Bind(fid, body))
        val (defs, rest) = extractDefFun(nested)
        (defFun +: defs, rest)
      }
      case bind: Bind => extractDefFun(extractBody(bind)._2)

      case Var(_) => (Nil, NatLiteral(BigInt(0)))
      case rest => (Nil, rest)
    }

    def extractBody(bind: Tree): (Identifier, Tree) = bind match {
      case Bind(id, rec: Bind) => extractBody(rec)
      case Bind(id, body) => (id, body)
      case _ => rc.reporter.fatalError(s"Couldn't find the body in $bind")
    } //Binds(bind: Bind): Option(Seq[Identifier], Tree) otherwise only the first identifier is returned

    def extractFreeVariables(t: Tree)(implicit closedVariables: List[Identifier]): List[Identifier] = t match {
      case Var(id) if !closedVariables.contains(id) => List(id)
      case LetIn(_, _, Bind(id, rest)) => extractFreeVariables(rest)(id +: closedVariables)

      case Primitive(op, args) => args.flatMap(arg => extractFreeVariables(arg))

      case IfThenElse(cond, thenn, elze) =>
        extractFreeVariables(cond) ++
        extractFreeVariables(thenn) ++
        extractFreeVariables(elze)

      case app @ App(_, _) => {
        val (_, flatArgs) = flattenApp(app)
        flatArgs.flatMap(arg => extractFreeVariables(arg))
      }

      case Pair(first, second) => extractFreeVariables(first) ++ extractFreeVariables(second)
      case First(pair) => extractFreeVariables(pair)
      case Second(pair) => extractFreeVariables(pair)
      case LeftTree(either) => extractFreeVariables(either)
      case RightTree(either) => extractFreeVariables(either)
      case Bind(_, body) => extractFreeVariables(body)
      case _ => Nil
    }

    def translateDefFunction(defFun: DefFunction, fh: FunctionHandler): (Function, LocalHandler, Tree) = {
      val DefFunction(args, optReturnType, _, bind, _) = defFun

      val params = args.toList.map{
        case TypedArgument(id, tpe) => (id, ParamDef(translateType(tpe), Local(id.name)))
        case arg => rc.reporter.fatalError(s"Unexpected type for arg $arg")
      }

      val (funId, body) = extractBody(bind)

      val returnType = translateType(optReturnType.getOrElse(rc.reporter.fatalError("No return type found")))

      val function = Function(returnType, Global(funId.name), params.unzip._2)
      fh.addFunction(funId, function)

      val lh = new LocalHandler(rc)
      lh.add(params)

      (function, lh, body)
    }

    def translateOp(op: Operator): Op = op match {
      case Not => IRNot
      case And => IRAnd
      case Or => IROr
      case Neq => IRNeq
      case Eq => IREq
      case Lt => IRLt
      case Gt => IRGt
      case Leq => IRLeq
      case Geq => IRGeq
      case Nop => IRNop
      case Plus => IRPlus
      case Minus => IRMinus
      case Mul => IRMul
      case Div => IRDiv

      case _ => rc.reporter.fatalError(s"Unable to translate operator $op")
    }

    def translateType(tpe: Tree): Type = tpe match {
      case BoolType => BooleanType
      case NatType => IRNatType
      case UnitType => IRUnitType
      case Bind(_, rest) => translateType(rest) //TODO Is this necessary
      case SigmaType(tpe, bind) => PairType(translateType(tpe), translateType(bind))
      case PiType(argType, Bind(_, retType)) => LambdaValue(translateType(argType), translateType(retType))
      case SumType(leftType ,rightType) => EitherType(translateType(leftType), translateType(rightType))
      case RefinementType(typee, _) => translateType(typee)
      case _ => rc.reporter.fatalError(s"Unable to translate type $tpe")
    }

    def translateValue(t: Tree)(implicit lh: LocalHandler): Value = t match {
      case BooleanLiteral(b) => Value(IRBooleanLiteral(b))
      case NatLiteral(n) => Value(Nat(n))
      case UnitLiteral => Value(Nat(0))
      case Var(id) => Value(lh.getLocal(id))
      case _ => rc.reporter.fatalError(s"This tree isn't a value: $t")
    }

    def flattenPrimitive(t: Tree): List[Tree] = t match {
      case Primitive(op, args) => args.flatMap{
        case Primitive(op2, args2) if op2 == op => flattenPrimitive(Primitive(op2, args2))
        case other => List(other)
      }

      case _ => rc.reporter.fatalError(s"flattenPrimitive is not defined for $t")
    }

    def flattenApp(t: Tree): (Identifier, List[Tree]) = t match {
      case App(Var(id), arg) => (id, List(arg))
      case App(recApp @ App(_, _), arg) => {
        val (id, otherArgs) = flattenApp(recApp)
        (id , otherArgs :+ arg)
      }

      case _ => rc.reporter.fatalError(s"flattenApp is not defined fo $t")
    }

    def typeOfOperand(op: Operator): Type = op match {
      case Not => BooleanType
      case And => BooleanType
      case Or => BooleanType

      case Neq => IRNatType
      case Eq => IRNatType
      case Lt => IRNatType
      case Gt => IRNatType
      case Leq => IRNatType
      case Geq => IRNatType

      // case Nop => IRNop

      case Plus => IRNatType
      case Minus => IRNatType
      case Mul => IRNatType
      case Div => IRNatType

      case _ => rc.reporter.fatalError(s"Unable to determine the operand type of $op")
    }

    def typeOf(t: Tree)(implicit lh: LocalHandler, fh: FunctionHandler, helper: Map[Identifier, Type]): Type = t match {
      case BooleanLiteral(_) => BooleanType
      case NatLiteral(_) => IRNatType
      case UnitLiteral => IRUnitType
      case Var(id) => {
        if(helper.contains(id)){
          helper.get(id).get
        } else {
          lh.getType(id)
        }
      }
      case LetIn(optType, value, Bind(id, rest)) => {
        val valueType = optType match {
          case Some(tpe) => translateType(tpe)
          case None => typeOf(value)
        }

        val updatedHelper = helper + (id -> valueType)

        typeOf(rest)(lh, fh, updatedHelper)
      }
      case Primitive(op, _) => translateOp(op).returnType
      case IfThenElse(_, thenn, _) => typeOf(thenn)

      case Lambda(optArgType, Bind(argId, body)) => {
        val treeArgType = optArgType.getOrElse(rc.reporter.fatalError("Need to know the type of the argument"))
        val argType = translateType(treeArgType)
        val updatedHelper = helper + (argId -> argType)
        LambdaValue(argType, typeOf(body)(lh, fh, updatedHelper))
      }

      case App(Var(id), arg) => {
        if(fh.hasFunction(id) || fh.hasLambda(id)){
          if(fh.getArgNumber(id) == 0){
            //Function returns a lambda which is then applied
            val lambdaType = fh.getReturnType(id)
            val (_, retType) = getLambdaPrototype(lambdaType)
            retType
          } else {
            fh.getReturnType(id)
          }
        } else {
          //Lambda variable applied to arg
          val lambdaType = typeOf(Var(id))
          val (_, retType) = getLambdaPrototype(lambdaType)
          retType
        }
      }

      case App(recApp, arg) => {
        val lambdaType = typeOf(recApp)
        val (_, retType) = getLambdaPrototype(lambdaType)
        retType
      }

      case Pair(first, second) => PairType(typeOf(first), typeOf(second))

      case First(pair) => typeOf(pair) match {
        case PairType(firstType, _) => firstType
        case nested @ FirstType(_) => FirstType(nested)
        case nested @ SecondType(_) => FirstType(nested)
        case other => rc.reporter.fatalError(s"Unexpected operation: calling First on type $other")
      }

      case Second(pair) => typeOf(pair) match {
        case PairType(_, secondType) => secondType
        case nested @ FirstType(_) => SecondType(nested)
        case nested @ SecondType(_) => SecondType(nested)
        case other => rc.reporter.fatalError(s"Unexpected operation: calling Second on type $other")
      }

      case LeftTree(either) => LeftType(typeOf(either))

      case RightTree(either) => RightType(typeOf(either))

      case EitherMatch(_, t1, t2) => {
        (typeOf(t1), typeOf(t2)) match {
          case (leftType, rightType) if leftType == rightType => leftType
          case (LeftType(innerLeft), RightType(innerRight)) => EitherType(innerLeft, innerRight)
          case (RightType(innerRight), LeftType(innerLeft)) => EitherType(innerLeft, innerRight)
          case (leftType, rightType) =>
            rc.reporter.fatalError(s"EitherMatch returns different types: left => $leftType, right => $rightType")
        }
      }

      case Bind(_, body) => typeOf(body)

      case _ => rc.reporter.fatalError(s"TypeOf not yet implemented for $t")
    }

    def isValue(t: Tree): Boolean = t match {
      case BooleanLiteral(_) | NatLiteral(_) | UnitLiteral | Var(_) => true
      case _ => false
    }

    def cgValue(tpe: Type, value: Value, next: Option[Label], toAssign: Option[Local])
      (implicit lh: LocalHandler): List[Instruction] = {
        val jump = jumpTo(next)
        val assign = Assign(assignee(toAssign), tpe, value)
        if(toAssign.isEmpty && jump.isEmpty) rc.reporter.fatalError("Unexpected control flow during codegen")

        assign +: jump
    }

    def cgTopLevelFunCall(funId: Identifier, args: List[Tree], block: Block, next: Option[Label], toAssign: Option[Local])
      (implicit lh: LocalHandler, f: Function, fh: FunctionHandler): (Block, List[Instruction]) = {

        val result = assignee(toAssign)
        val jump = jumpTo(next)
        val resultType = fh.getReturnType(funId)

        val init: (Block, List[Value], List[Type]) = (block, Nil, Nil)

        val (currentBlock, values, argTypes) = args.zipWithIndex.foldLeft(init){
          case ((currentBlock, values, argTypes), (arg, index)) => {
            val temp = lh.freshLocal(s"arg_$index")
            val argType = fh.getArgType(funId, index)

            val prepArg = preAllocate(temp, argType)

            val (nextBlock, phi) = codegen(arg, currentBlock <:> prepArg, None, Some(temp), argType)
            (nextBlock <:> phi, values :+ Value(temp), argTypes :+ argType)
          }
        }

        val (extraArg, extraType) = if(fh.hasLambda(funId)){ //TODO
          (List(Value(Local("raw.env"))), List(RawEnvType))
        } else {
          (Nil, Nil)
        }
        val params = values ++ extraArg
        val paramTypes = argTypes ++ extraType
        (currentBlock <:> CallTopLevel(result, resultType, fh.getGlobal(funId), params, paramTypes) <:> jump, Nil)
    }

    def cgLambdaCall(lambda: Local, args: List[Tree], block: Block, next: Option[Label], res: Local,
      argType: Type, retType: Type)(implicit lh: LocalHandler, f: Function, fh: FunctionHandler): Block = {

        def applyOneArg(arg: Tree, res: Local, argType: Type, retType: Type): Block = {

          val argLocal = lh.freshLocal("arg")
          val prepArg = preAllocate(argLocal, argType)
          val (currentBlock, phi) = codegen(arg, block <:> prepArg, None, Some(argLocal), argType)

          val (funLocal, loadFun) = getLambdaFunction(lambda, argType, retType)
          val (envLocal, loadEnv) = getLambdaEnv(lambda, argType, retType)

          val prep = currentBlock <:> phi <:> loadFun <:> loadEnv
          prep <:> CallLambda(res, funLocal, Value(argLocal), argType, envLocal, retType)
        }

        args match {
          case arg :: Nil =>  {
            // val res = assignee(toAssign)
            val currentBlock = applyOneArg(arg, res, argType, retType) <:> jumpTo(next)
            currentBlock
          }

          case arg :: rest => {
            val tempRes = lh.freshLocal("nested.lambda")
            val currentBlock = applyOneArg(arg, tempRes, argType, retType)
            val (nextArgType, nextRetType) = getLambdaPrototype(retType)
            cgLambdaCall(tempRes, rest, currentBlock, next, res, nextArgType, nextRetType)
          }

          case Nil => { //TODO
            // val res = assignee(toAssign)
            // block <:> Assign(res, LambdaValue(argType, retType), lambda) <:> jumpTo(next)

            block <:> jumpTo(next)
            //rc.reporter.fatalError("Cannot apply a lambda to nothing")
          }
        }
    }

    def cgEitherChoose(either: Local, eitherType: Type, chooseLeft: Label, chooseRight: Label)
      (implicit lh: LocalHandler): List[Instruction] = {
      val eitherTypePtr = lh.dot(either, "type.gep")
      val eitherCond = lh.dot(either, "type")

      val choose = List(
        GepToIdx(eitherTypePtr, eitherType, Value(either), Some(0)),
        Load(eitherCond, BooleanType, eitherTypePtr),
        Branch(Value(eitherCond), chooseRight, chooseLeft)  //false => left
      )
      choose
    }

    def cgAlternatives(trueLabel: Label, trueBody: Tree, falseLabel: Label, falseBody: Tree,
      after: Label, toAssign: Option[Local], resultType: Type)
      (implicit lh: LocalHandler, f: Function, fh: FunctionHandler): List[Instruction] = {
        val assign = assignee(toAssign)

        val (trueLocal, falseLocal) = resultType match {
          case EitherType(_, _) => (assign, assign)
          case _ => (lh.freshLocal("phi"), lh.freshLocal("phi"))
        }

        val tBlock = lh.newBlock(trueLabel)
        val (trueBlock, truePhi) = codegen(trueBody, tBlock, Some(after), Some(trueLocal), resultType)
        f.add(trueBlock)

        val fBlock = lh.newBlock(falseLabel)
        val (falseBlock, falsePhi) = codegen(falseBody, fBlock, Some(after), Some(falseLocal), resultType)
        f.add(falseBlock)

        val nextPhi = resultType match {
          case EitherType(_, _) => Nil
          case _ => List(Phi(assign, resultType, List((trueLocal, trueBlock.label), (falseLocal, falseBlock.label))))
        }

        truePhi ++ falsePhi ++ nextPhi
    }

    def cgLambda(body: Tree, argId: Identifier, freeVariables: List[Identifier], freeTypes: List[Type], resultType: Type)
      (implicit lh: LocalHandler, fh: FunctionHandler): Global = {

      //Environment receiving ==============================================
      val lambdaLH = new LocalHandler(rc)
      val rawEnv = lambdaLH.freshLocal("raw.env")
      val capturedEnv = Local("env") //lambdaLH.freshLocal("env") //TODO
      val envType = EnvironmentType(freeTypes)

      val translateEnv = Bitcast(capturedEnv, rawEnv, envType)

      //Load captured variables from the enviroment into locals
      val loadFromEnv = freeVariables.zipWithIndex.flatMap{
        case (freeVar, index) => {
          val capturedType = lh.getType(freeVar)
          val capturedLocal = lambdaLH.dot(capturedEnv, s"at$index")
          val capturedLocalGep = lambdaLH.dot(capturedLocal, "gep")

          lambdaLH.add(freeVar, ParamDef(capturedType, capturedLocal))

          List(
            GepToIdx(capturedLocalGep, envType, Value(capturedEnv), Some(index)),
            Load(capturedLocal, capturedType, capturedLocalGep))
        }
      }

      //Codgen the lambda body
      val (lambdaArgType, lambdaRetType) = getLambdaPrototype(resultType)

      val envDef = ParamDef(RawEnvType, rawEnv)
      val argDef = ParamDef(lambdaArgType, lambdaLH.freshLocal(argId))
      lambdaLH.add(argId, argDef)

      val lambdaName = Global(fh.nextLambda(fh.lambdaToConvert)) //TODO use a name according to top-level name (i.e. makeAdder#1)
      val emptyLambda = Function(lambdaRetType, lambdaName, List(argDef, envDef))
      fh.addLambda(fh.lambdaToConvert, emptyLambda)
      fh.lambdaToConvert = null

      val firstInstr = if(freeVariables.isEmpty) Nil else translateEnv +: loadFromEnv
      val completeLambda = cgFunction(emptyLambda, lambdaLH, body, fh, firstInstr)

      lambdaName
    }

    def getLeft(either: Local, tpe: EitherType)(implicit lh: LocalHandler): (Local, List[Instruction]) = {
      val eitherLeftPtr = lh.dot(either, "left.gep")
      val leftLocal = lh.dot(either, "left")
      val prepLeft = List(
        GepToIdx(eitherLeftPtr, tpe, Value(either), Some(1)),
        Load(leftLocal, tpe.leftType, eitherLeftPtr))
        (leftLocal, prepLeft)
    }

    def getRight(either: Local, tpe: EitherType)(implicit lh: LocalHandler): (Local, List[Instruction]) = {
      val eitherRightPtr = lh.dot(either, "right.gep")
      val rightLocal = lh.dot(either, "right")
      val prepRight = List(
        GepToIdx(eitherRightPtr, tpe, Value(either), Some(2)),
        Load(rightLocal, tpe.rightType, eitherRightPtr))
        (rightLocal, prepRight)
    }

    def getLambdaPrototype(tpe: Type) = tpe match {
      case LambdaValue(argType, retType) => (argType, retType)
      case other => rc.reporter.fatalError(s"Type $tpe isn't a lambda")
    }

    def getLambdaFunction(lambda: Local, argType: Type, retType: Type)
      (implicit lh: LocalHandler): (Local, List[Instruction]) = {
        val funPtr = lh.dot(lambda, "function.gep")
        val fun = lh.dot(lambda, "function")
        val loadFun = List(
          GepToIdx(funPtr, LambdaValue(argType, retType), Value(lambda), Some(0)),
          Load(fun, FunctionType(argType, retType), funPtr)
        )
        (fun, loadFun)
    }

    def getLambdaEnv(lambda: Local, argType: Type, retType: Type)(implicit lh: LocalHandler): (Local, List[Instruction]) = {
      val envPtr = lh.dot(lambda, "env.gep")
      val env = lh.dot(lambda, "env")
      val loadEnv = List(
        GepToIdx(envPtr, LambdaValue(argType, retType), Value(lambda), Some(1)),
        Load(env, RawEnvType, envPtr)
      )
      (env, loadEnv)
    }

    def setLambdaFunction(lambdaToSet: Local, function: Value, argType: Type, retType: Type)
      (implicit lh: LocalHandler): List[Instruction] = {
        val funPtr = lh.dot(lambdaToSet, "function.gep")
        val storeFun = List(
          GepToIdx(funPtr, LambdaValue(argType, retType), Value(lambdaToSet), Some(0)),
          Store(function, FunctionType(argType, retType), funPtr)
        )
        storeFun
    }

    def setLambdaEnv(lambdaToSet: Local, env: Value, argType: Type, retType: Type)
      (implicit lh: LocalHandler): List[Instruction] = {
        val envPtr = lh.dot(lambdaToSet, "env.gep")
        val storeEnv = List(
          GepToIdx(envPtr, LambdaValue(argType, retType), Value(lambdaToSet), Some(1)),
          Store(env, RawEnvType, envPtr)
        )
        storeEnv
    }

    def preAllocate(local: Local, tpe: Type)(implicit lh: LocalHandler): List[Instruction] = tpe match {
      case EitherType(_, _) => List(Malloc(local, lh.dot(local, "malloc"), tpe))
      case _ => Nil
    }

    def prepEnvironment(freeVariables: List[Identifier], freeTypes: List[Type], assign: Local)
      (implicit lh: LocalHandler): (List[Instruction], Value) = {

      val envType = EnvironmentType(freeTypes)

      //Environment passing ================================================
      if(freeVariables.isEmpty){
        (Nil, Value(NullLiteral))
      } else {
        //Create enviroment
        val env = lh.dot(assign, "env")
        val rawEnv = lh.dot(env, "malloc")
        val mallocEnv = Malloc(env, rawEnv, envType)

        //Store free variables into the environment
        val storeIntoEnv = freeVariables.zipWithIndex.flatMap{
          case (freeVar, index) => {
            val freeType = lh.getType(freeVar)
            val freeLocal = lh.getLocal(freeVar)
            val capturedLocalGep = lh.dot(env, s"at$index.gep")

            List(
              GepToIdx(capturedLocalGep, envType, Value(env), Some(index)),
              Store(Value(freeLocal), freeType, capturedLocalGep)
            )
          }
        }

        (mallocEnv +: storeIntoEnv, Value(rawEnv))
      }
    }

    def filterErasable(t: Tree): Tree = {
      Tree.traversePost(t, findErasable)
      t
    }
    def findErasable(t:Tree): Unit = t match {
      case
        MacroTypeDecl(_, _) |
        MacroTypeInst(_, _) |
        ErasableApp(_, _) |
        Refl(_, _) |
        Fold(_, _) |
        Unfold(_, _) |
        UnfoldPositive(_, _) |
        DefFunction(_, _, _, _, _) |
        ErasableLambda(_, _) |
        Abs(_) |
        TypeApp(_, _) |
        Because(_, _) => rc.reporter.fatalError(s"This tree should have been erased: $t")

      case _ => ()
    }

    def assignee(toAssign: Option[Local])(implicit lh: LocalHandler) = toAssign getOrElse lh.freshLocal
    def jumpTo(next: Option[Label]) = next.toList.map(label => Jump(label))

    def codegen(inputTree: Tree, block: Block, next: Option[Label], toAssign: Option[Local], resultType: Type)
      (implicit lh: LocalHandler, f: Function, fh: FunctionHandler): (Block, List[Instruction]) =

      inputTree match {

        case value if isValue(value) => (block <:> cgValue(resultType, translateValue(value), next, toAssign), Nil)

        case call @ App(recApp, arg) =>
        // {
        //   val (id, flatArgs) = flattenApp(call)
        //
        //   if(fh.hasFunction(id)){ //Top level function
        //     val (topLevelArgs, lambdaArgs) = flatArgs.splitAt(fh.getArgNumber(id))
        //
        //     if(lambdaArgs.isEmpty){
        //
        //       //Top level function returns a value that isn't applied to anything
        //       cgTopLevelFunCall(id, topLevelArgs, block, next, toAssign)
        //     } else {
        //
        //       //Top level function returns a lambda which is then applied
        //       val lambda = lh.freshLocal("lambda")
        //
        //       val (currentBlock, phi) = cgTopLevelFunCall(id, topLevelArgs, block, None, Some(lambda))
        //
        //       val (argType, retType) = getLambdaPrototype(fh.getReturnType(funId))
        //       val lambdaCall = cgLambdaCall(lambda, lambdaArgs, currentBlock <:> phi, next, toAssign, argType, retType)
        //       (lambdaCall, Nil)
        //     }
        //
        //   // } else if(fh.hasLambda(id) && (fh.getLambda(id).name == f.name)){  //(recursive) top level lambda
        //   //   cgTopLevelFunCall(id, List(), block, next, toAssign)
        //   } else {  //Lambda variable
        //     val (argType, retType) = getLambdaPrototype(lh.getType(funId))
        //     val lambdaCall = cgLambdaCall(lh.getLocal(funId), flatArgs, block, next, toAssign, argType, retType)
        //     (lambdaCall, Nil)
        //   }
        // }
        ////////////////////////////////////////////////////////////////////////////////////
        {
          val (id, flatArgs) = flattenApp(call)
          val result = assignee(toAssign)

          val isTopLevelFunction = fh.hasFunction(id)
          val isRecursiveLambdaCall = fh.hasLambda(id) && (fh.get(id).name == f.name)

          if(isTopLevelFunction || isRecursiveLambdaCall){  //Top level function or lambda
            val nbConsumedArgs = fh.getArgNumber(id)

            val (functionArgs, lambdaArgs) = flatArgs.splitAt(nbConsumedArgs)

            // val intermediate =
              if(lambdaArgs.isEmpty){
              // Some(result)  //cgLambdaCall wil do nothing
              cgTopLevelFunCall(id, functionArgs, block, None, Some(result))
            } else {
              // Some(lh.freshLocal("intermediate.result"))
              val intermediate = lh.freshLocal("intermediate.result")
              val (currentBlock, phi) = cgTopLevelFunCall(id, functionArgs, block, None, Some(intermediate))

              val (argType, retType) = getLambdaPrototype(fh.getReturnType(id))
              val lambdaCall = cgLambdaCall(intermediate, lambdaArgs, currentBlock <:> phi, next, result, argType, retType)
              (lambdaCall, Nil)
            }

            // val (currentBlock, phi) = cgTopLevelFunCall(id, functionArgs, block, None, intermediate)
            //
            // val (argType, retType) = getLambdaPrototype(fh.getReturnType(id))
            // val lambdaCall = cgLambdaCall(intermediate.get, lambdaArgs, currentBlock <:> phi, next, result, argType, retType)
            // (lambdaCall, Nil)

          } else {  //Lambda variable
            val (argType, retType) = getLambdaPrototype(lh.getType(id))
            val lambdaCall = cgLambdaCall(lh.getLocal(id), flatArgs, block, next, result, argType, retType)
            (lambdaCall, Nil)
          }
        }
        ////////////////////////////////////////////////////////////////////////////////////
        case Pair(first, second) => {
          val pair = assignee(toAssign)
          val firstLocal = lh.dot(pair, "first")
          val secondLocal = lh.dot(pair, "second")

          val t3 = lh.dot(pair, "malloc")

          val (firstType, secondType) = resultType match {
            case PairType(f, s) => (f, s)
            case other => rc.reporter.fatalError(s"Expected type is $resultType but value has type Pair(_, _)")
          }
          val pairType = PairType(firstType, secondType)

          val malloc = Malloc(pair, t3, pairType) //will assign toAssign

          val (firstBlock, firstPhi) = codegen(first, block <:> malloc, None, Some(firstLocal), firstType)
          val (secondBlock, secondPhi) = codegen(second, firstBlock <:> firstPhi, None, Some(secondLocal), secondType)

          val (firstPtr, secondPtr) = (lh.dot(pair, "first.gep"), lh.dot(pair, "second.gep"))

          val initialise = List(
            GepToIdx(firstPtr, pairType, Value(pair), Some(0)),
            Store(Value(firstLocal), firstType, firstPtr),
            GepToIdx(secondPtr, pairType, Value(pair), Some(1)),
            Store(Value(secondLocal), secondType, secondPtr)
          )

          (secondBlock <:> secondPhi <:> initialise <:> jumpTo(next), Nil)
        }

        case First(pair) => { //TODO refactor first and second since they are identical?
          val pairLocal = lh.freshLocal("pair")
          val pairType = typeOf(pair)(lh, fh, Map.empty)
          val (currentBlock, phi) = codegen(pair, block, None, Some(pairLocal), pairType)

          val assignLocal = assignee(toAssign)
          val firstPtr = lh.dot(assignLocal, "first.gep")

          val prep = List(
            GepToIdx(firstPtr, pairType, Value(pairLocal), Some(0)),
            Load(assignLocal, resultType, firstPtr)
          )

          (currentBlock <:> phi <:> prep <:> jumpTo(next), Nil)
        }

        case Second(pair) => {

          val pairLocal = lh.freshLocal("pair")
          val pairType = typeOf(pair)(lh, fh, Map.empty)
          val (currentBlock, phi) = codegen(pair, block, None, Some(pairLocal), pairType)

          val assignLocal = assignee(toAssign)

          val secondPtr = lh.dot(pairLocal, "second.gep")

          val prep = List(
            GepToIdx(secondPtr, pairType, Value(pairLocal), Some(1)),
            Load(assignLocal, resultType, secondPtr)
          )

          (currentBlock <:> phi <:> prep <:> jumpTo(next), Nil)
        }

        case LetIn(optTpe, valueBody, Bind(newVar, rest)) => {
            val local = lh.freshLocal(newVar)
            val valueTpe = optTpe match {
              case Some(tpe) => translateType(tpe)
              case None => typeOf(valueBody)(lh, fh, Map.empty)
            }

            val allocate = preAllocate(local, valueTpe)

            lh.add(newVar, ParamDef(valueTpe, local))
            fh.lambdaToConvert = newVar
            println(s"Trying to codegen the value of $newVar of type ${lh.getType(newVar)}###########################################")
            val (tempBlock, phi) = codegen(valueBody, block <:> allocate, None, Some(local), valueTpe)
            println(s"I codegened the value of $newVar")

            codegen(rest, tempBlock <:> phi, next, toAssign, resultType)
        }

        case Lambda(opt, Bind(argId, body)) => {
          val assign = assignee(toAssign)

          val freeVariables: List[Identifier] = extractFreeVariables(body)(List(argId))
          val freeTypes: List[Type] = freeVariables.map(freeVar => lh.getType(freeVar))

          val (prepEnv, envToPass) = prepEnvironment(freeVariables, freeTypes, assign)
          val convertedLambda = cgLambda(body, argId, freeVariables, freeTypes, resultType)

          val allocateResult = List(Malloc(assign, lh.dot(assign, "malloc"), resultType))

          val (argType, retType) = getLambdaPrototype(resultType)
          val storeFun = setLambdaFunction(assign, Value(convertedLambda), argType, retType)
          val storeEnv = setLambdaEnv(assign, envToPass, argType, retType)

          (block <:> allocateResult <:> prepEnv <:> storeFun <:> storeEnv <:> jumpTo(next), Nil)
        }

        case IfThenElse(cond, thenn, elze) => {

          val condLocal = lh.freshLocal("cond")

          val trueLabel = lh.dot(block.label, "then")
          val falseLabel = lh.dot(block.label, "else")
          val afterLabel = lh.dot(block.label, "after")

          val (condPrep, condPhi) = codegen(cond, block, None, Some(condLocal), BooleanType)

          val parentBlock =
            condPrep <:>
            condPhi <:>
            Branch(Value(condLocal), trueLabel, falseLabel)

          f.add(parentBlock)

          val afterPhi = cgAlternatives(trueLabel, thenn, falseLabel, elze, afterLabel, toAssign, resultType)

          val jump = jumpTo(next)
          (lh.newBlock(afterLabel) <:> afterPhi <:> jump, Nil)
        }

        case Primitive(op, args) => {
          val init: (Block, List[Value]) = (block, Nil)
          val (currentBlock, values) = args.map((_, lh.freshLocal("temp"))).foldLeft(init){
            case ((currentBlock, values), (arg, temp)) => {
              val argType = typeOfOperand(op)
              val (nextBlock, phi) = codegen(arg, currentBlock, None, Some(temp), argType)
              (nextBlock <:> phi, values :+ Value(temp))
            }
          }

          val jump = jumpTo(next)

          val operation = values match {
            case List(operand) => UnaryOp(translateOp(op), assignee(toAssign), operand)
            case List(leftOp, rightOp) => BinaryOp(translateOp(op), assignee(toAssign), leftOp, rightOp)
            case other => rc.reporter.fatalError(s"Unexpected number of arguments for operator $op. Expected 1 or 2 but was ${other.size}")
          }

          (currentBlock <:> operation <:> jump, Nil)
        }

        case EitherMatch(scrut, t1, t2) => {
          val (varLeft, bodyLeft) = extractBody(t1)
          val (varRight, bodyRight) = extractBody(t2)

          val scrutLocal = lh.freshLocal("scrut")
          val scrutType = typeOf(scrut)(lh, fh, Map.empty)
          val (currentBlock, scrutPhi) = codegen(scrut, block, None, Some(scrutLocal), scrutType)

          scrutType match {
            case LeftType(innerType) => {
              lh.add(varLeft, ParamDef(innerType, scrutLocal))
              codegen(bodyLeft, currentBlock <:> scrutPhi, next, toAssign, resultType)
            }

            case RightType(innerType) => {
              lh.add(varRight, ParamDef(innerType, scrutLocal))
              codegen(bodyRight, currentBlock <:> scrutPhi, next, toAssign, resultType)
            }

            case tpe @ EitherType(leftType, rightType) => {
              val leftLabel = lh.dot(block.label, "match.left")
              val rightLabel = lh.dot(block.label, "match.right")
              val afterLabel = lh.dot(block.label, "after")

              val (leftLocal, prepLeft) = getLeft(scrutLocal, tpe)
              lh.add(varLeft, ParamDef(leftType, leftLocal))

              val (rightLocal, prepRight) = getRight(scrutLocal, tpe)
              lh.add(varRight, ParamDef(rightType, rightLocal))

              val choose = cgEitherChoose(scrutLocal, tpe, leftLabel, rightLabel)
              f.add(currentBlock <:> scrutPhi <:> prepLeft <:> prepRight <:> choose)

              val afterPhi = cgAlternatives(leftLabel, bodyLeft, rightLabel, bodyRight, afterLabel, toAssign, resultType)

              (lh.newBlock(afterLabel) <:> afterPhi <:> jumpTo(next), Nil)
            }
          }
        }

        case LeftTree(either) => {

          val assignLocal = assignee(toAssign)
          val leftLocal = lh.freshLocal("left")

          val eitherValuePtr = lh.dot(assignLocal, "left.value.gep")
          val eitherTypePtr = lh.dot(assignLocal, "left.type.gep")

          val (currentBlock, phi) = (toAssign, resultType) match {
            case (Some(local), EitherType(leftType, _)) => {

              val assign = List(
                GepToIdx(eitherTypePtr, resultType, Value(assignLocal), Some(0)),
                Store(Value(IRBooleanLiteral(false)), BooleanType, eitherTypePtr),
                GepToIdx(eitherValuePtr, resultType, Value(assignLocal), Some(1)),
                Store(Value(leftLocal), leftType, eitherValuePtr))

              val (prepBlock, p) = codegen(either, block, None, Some(leftLocal), leftType)
              (prepBlock <:> p <:> assign, Nil)
            }

            case (Some(local), LeftType(innerType)) => codegen(either, block, None, Some(local), innerType)
            //case (None, _) => codegen(either, block, None, None, resultType)
            case (opt, other) => rc.reporter.fatalError(s"Found LeftTree but expected $other when assigning $opt")
            //TODO malloc a temp variable when there is nothing to assign?
          }

          (currentBlock <:> phi <:> jumpTo(next), Nil)
        }

        case RightTree(either) => {

          val assignLocal = assignee(toAssign)
          val rightLocal = lh.freshLocal("right")

          val eitherValuePtr = lh.dot(assignLocal, "right.value.gep")
          val eitherTypePtr = lh.dot(assignLocal, "right.type.gep")

          val (currentBlock, phi) = (toAssign, resultType) match {
            case (Some(local), EitherType(_, rightType)) => {

              val assign = List(
                GepToIdx(eitherTypePtr, resultType, Value(assignLocal), Some(0)),
                Store(Value(IRBooleanLiteral(true)), BooleanType, eitherTypePtr),
                GepToIdx(eitherValuePtr, resultType, Value(assignLocal), Some(2)),
                Store(Value(rightLocal), rightType, eitherValuePtr))

              val (prepBlock, p) = codegen(either, block, None, Some(rightLocal), rightType)
              (prepBlock <:> p <:> assign, Nil)
            }

            case (Some(local), RightType(innerType)) => codegen(either, block, None, Some(local), innerType)
            // case (None, _) => codegen(either, block, None, None, resultType)
            case (opt, other) => rc.reporter.fatalError(s"Found RightTree but expected $other when assigning $opt")
            //TODO malloc a temp variable when there is nothing to assign?
          }

          (currentBlock <:> phi <:> jumpTo(next), Nil)
        }
        case _ => rc.reporter.fatalError(s"codegen not implemented for $inputTree")
    }
}
