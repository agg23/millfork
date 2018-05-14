package millfork.node.opt

import millfork.{CompilationFlag, CompilationOptions}
import millfork.env._
import millfork.error.ErrorReporting
import millfork.node._

/**
  * @author Karol Stasiak
  */
object UnusedFunctions extends NodeOptimization {

  override def optimize(nodes: List[Node], options: CompilationOptions): List[Node] = {
    val panicRequired = options.flags(CompilationFlag.CheckIndexOutOfBounds)
    val allNormalFunctions = nodes.flatMap {
      case v: FunctionDeclarationStatement => if (v.address.isDefined || v.interrupt || v.name == "main" || panicRequired && v.name == "_panic") Nil else List(v.name)
      case _ => Nil
    }.toSet
    val allCalledFunctions = getAllCalledFunctions(nodes).toSet
    var unusedFunctions = allNormalFunctions -- allCalledFunctions
    if (allCalledFunctions.contains("*") && options.flag(CompilationFlag.ZeropagePseudoregister)) {
      unusedFunctions -= "__mul_u8u8u8"
    }
    if (unusedFunctions.nonEmpty) {
      ErrorReporting.debug("Removing unused functions: " + unusedFunctions.mkString(", "))
      optimize(removeFunctionsFromProgram(nodes, unusedFunctions), options)
    } else {
      nodes
    }
  }

  private def removeFunctionsFromProgram(nodes: List[Node], unusedFunctions: Set[String]): List[Node] = {
    nodes match {
      case (x: FunctionDeclarationStatement) :: xs if unusedFunctions(x.name) =>
        removeFunctionsFromProgram(xs, unusedFunctions)
      case x :: xs =>
        x :: removeFunctionsFromProgram(xs, unusedFunctions)
      case Nil =>
        Nil
    }
  }

  def getAllCalledFunctions(c: Constant): List[String] = c match {
    case SubbyteConstant(cc, _) => getAllCalledFunctions(cc)
    case CompoundConstant(_, l, r) => getAllCalledFunctions(l) ++ getAllCalledFunctions(r)
    case MemoryAddressConstant(th) => List(
      th.name,
      th.name.stripSuffix(".addr"),
      th.name.stripSuffix(".hi"),
      th.name.stripSuffix(".lo"),
      th.name.stripSuffix(".addr.lo"),
      th.name.stripSuffix(".addr.hi"))
    case _ => Nil
  }

  def getAllCalledFunctions(expressions: List[Node]): List[String] = expressions.flatMap {
    case s: VariableDeclarationStatement => getAllCalledFunctions(s.address.toList) ++ getAllCalledFunctions(s.initialValue.toList)
    case s: ArrayDeclarationStatement => getAllCalledFunctions(s.address.toList) ++ getAllCalledFunctions(s.elements.toList)
    case s: ArrayContents => getAllCalledFunctions(s.getAllExpressions)
    case s: FunctionDeclarationStatement => getAllCalledFunctions(s.address.toList) ++ getAllCalledFunctions(s.statements.getOrElse(Nil))
    case Assignment(VariableExpression(_), expr) => getAllCalledFunctions(expr :: Nil)
    case s: ReturnDispatchStatement =>
      getAllCalledFunctions(s.getAllExpressions) ++ getAllCalledFunctions(s.branches.map(_.function))
    case s: Statement => getAllCalledFunctions(s.getAllExpressions)
    case s: VariableExpression => List(
          s.name,
          s.name.stripSuffix(".addr"),
          s.name.stripSuffix(".hi"),
          s.name.stripSuffix(".lo"),
          s.name.stripSuffix(".addr.lo"),
          s.name.stripSuffix(".addr.hi"))
    case s: LiteralExpression => Nil
    case HalfWordExpression(param, _) => getAllCalledFunctions(param :: Nil)
    case SumExpression(xs, _) => getAllCalledFunctions(xs.map(_._2))
    case FunctionCallExpression(name, xs) => name :: getAllCalledFunctions(xs)
    case IndexedExpression(arr, index) => arr :: getAllCalledFunctions(List(index))
    case SeparateBytesExpression(h, l) => getAllCalledFunctions(List(h, l))
    case _ => Nil
  }

}
