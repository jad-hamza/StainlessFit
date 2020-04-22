// package stainlessfit
// package core
// package codegen.utils
//
// import core.codegen.llvm.IR.{UnitType => IRUnitType, NatType => IRNatType, _}
// import core.util.RunContext
//
// object ResultPrinter {
//
//   def apply(result: Local, tpe: Type, lh: LocalHandler, rc: RunContext): List[Instruction] = {
//
//     def customPrint(toPrint: Local, tpe: Type, flattenPairs: Boolean, nestedEither: Boolean): (List[Instruction], List[Instruction]) = tpe match {
//       case PairType(firstType, secondType) => {
//
//         val (firstLocal, secondLocal) = (lh.dot(toPrint, "first"), lh.dot(toPrint, "second"))
//
//         val (firstPtr, secondPtr) = (lh.dot(firstLocal, "gep"), lh.dot(secondLocal, "gep"))
//
//         val prepPair = List(
//           GepToIdx(firstPtr, tpe, Value(toPrint), Some(0)),
//           Load(firstLocal, firstType, firstPtr),
//           NoOp,
//           GepToIdx(secondPtr, tpe, Value(toPrint), Some(1)),
//           Load(secondLocal, secondType, secondPtr),
//           NoOp
//         )
//
//         val (prepFirst, printFirst) = customPrint(firstLocal, firstType, false, false)
//         val (prepSecond, printSecond) = customPrint(secondLocal, secondType, true, false)
//
//         val (open, close) = if(flattenPairs) (Nil, Nil) else (List(PrintOpen), List(PrintClose))
//         (prepPair ++ prepFirst ++ prepSecond, open ++ printFirst ++ List(PrintComma) ++ printSecond ++ close)
//       }
//
//       case BooleanType | IRUnitType => (Nil, List(PrintBool(toPrint)))
//
//       case IRNatType => (Nil, List(PrintNat(Value(toPrint))))
//
//       case EitherType(leftType, rightType) => {
//         // //Dynamically choose between Left and Right
//         // val eitherTypePtr = lh.dot(toPrint, "type.gep")
//         // val eitherCond = lh.dot(toPrint, "type")
//         //
//         // val printLeft = lh.freshLabel("left")
//         // val printRight = lh.freshLabel("right")
//         //
//         // val eitherChoose = List(
//         //   GepToIdx(eitherTypePtr, tpe, Value(toPrint), Some(0)),
//         //   Load(eitherCond, BooleanType, eitherTypePtr),
//         //   Branch(Value(eitherCond), printRight, printLeft)  //false => left
//         // )
//         // f.add(block <:> eitherChoose)
//         //
//         // //If the value is a LeftTree
//         // val printLeftBlock = lh.newBlock(printLeft)
//         // val eitherLeftPtr = lh.dot(toPrint, "left.gep")
//         // val leftLocal = lh.dot(toPrint, "left")
//         // val prepLeft = List(
//         //   GepToIdx(eitherLeftPtr, tpe, Value(toPrint), Some(1)),
//         //   Load(leftLocal, leftType, eitherLeftPtr)
//         // )
//         // val printLeftValue = customPrint(leftLocal, leftType, false, false)
//         //
//         // val (open, close) = if(nestedEither) (List(PrintOpen), List(PrintClose)) else (Nil, Nil)
//         //
//         // PrintLeft +: open ++ printLeftValue ++ close
//         ???
//       }
//
//       case other => rc.reporter.fatalError(s"Pretty printing not implemented for $other yet")
//       //TODO add case for Left and Right type
//     }
//
//     val (prep, print) = tpe match {
//       case PairType(_, _) => customPrint(result, tpe, false, false)
//       case LeftType(_) | RightType(_) => customPrint(result, tpe, false, true)
//       case _ => customPrint(result, tpe, false, false)
//     }
//     prep ++ print
//   }
// }
