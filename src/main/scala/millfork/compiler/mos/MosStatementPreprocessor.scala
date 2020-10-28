package millfork.compiler.mos

import millfork.compiler.{AbstractStatementPreprocessor, CompilationContext}
import millfork.env.{NumericConstant, Variable}
import millfork.error.ConsoleLogger
import millfork.node._

/**
  * @author Karol Stasiak
  */
class MosStatementPreprocessor(ctx: CompilationContext, statements: List[ExecutableStatement]) extends AbstractStatementPreprocessor(ctx, statements) {

  def maybeOptimizeForStatement(f: ForStatement): Option[(ExecutableStatement, VV)] = {
    if (f.extraIncrement.isEmpty && optimize && !f.variable.contains(".") && env.get[Variable](f.variable).typ.size == 2) {
      (env.eval(f.start), env.eval(f.end)) match {
        case (Some(NumericConstant(s, _)), Some(NumericConstant(e, _))) if (s & 0xffff) == s && (e & 0xffff) == e =>
          f.direction match {
            case ForDirection.Until | ForDirection.ParallelUntil =>
              if (s.&(0xff) == 0 && e.&(0xff) == 0) {
                ctx.log.debug(s"Loop across whole memory pages", f.position)
                Some(ForStatement(
                  f.variable + ".hi",
                  FunctionCallExpression("hi", List(f.start)).pos(f.start.position, f.start.endPosition),
                  FunctionCallExpression("hi", List(f.end)).pos(f.end.position, f.end.endPosition),
                  f.direction,
                  List(
                    ForStatement(
                      f.variable + ".lo",
                      LiteralExpression(0, 1).pos(f.start.position, f.start.endPosition),
                      LiteralExpression(256, 2).pos(f.end.position, f.end.endPosition),
                      f.direction,
                      f.body
                    ).pos(f.position, f.endPosition))
                ).pos(f.position, f.endPosition) -> Map())
              } else None
            case ForDirection.To | ForDirection.ParallelTo =>
              if (s.&(0xff) == 0 && e.&(0xff) == 0xff) {
                ctx.log.debug(s"Loop across whole memory pages", f.position)
                Some(ForStatement(
                  f.variable + ".hi",
                  FunctionCallExpression("hi", List(f.start)).pos(f.start.position, f.start.endPosition),
                  FunctionCallExpression("hi", List(f.end)).pos(f.end.position, f.end.endPosition),
                  f.direction,
                  List(
                    ForStatement(
                      f.variable + ".lo",
                      LiteralExpression(0, 1).pos(f.start.position, f.start.endPosition),
                      LiteralExpression(255, 1).pos(f.end.position, f.end.endPosition),
                      f.direction,
                      f.body
                    ).pos(f.position, f.endPosition))
                ).pos(f.position, f.endPosition) -> Map())
              } else None
            case ForDirection.DownTo | ForDirection.ParallelTo =>
              if (s.&(0xff) == 0xff && e.&(0xff) == 0) {
                ctx.log.debug(s"Loop across whole memory pages", f.position)
                Some(ForStatement(
                  f.variable + ".hi",
                  FunctionCallExpression("hi", List(f.start)).pos(f.start.position, f.start.endPosition),
                  FunctionCallExpression("hi", List(f.end)).pos(f.end.position, f.end.endPosition),
                  f.direction,
                  List(
                    ForStatement(
                      f.variable + ".lo",
                      LiteralExpression(255, 1).pos(f.start.position, f.start.endPosition),
                      LiteralExpression(0, 1).pos(f.end.position, f.end.endPosition),
                      f.direction,
                      f.body
                    ).pos(f.position, f.endPosition))
                ).pos(f.position, f.endPosition) -> Map())
              } else None
            case _ => None
          }
        case _ => None
      }
    } else None
  }

}
