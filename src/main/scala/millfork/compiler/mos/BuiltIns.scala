package millfork.compiler.mos

import millfork.CompilationFlag
import millfork.assembly.mos.AddrMode._
import millfork.assembly.mos.Opcode._
import millfork.assembly.mos._
import millfork.compiler._
import millfork.env._
import millfork.error.ErrorReporting
import millfork.node._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * @author Karol Stasiak
  */
//noinspection RedundantDefaultArgument
object BuiltIns {

  object IndexChoice extends Enumeration {
    val RequireX, PreferX, PreferY = Value
  }

  def wrapInSedCldIfNeeded(decimal: Boolean, code: List[AssemblyLine]): List[AssemblyLine] = {
    if (decimal) {
      AssemblyLine.implied(SED) :: (code :+ AssemblyLine.implied(CLD))
    } else {
      code
    }
  }

  def staTo(op: Opcode.Value, l: List[AssemblyLine]): List[AssemblyLine] = l.map(x => if (x.opcode == STA) x.copy(opcode = op) else x)

  def cmpTo(op: Opcode.Value, l: List[AssemblyLine]): List[AssemblyLine] = l.map(x => if (x.opcode == CMP) x.copy(opcode = op) else x)

  def ldTo(op: Opcode.Value, l: List[AssemblyLine]): List[AssemblyLine] = l.map(x => if (x.opcode == LDA || x.opcode == LDX || x.opcode == LDY) x.copy(opcode = op) else x)

  def simpleOperation(opcode: Opcode.Value, ctx: CompilationContext, source: Expression, indexChoice: IndexChoice.Value, preserveA: Boolean, commutative: Boolean, decimal: Boolean = false): List[AssemblyLine] = {
    val env = ctx.env
    val parts: (List[AssemblyLine], List[AssemblyLine]) = env.eval(source).fold {
      val b = env.get[Type]("byte")
      source match {
        case VariableExpression(name) =>
          val v = env.get[Variable](name)
          if (v.typ.size > 1) {
            ErrorReporting.error(s"Variable `$name` is too big for a built-in operation", source.position)
            return Nil
          }
          Nil -> AssemblyLine.variable(ctx, opcode, v)
        case IndexedExpression(arrayName, index) =>
          val pointy = env.getPointy(arrayName)
          AbstractExpressionCompiler.checkIndexType(ctx, pointy, index)
          val (variablePart, constantPart) = env.evalVariableAndConstantSubParts(index)
          val indexerSize = variablePart.map(v => getIndexerSize(ctx, v)).getOrElse(1)
          val totalIndexSize = getIndexerSize(ctx, index)
          (pointy, totalIndexSize, indexerSize, indexChoice, variablePart) match {
            case (p: ConstantPointy, _, _, _, None) =>
              Nil -> List(AssemblyLine.absolute(opcode, p.value + constantPart))
            case (p: ConstantPointy, _, 1, IndexChoice.RequireX | IndexChoice.PreferX, Some(v)) =>
              MosExpressionCompiler.compile(ctx, v, Some(b -> RegisterVariable(MosRegister.X, pointy.indexType)), NoBranching) -> List(AssemblyLine.absoluteX(opcode, p.value + constantPart))
            case (p: ConstantPointy, _, 1, IndexChoice.PreferY, Some(v)) =>
              MosExpressionCompiler.compile(ctx, v, Some(b -> RegisterVariable(MosRegister.Y, pointy.indexType)), NoBranching) -> List(AssemblyLine.absoluteY(opcode, p.value + constantPart))
            case (p: VariablePointy, 0 | 1, _, IndexChoice.PreferX | IndexChoice.PreferY, _) =>
              MosExpressionCompiler.compile(ctx, index, Some(b -> RegisterVariable(MosRegister.Y, pointy.indexType)), NoBranching) -> List(AssemblyLine.indexedY(opcode, p.addr))
            case (p: ConstantPointy, _, 2, IndexChoice.PreferX | IndexChoice.PreferY, Some(v)) =>
              MosExpressionCompiler.prepareWordIndexing(ctx, p, index) -> List(AssemblyLine.indexedY(opcode, env.get[VariableInMemory]("__reg")))
            case (p: VariablePointy, 2, _, IndexChoice.PreferX | IndexChoice.PreferY, _) =>
              MosExpressionCompiler.prepareWordIndexing(ctx, p, index) -> List(AssemblyLine.indexedY(opcode, env.get[VariableInMemory]("__reg")))
            case _ =>
              ErrorReporting.error("Invalid index for simple operation argument", index.position)
              Nil -> Nil
          }
        case FunctionCallExpression(name, List(param)) if env.maybeGet[Type](name).isDefined =>
          return simpleOperation(opcode, ctx, param, indexChoice, preserveA, commutative, decimal)
        case _: FunctionCallExpression | _:SumExpression if commutative =>
          // TODO: is it ok?
          if (ctx.options.flag(CompilationFlag.EmitEmulation65816Opcodes)) {
            return List(AssemblyLine.implied(PHA)) ++ MosExpressionCompiler.compile(ctx.addStack(1), source, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching) ++ wrapInSedCldIfNeeded(decimal, List(
              AssemblyLine.stackRelative(opcode, 1),
              AssemblyLine.implied(PHX)))
          } else {
            return List(AssemblyLine.implied(PHA)) ++ MosExpressionCompiler.compile(ctx.addStack(1), source, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching) ++ wrapInSedCldIfNeeded(decimal, List(
              AssemblyLine.implied(TSX),
              AssemblyLine.absoluteX(opcode, 0x101),
              AssemblyLine.implied(INX),
              AssemblyLine.implied(TXS))) // this TXS is fine, it won't appear in 65816 code
          }
        case _ =>
          ErrorReporting.error("Right-hand-side expression is too complex", source.position)
          return Nil
      }
    } {
      const =>
        if (const.requiredSize > 1) {
          ErrorReporting.error("Constant too big for a built-in operation", source.position)
        }
        Nil -> List(AssemblyLine.immediate(opcode, const))
    }
    val preparations = parts._1
    val finalRead = wrapInSedCldIfNeeded(decimal, parts._2)
    if (preserveA && AssemblyLine.treatment(preparations, State.A) != Treatment.Unchanged) {
      AssemblyLine.implied(PHA) :: (MosExpressionCompiler.fixTsx(preparations) ++ (AssemblyLine.implied(PLA) :: finalRead))
    } else {
      preparations ++ finalRead
    }
  }

  def insertBeforeLast(item: AssemblyLine, list: List[AssemblyLine]): List[AssemblyLine] = list match {
    case Nil => Nil
    case last :: cld :: Nil if cld.opcode == CLD => item :: last :: cld :: Nil
    case last :: cld :: dex :: txs :: Nil if cld.opcode == CLD && dex.opcode == DEX && txs.opcode == TXS => item :: last :: cld :: dex :: txs :: Nil
    case last :: cld :: inx :: txs :: Nil if cld.opcode == CLD && inx.opcode == INX && txs.opcode == TXS => item :: last :: cld :: inx :: txs :: Nil
    case last :: dex :: txs :: Nil if dex.opcode == DEX && txs.opcode == TXS => item :: last :: dex :: txs :: Nil
    case last :: inx :: txs :: Nil if inx.opcode == INX && txs.opcode == TXS => item :: last :: inx :: txs :: Nil
    case last :: Nil => item :: last :: Nil
    case first :: rest => first :: insertBeforeLast(item, rest)
  }

  def compileAddition(ctx: CompilationContext, params: List[(Boolean, Expression)], decimal: Boolean): List[AssemblyLine] = {
    if (decimal && !ctx.options.flag(CompilationFlag.DecimalMode)) {
      ErrorReporting.warn("Unsupported decimal operation", ctx.options, params.head._2.position)
    }
    //    if (params.isEmpty) {
    //      return Nil
    //    }
    val env = ctx.env
    val b = env.get[Type]("byte")
    val sortedParams = params.sortBy { case (subtract, expr) =>
      simplicity(env, expr) + (if (subtract) "X" else "P")
    }
    // TODO: merge constants
    val normalizedParams = sortedParams

    val h = normalizedParams.head
    val firstParamCompiled = MosExpressionCompiler.compile(ctx, h._2, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
    val firstParamSignCompiled = if (h._1) {
      // TODO: check if decimal subtraction works correctly here
      List(AssemblyLine.immediate(EOR, 0xff), AssemblyLine.implied(SEC), AssemblyLine.immediate(ADC, 0))
    } else {
      Nil
    }

    val remainingParamsCompiled = normalizedParams.tail.flatMap { p =>
      if (p._1) {
        insertBeforeLast(AssemblyLine.implied(SEC), simpleOperation(SBC, ctx, p._2, IndexChoice.PreferY, preserveA = true, commutative = false, decimal = decimal))
      } else {
        insertBeforeLast(AssemblyLine.implied(CLC), simpleOperation(ADC, ctx, p._2, IndexChoice.PreferY, preserveA = true, commutative = true, decimal = decimal))
      }
    }
    firstParamCompiled ++ firstParamSignCompiled ++ remainingParamsCompiled
  }

  private def simplicity(env: Environment, expr: Expression): Char = {
    val constPart = env.eval(expr) match {
      case Some(NumericConstant(_, _)) => 'Z'
      case Some(_) => 'Y'
      case None => expr match {
        case VariableExpression(_) => 'V'
        case IndexedExpression(_, LiteralExpression(_, _)) => 'K'
        case IndexedExpression(_, GeneratedConstantExpression(_, _)) => 'K'
        case IndexedExpression(_, expr@VariableExpression(v)) =>
          env.eval(expr) match {
            case Some(_) => 'K'
            case None => env.get[Variable](v).typ.size match {
              case 1 => 'J'
              case _ => 'I'
            }
          }
        case IndexedExpression(_, VariableExpression(v)) if env.get[Variable](v).typ.size == 1 => 'J'
        case IndexedExpression(_, _) => 'I'
        case _ => 'A'
      }
    }
    constPart
  }

  def compileBitOps(opcode: Opcode.Value, ctx: CompilationContext, params: List[Expression]): List[AssemblyLine] = {
    val b = ctx.env.get[Type]("byte")

    val sortedParams = params.sortBy { expr => simplicity(ctx.env, expr) }

    val h = sortedParams.head
    val firstParamCompiled = MosExpressionCompiler.compile(ctx, h, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)

    val remainingParamsCompiled = sortedParams.tail.flatMap { p =>
      simpleOperation(opcode, ctx, p, IndexChoice.PreferY, preserveA = true, commutative = true)
    }

    firstParamCompiled ++ remainingParamsCompiled
  }

  def compileShiftOps(opcode: Opcode.Value, ctx: CompilationContext, l: Expression, r: Expression): List[AssemblyLine] = {
    val b = ctx.env.get[Type]("byte")
    val firstParamCompiled = MosExpressionCompiler.compile(ctx, l, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
    ctx.env.eval(r) match {
      case Some(NumericConstant(0, _)) =>
        MosExpressionCompiler.compile(ctx, l, None, NoBranching)
      case Some(NumericConstant(v, _)) if v > 0 =>
        firstParamCompiled ++ List.fill(v.toInt)(AssemblyLine.implied(opcode))
      case _ =>
        val compileCounter = MosExpressionCompiler.preserveRegisterIfNeeded(ctx, MosRegister.A,
          MosExpressionCompiler.compile(ctx, r, Some(b -> RegisterVariable(MosRegister.X, b)), NoBranching))
        val labelSkip = MosCompiler.nextLabel("ss")
        val labelRepeat = MosCompiler.nextLabel("sr")
        val loop = List(
          AssemblyLine.relative(BEQ, labelSkip),
          AssemblyLine.label(labelRepeat),
          AssemblyLine.implied(opcode),
          AssemblyLine.implied(DEX),
          AssemblyLine.relative(BNE, labelRepeat),
          AssemblyLine.label(labelSkip))
        firstParamCompiled ++ compileCounter ++ loop
    }
  }

  def compileNonetOps(ctx: CompilationContext, lhs: LhsExpression, rhs: Expression): List[AssemblyLine] = {
    val env = ctx.env
    val b = env.get[Type]("byte")
    val (ldaHi, ldaLo) = lhs match {
      case v: VariableExpression =>
        val variable = env.get[Variable](v.name)
        AssemblyLine.variable(ctx, LDA, variable, 1) -> AssemblyLine.variable(ctx, LDA, variable, 0)
      case SeparateBytesExpression(h: VariableExpression, l: VariableExpression) =>
        AssemblyLine.variable(ctx, LDA, env.get[Variable](h.name), 0) -> AssemblyLine.variable(ctx, LDA, env.get[Variable](l.name), 0)
      case _ =>
        ???
    }
    env.eval(rhs) match {
      case Some(NumericConstant(0, _)) =>
        MosExpressionCompiler.compile(ctx, lhs, None, NoBranching)
      case Some(NumericConstant(shift, _)) if shift > 0 =>
        if (ctx.options.flag(CompilationFlag.RorWarning))
          ErrorReporting.warn("ROR instruction generated", ctx.options, lhs.position)
        ldaHi ++ List(AssemblyLine.implied(ROR)) ++ ldaLo ++ List(AssemblyLine.implied(ROR)) ++ List.fill(shift.toInt - 1)(AssemblyLine.implied(LSR))
      case _ =>
        ErrorReporting.error("Non-constant shift amount", rhs.position) // TODO
        Nil
    }
  }

  def compileInPlaceByteShiftOps(opcode: Opcode.Value, ctx: CompilationContext, lhs: LhsExpression, rhs: Expression): List[AssemblyLine] = {
    val env = ctx.env
    val b = env.get[Type]("byte")
    val firstParamCompiled = MosExpressionCompiler.compile(ctx, lhs, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
    env.eval(rhs) match {
      case Some(NumericConstant(0, _)) =>
        MosExpressionCompiler.compile(ctx, lhs, None, NoBranching)
      case Some(NumericConstant(v, _)) if v > 0 =>
        val result = simpleOperation(opcode, ctx, lhs, IndexChoice.RequireX, preserveA = true, commutative = false)
        result ++ List.fill(v.toInt - 1)(result.last)
      case _ =>
        compileShiftOps(opcode, ctx, lhs, rhs) ++ MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, lhs)
    }
  }

  def compileInPlaceWordOrLongShiftOps(ctx: CompilationContext, lhs: LhsExpression, rhs: Expression, aslRatherThanLsr: Boolean): List[AssemblyLine] = {
    val env = ctx.env
    val b = env.get[Type]("byte")
    val targetBytes = getStorageForEachByte(ctx, lhs)
    val lo = targetBytes.head
    val hi = targetBytes.last
    // TODO: this probably breaks in case of complex split word expressions
    env.eval(rhs) match {
      case Some(NumericConstant(0, _)) =>
        MosExpressionCompiler.compile(ctx, lhs, None, NoBranching)
      case Some(NumericConstant(shift, _)) if shift > 0 =>
        if (ctx.options.flags(CompilationFlag.EmitNative65816Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, a1, l, _)), List(AssemblyLine(STA, a2, h, _))) =>
              if (a1 == a2 && l.+(1).quickSimplify == h) {
                return List(AssemblyLine.accu16) ++ List.fill(shift.toInt)(AssemblyLine(if (aslRatherThanLsr) ASL_W else LSR_W, a1, l)) ++ List(AssemblyLine.accu8)
              }
            case _ =>
          }
        }
        List.fill(shift.toInt)(if (aslRatherThanLsr) {
          staTo(ASL, lo) ++ targetBytes.tail.flatMap { b => staTo(ROL, b) }
        } else {
          if (ctx.options.flag(CompilationFlag.RorWarning))
            ErrorReporting.warn("ROR instruction generated", ctx.options, lhs.position)
          staTo(LSR, hi) ++ targetBytes.reverse.tail.flatMap { b => staTo(ROR, b) }
        }).flatten
      case _ =>
        val usesX = targetBytes.exists(_.exists(_.concernsX))
        val usesY = targetBytes.exists(_.exists(_.concernsY))
        val (register, decrease) = (usesX, usesY) match {
          case (true, false) => MosRegister.Y -> DEY
          case (false, true) => MosRegister.X -> DEX
          case (false, false) => MosRegister.X -> DEX
          case (true, true) => ???
        }

        val compileCounter = MosExpressionCompiler.preserveRegisterIfNeeded(ctx, MosRegister.A,
          MosExpressionCompiler.compile(ctx, rhs, Some(b -> RegisterVariable(register, b)), NoBranching))
        val labelSkip = MosCompiler.nextLabel("ss")
        val labelRepeat = MosCompiler.nextLabel("sr")

        if (ctx.options.flags(CompilationFlag.EmitNative65816Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, a1, l, _)), List(AssemblyLine(STA, a2, h, _))) =>
              if (a1 == a2 && l.+(1).quickSimplify == h) {
                return compileCounter ++ List(
                  AssemblyLine.relative(BEQ, labelSkip),
                  AssemblyLine.accu16,
                  AssemblyLine.label(labelRepeat),
                  AssemblyLine(if (aslRatherThanLsr) ASL_W else LSR_W, a1, l),
                  AssemblyLine.implied(decrease),
                  AssemblyLine.relative(BNE, labelRepeat),
                  AssemblyLine.accu8,
                  AssemblyLine.label(labelSkip))
              }
            case _ =>
          }
        }

        compileCounter ++ List(
          AssemblyLine.relative(BEQ, labelSkip),
          AssemblyLine.label(labelRepeat)) ++ (if (aslRatherThanLsr) {
          staTo(ASL, lo) ++ targetBytes.tail.flatMap { b => staTo(ROL, b) }
        } else {
          if (ctx.options.flag(CompilationFlag.RorWarning))
            ErrorReporting.warn("ROR instruction generated", ctx.options, lhs.position)
          staTo(LSR, hi) ++ targetBytes.reverse.tail.flatMap { b => staTo(ROR, b) }
        }) ++ List(
          AssemblyLine.implied(decrease),
          AssemblyLine.relative(BNE, labelRepeat),
          AssemblyLine.label(labelSkip))
    }
  }

  def compileByteComparison(ctx: CompilationContext, compType: ComparisonType.Value, lhs: Expression, rhs: Expression, branches: BranchSpec): List[AssemblyLine] = {
    val env = ctx.env
    val b = env.get[Type]("byte")
    if (simplicity(env, lhs) >= 'J' && simplicity(env, rhs) < 'J') {
      return compileByteComparison(ctx, ComparisonType.flip(compType), rhs, lhs, branches)
    }
    val firstParamCompiled = MosExpressionCompiler.compile(ctx, lhs, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
    val maybeConstant = env.eval(rhs)
    maybeConstant match {
      case Some(NumericConstant(0, _)) =>
        compType match {
          case ComparisonType.LessUnsigned =>
            ErrorReporting.warn("Unsigned < 0 is always false", ctx.options, lhs.position)
          case ComparisonType.LessOrEqualUnsigned =>
            if (ctx.options.flag(CompilationFlag.ExtraComparisonWarnings))
              ErrorReporting.warn("Unsigned <= 0 means the same as unsigned == 0", ctx.options, lhs.position)
          case ComparisonType.GreaterUnsigned =>
            if (ctx.options.flag(CompilationFlag.ExtraComparisonWarnings))
              ErrorReporting.warn("Unsigned > 0 means the same as unsigned != 0", ctx.options, lhs.position)
          case ComparisonType.GreaterOrEqualUnsigned =>
            ErrorReporting.warn("Unsigned >= 0 is always true", ctx.options, lhs.position)
          case _ =>
        }
      case Some(NumericConstant(1, _)) =>
        if (ctx.options.flag(CompilationFlag.ExtraComparisonWarnings)) {
          compType match {
            case ComparisonType.LessUnsigned =>
              ErrorReporting.warn("Unsigned < 1 means the same as unsigned == 0", ctx.options, lhs.position)
            case ComparisonType.GreaterOrEqualUnsigned =>
              ErrorReporting.warn("Unsigned >= 1 means the same as unsigned != 0", ctx.options, lhs.position)
            case _ =>
          }
        }
      case _ =>
    }
    val secondParamCompiled = maybeConstant match {
      case Some(x) =>
        compType match {
          case ComparisonType.Equal | ComparisonType.NotEqual | ComparisonType.LessSigned | ComparisonType.GreaterOrEqualSigned =>
            if (x.quickSimplify.isLowestByteAlwaysEqual(0) && OpcodeClasses.ChangesAAlways(firstParamCompiled.last.opcode)) Nil
            else List(AssemblyLine.immediate(CMP, x))
          case _ =>
            List(AssemblyLine.immediate(CMP, x))
        }
      case _ => compType match {
        case ComparisonType.Equal | ComparisonType.NotEqual | ComparisonType.LessSigned | ComparisonType.GreaterOrEqualSigned =>
          val secondParamCompiledUnoptimized = simpleOperation(CMP, ctx, rhs, IndexChoice.PreferY, preserveA = true, commutative = false)
          secondParamCompiledUnoptimized match {
            case List(AssemblyLine(CMP, Immediate, NumericConstant(0, _), true)) =>
              if (OpcodeClasses.ChangesAAlways(firstParamCompiled.last.opcode)) {
                Nil
              } else {
                secondParamCompiledUnoptimized
              }
            case _ => secondParamCompiledUnoptimized
          }
        case _ =>
          simpleOperation(CMP, ctx, rhs, IndexChoice.PreferY, preserveA = true, commutative = false)
      }
    }
    val (effectiveComparisonType, label) = branches match {
      case NoBranching => return Nil
      case BranchIfTrue(l) => compType -> l
      case BranchIfFalse(l) => ComparisonType.negate(compType) -> l
    }
    val branchingCompiled = effectiveComparisonType match {
      case ComparisonType.Equal =>
        List(AssemblyLine.relative(BEQ, Label(label)))
      case ComparisonType.NotEqual =>
        List(AssemblyLine.relative(BNE, Label(label)))

      case ComparisonType.LessUnsigned =>
        List(AssemblyLine.relative(BCC, Label(label)))
      case ComparisonType.GreaterOrEqualUnsigned =>
        List(AssemblyLine.relative(BCS, Label(label)))
      case ComparisonType.LessOrEqualUnsigned =>
        List(AssemblyLine.relative(BCC, Label(label)), AssemblyLine.relative(BEQ, Label(label)))
      case ComparisonType.GreaterUnsigned =>
        val x = MosCompiler.nextLabel("co")
        List(
          AssemblyLine.relative(BEQ, x),
          AssemblyLine.relative(BCS, Label(label)),
          AssemblyLine.label(x))

      case ComparisonType.LessSigned =>
        List(AssemblyLine.relative(BMI, Label(label)))
      case ComparisonType.GreaterOrEqualSigned =>
        List(AssemblyLine.relative(BPL, Label(label)))
      case ComparisonType.LessOrEqualSigned =>
        List(AssemblyLine.relative(BMI, Label(label)), AssemblyLine.relative(BEQ, Label(label)))
      case ComparisonType.GreaterSigned =>
        val x = MosCompiler.nextLabel("co")
        List(
          AssemblyLine.relative(BEQ, x),
          AssemblyLine.relative(BPL, Label(label)),
          AssemblyLine.label(x))
    }
    firstParamCompiled ++ secondParamCompiled ++ branchingCompiled

  }

  def compileWordComparison(ctx: CompilationContext, compType: ComparisonType.Value, lhs: Expression, rhs: Expression, branches: BranchSpec): List[AssemblyLine] = {
    val env = ctx.env
    // TODO: comparing longer variables
    val b = env.get[Type]("byte")
    val w = env.get[Type]("word")

    val (effectiveComparisonType, x) = branches match {
      case NoBranching => return Nil
      case BranchIfTrue(label) => compType -> label
      case BranchIfFalse(label) => ComparisonType.negate(compType) -> label
    }
    val (lh, ll, rh, rl) = (lhs, env.eval(lhs), rhs, env.eval(rhs)) match {
      case (_, Some(NumericConstant(lc, _)), _, Some(NumericConstant(rc, _))) =>
        return if (effectiveComparisonType match {
          // TODO: those masks are probably wrong
          case ComparisonType.Equal =>
            (lc & 0xffff) == (rc & 0xffff) // ??
          case ComparisonType.NotEqual =>
            (lc & 0xffff) != (rc & 0xffff) // ??

          case ComparisonType.LessOrEqualUnsigned =>
            (lc & 0xffff) <= (rc & 0xffff)
          case ComparisonType.GreaterOrEqualUnsigned =>
            (lc & 0xffff) >= (rc & 0xffff)
          case ComparisonType.GreaterUnsigned =>
            (lc & 0xffff) > (rc & 0xffff)
          case ComparisonType.LessUnsigned =>
            (lc & 0xffff) < (rc & 0xffff)

          case ComparisonType.LessOrEqualSigned =>
            lc.toShort <= rc.toShort
          case ComparisonType.GreaterOrEqualSigned =>
            lc.toShort >= rc.toShort
          case ComparisonType.GreaterSigned =>
            lc.toShort > rc.toShort
          case ComparisonType.LessSigned =>
            lc.toShort < rc.toShort
        }) List(AssemblyLine.absolute(JMP, Label(x))) else Nil
      case (_, Some(lc), _, Some(rc)) =>
        // TODO: comparing late-bound constants
        ???
      case (_, Some(lc), rv: VariableExpression, None) =>
        return compileWordComparison(ctx, ComparisonType.flip(compType), rhs, lhs, branches)
      case (v: VariableExpression, None, _, Some(rc)) =>
        val lva = env.get[VariableInMemory](v.name)
        (AssemblyLine.variable(ctx, CMP, lva, 1),
          AssemblyLine.variable(ctx, CMP, lva, 0),
          List(AssemblyLine.immediate(CMP, rc.hiByte.quickSimplify)),
          List(AssemblyLine.immediate(CMP, rc.loByte.quickSimplify)))
      case (lv: VariableExpression, None, rv: VariableExpression, None) =>
        val lva = env.get[VariableInMemory](lv.name)
        val rva = env.get[VariableInMemory](rv.name)
        (AssemblyLine.variable(ctx, CMP, lva, 1),
          AssemblyLine.variable(ctx, CMP, lva, 0),
          AssemblyLine.variable(ctx, CMP, rva, 1),
          AssemblyLine.variable(ctx, CMP, rva, 0))
      case _ =>
        // TODO comparing expressions
        ErrorReporting.error("Too complex expressions in comparison", lhs.position)
        (Nil, Nil, Nil, Nil)
    }
    val lType = MosExpressionCompiler.getExpressionType(ctx, lhs)
    val rType = MosExpressionCompiler.getExpressionType(ctx, rhs)
    val compactEqualityComparison = if (ctx.options.flag(CompilationFlag.OptimizeForSpeed)) {
      None
    } else if (lType.size == 1 && !lType.isSigned) {
      Some(cmpTo(LDA, ll) ++ cmpTo(EOR, rl) ++ cmpTo(ORA, rh))
    } else if (rType.size == 1 && !rType.isSigned) {
      Some(cmpTo(LDA, rl) ++ cmpTo(EOR, ll) ++ cmpTo(ORA, lh))
    } else {
      None
    }
    effectiveComparisonType match {
      case ComparisonType.Equal =>
        compactEqualityComparison match {
          case Some(code) => code :+ AssemblyLine.relative(BEQ, Label(x))
          case None =>
            val innerLabel = MosCompiler.nextLabel("cp")
            cmpTo(LDA, ll) ++
              cmpTo(CMP, rl) ++
              List(AssemblyLine.relative(BNE, innerLabel)) ++
              cmpTo(LDA, lh) ++
              cmpTo(CMP, rh) ++
              List(
                AssemblyLine.relative(BEQ, Label(x)),
                AssemblyLine.label(innerLabel))
        }

      case ComparisonType.NotEqual =>
        compactEqualityComparison match {
          case Some(code) => code :+ AssemblyLine.relative(BNE, Label(x))
          case None =>
            cmpTo(LDA, ll) ++
              cmpTo(CMP, rl) ++
              List(AssemblyLine.relative(BNE, Label(x))) ++
              cmpTo(LDA, lh) ++
              cmpTo(CMP, rh) ++
              List(AssemblyLine.relative(BNE, Label(x)))
        }

      case ComparisonType.LessUnsigned =>
        val innerLabel = MosCompiler.nextLabel("cp")
        cmpTo(LDA, lh) ++
          cmpTo(CMP, rh) ++
          List(
            AssemblyLine.relative(BCC, Label(x)),
            AssemblyLine.relative(BNE, innerLabel)) ++
          cmpTo(LDA, ll) ++
          cmpTo(CMP, rl) ++
          List(
            AssemblyLine.relative(BCC, Label(x)),
            AssemblyLine.label(innerLabel))

      case ComparisonType.LessOrEqualUnsigned =>
        val innerLabel = MosCompiler.nextLabel("cp")
        cmpTo(LDA, rh) ++
          cmpTo(CMP, lh) ++
          List(AssemblyLine.relative(BCC, innerLabel),
            AssemblyLine.relative(BNE, x)) ++
          cmpTo(LDA, rl) ++
          cmpTo(CMP, ll) ++
          List(AssemblyLine.relative(BCS, x),
            AssemblyLine.label(innerLabel))

      case ComparisonType.GreaterUnsigned =>
        val innerLabel = MosCompiler.nextLabel("cp")
        cmpTo(LDA, rh) ++
          cmpTo(CMP, lh) ++
          List(AssemblyLine.relative(BCC, Label(x)),
            AssemblyLine.relative(BNE, innerLabel)) ++
          cmpTo(LDA, rl) ++
          cmpTo(CMP, ll) ++
          List(AssemblyLine.relative(BCC, Label(x)),
            AssemblyLine.label(innerLabel))

      case ComparisonType.GreaterOrEqualUnsigned =>
        val innerLabel = MosCompiler.nextLabel("cp")
        cmpTo(LDA, lh) ++
          cmpTo(CMP, rh) ++
          List(AssemblyLine.relative(BCC, innerLabel),
            AssemblyLine.relative(BNE, x)) ++
          cmpTo(LDA, ll) ++
          cmpTo(CMP, rl) ++
          List(AssemblyLine.relative(BCS, x),
            AssemblyLine.label(innerLabel))

      case _ => ???
      // TODO: signed word comparisons
    }
  }

  def compileLongComparison(ctx: CompilationContext, compType: ComparisonType.Value, lhs: Expression, rhs: Expression, size:Int, branches: BranchSpec, alreadyFlipped: Boolean = false): List[AssemblyLine] = {
    val rType = MosExpressionCompiler.getExpressionType(ctx, rhs)
    if (rType.size < size && rType.isSigned) {
      if (alreadyFlipped) ???
      else return compileLongComparison(ctx, ComparisonType.flip(compType), rhs, lhs, size, branches, alreadyFlipped = true)
    }

    val (effectiveComparisonType, label) = branches match {
      case NoBranching => return Nil
      case BranchIfTrue(x) => compType -> x
      case BranchIfFalse(x) => ComparisonType.negate(compType) -> x
    }

    // TODO: check for carry flag clobbering
    val l = getLoadForEachByte(ctx, lhs, size)
    val r = getLoadForEachByte(ctx, rhs, size)

    val mask = (1L << (size * 8)) - 1
    (ctx.env.eval(lhs), ctx.env.eval(rhs)) match {
      case (Some(NumericConstant(lc, _)), Some(NumericConstant(rc, _))) =>
        return if (effectiveComparisonType match {
          // TODO: those masks are probably wrong
          case ComparisonType.Equal =>
            (lc & mask) == (rc & mask) // ??
          case ComparisonType.NotEqual =>
            (lc & mask) != (rc & mask) // ??

          case ComparisonType.LessOrEqualUnsigned =>
            (lc & mask) <= (rc & mask)
          case ComparisonType.GreaterOrEqualUnsigned =>
            (lc & mask) >= (rc & mask)
          case ComparisonType.GreaterUnsigned =>
            (lc & mask) > (rc & mask)
          case ComparisonType.LessUnsigned =>
            (lc & mask) < (rc & mask)

          case ComparisonType.LessOrEqualSigned =>
            signExtend(lc, mask) <= signExtend(lc, mask)
          case ComparisonType.GreaterOrEqualSigned =>
            signExtend(lc, mask) >= signExtend(lc, mask)
          case ComparisonType.GreaterSigned =>
            signExtend(lc, mask) > signExtend(lc, mask)
          case ComparisonType.LessSigned =>
            signExtend(lc, mask) < signExtend(lc, mask)
        }) List(AssemblyLine.absolute(JMP, Label(label))) else Nil
      case  _ =>
        effectiveComparisonType match {
          case ComparisonType.Equal =>
            val innerLabel = MosCompiler.nextLabel("cp")
            val bytewise = l.zip(r).map{
              case (cmpL, cmpR) => cmpTo(LDA, cmpL) ++ cmpTo(CMP, cmpR)
            }
            bytewise.init.flatMap(b => b :+ AssemblyLine.relative(BNE, innerLabel)) ++ bytewise.last ++List(
                AssemblyLine.relative(BEQ, Label(label)),
                AssemblyLine.label(innerLabel))
          case ComparisonType.NotEqual =>
            l.zip(r).flatMap {
              case (cmpL, cmpR) => cmpTo(LDA, cmpL) ++ cmpTo(CMP, cmpR) :+ AssemblyLine.relative(BNE, label)
            }
          case ComparisonType.LessUnsigned =>
            val calculateCarry = AssemblyLine.implied(SEC) :: l.zip(r).flatMap{
              case (cmpL, cmpR) => cmpTo(LDA, cmpL) ++ cmpTo(SBC, cmpR)
            }
            calculateCarry ++ List(AssemblyLine.relative(BCC, Label(label)))
          case ComparisonType.GreaterOrEqualUnsigned =>
            val calculateCarry = AssemblyLine.implied(SEC) :: l.zip(r).flatMap{
              case (cmpL, cmpR) => cmpTo(LDA, cmpL) ++ cmpTo(SBC, cmpR)
            }
            calculateCarry ++ List(AssemblyLine.relative(BCS, Label(label)))
          case ComparisonType.GreaterUnsigned | ComparisonType.LessOrEqualUnsigned =>
            compileLongComparison(ctx, ComparisonType.flip(compType), rhs, lhs, size, branches, alreadyFlipped = true)
          case _ =>
            ErrorReporting.error("Long signed comparisons are not yet supported", lhs.position)
            Nil
        }
    }

  }

  private def signExtend(value: Long, mask: Long): Long = {
    val masked = value & mask
    if (masked > mask/2) masked | ~mask
    else masked
  }

  def compileInPlaceByteMultiplication(ctx: CompilationContext, v: LhsExpression, addend: Expression): List[AssemblyLine] = {
    val b = ctx.env.get[Type]("byte")
    ctx.env.eval(addend) match {
      case Some(NumericConstant(0, _)) =>
        MosExpressionCompiler.compile(ctx, v, None, NoBranching) ++ (AssemblyLine.immediate(LDA, 0) :: MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, v))
      case Some(NumericConstant(1, _)) =>
        MosExpressionCompiler.compile(ctx, v, None, NoBranching)
      case Some(NumericConstant(x, _)) =>
        compileByteMultiplication(ctx, v, x.toInt) ++ MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, v)
      case _ =>
        PseudoregisterBuiltIns.compileByteMultiplication(ctx, Some(v), addend, storeInRegLo = false) ++ MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, v)
    }
  }

  def compileByteMultiplication(ctx: CompilationContext, v: Expression, c: Int): List[AssemblyLine] = {
    val result = ListBuffer[AssemblyLine]()
    // TODO: optimise
    val addingCode = simpleOperation(ADC, ctx, v, IndexChoice.PreferY, preserveA = false, commutative = false, decimal = false)
    val adc = addingCode.last
    val indexing = addingCode.init
    result ++= indexing
    result += AssemblyLine.immediate(LDA, 0)
    val mult = c & 0xff
    var mask = 128
    var empty = true
    while (mask > 0) {
      if (!empty) {
        result += AssemblyLine.implied(ASL)
      }
      if ((mult & mask) != 0) {
        result ++= List(AssemblyLine.implied(CLC), adc)
        empty = false
      }

      mask >>>= 1
    }
    result.toList
  }

  //noinspection ZeroIndexToHead
  def compileByteMultiplication(ctx: CompilationContext, params: List[Expression]): List[AssemblyLine] = {
    val (constants, variables) = params.map(p => p -> ctx.env.eval(p)).partition(_._2.exists(_.isInstanceOf[NumericConstant]))
    val constant = constants.map(_._2.get.asInstanceOf[NumericConstant].value).foldLeft(1L)(_ * _).toInt
    variables.length match {
      case 0 => List(AssemblyLine.immediate(LDA, constant & 0xff))
      case 1 => compileByteMultiplication(ctx, variables.head._1, constant)
      case 2 =>
        if (constant == 1)
          PseudoregisterBuiltIns.compileByteMultiplication(ctx, Some(variables(0)._1), variables(1)._1, storeInRegLo = false)
        else
          PseudoregisterBuiltIns.compileByteMultiplication(ctx, Some(variables(0)._1), variables(1)._1, storeInRegLo = true) ++
          compileByteMultiplication(ctx, VariableExpression("__reg.lo"), constant)
      case _ => ??? // TODO
    }
  }

  def compileInPlaceByteAddition(ctx: CompilationContext, v: LhsExpression, addend: Expression, subtract: Boolean, decimal: Boolean): List[AssemblyLine] = {
    if (decimal && !ctx.options.flag(CompilationFlag.DecimalMode)) {
      ErrorReporting.warn("Unsupported decimal operation", ctx.options, v.position)
    }
    val env = ctx.env
    val b = env.get[Type]("byte")
    val lhsIsDirectlyIncrementable = v match {
      case _: VariableExpression => true
      case IndexedExpression(pointy, indexExpr) =>
        val indexerSize = getIndexerSize(ctx, indexExpr)
        indexerSize <= 1 && (env.getPointy(pointy) match {
          case _: ConstantPointy => true
          case _: VariablePointy => false
        })
      case _ => false
    }
    env.eval(addend) match {
      case Some(NumericConstant(0, _)) => MosExpressionCompiler.compile(ctx, v, None, NoBranching)
      case Some(NumericConstant(1, _)) if lhsIsDirectlyIncrementable && !decimal => if (subtract) {
        simpleOperation(DEC, ctx, v, IndexChoice.RequireX, preserveA = false, commutative = true)
      } else {
        simpleOperation(INC, ctx, v, IndexChoice.RequireX, preserveA = false, commutative = true)
      }
      // TODO: compile +=2 to two INCs
      case Some(NumericConstant(-1, _)) if lhsIsDirectlyIncrementable && !decimal => if (subtract) {
        simpleOperation(INC, ctx, v, IndexChoice.RequireX, preserveA = false, commutative = true)
      } else {
        simpleOperation(DEC, ctx, v, IndexChoice.RequireX, preserveA = false, commutative = true)
      }
      case _ =>
        if (!subtract && simplicity(env, v) > simplicity(env, addend)) {
          val loadRhs = MosExpressionCompiler.compile(ctx, addend, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
          val modifyAcc = insertBeforeLast(AssemblyLine.implied(CLC), simpleOperation(ADC, ctx, v, IndexChoice.PreferY, preserveA = true, commutative = true, decimal = decimal))
          val storeLhs = MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, v)
          loadRhs ++ modifyAcc ++ storeLhs
        } else {
          val loadLhs = MosExpressionCompiler.compile(ctx, v, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
          val modifyLhs = if (subtract) {
            insertBeforeLast(AssemblyLine.implied(SEC), simpleOperation(SBC, ctx, addend, IndexChoice.PreferY, preserveA = true, commutative = false, decimal = decimal))
          } else {
            insertBeforeLast(AssemblyLine.implied(CLC), simpleOperation(ADC, ctx, addend, IndexChoice.PreferY, preserveA = true, commutative = true, decimal = decimal))
          }
          val storeLhs = MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, v)
          loadLhs ++ modifyLhs ++ storeLhs
        }
    }
  }

  private def getIndexerSize(ctx: CompilationContext, indexExpr: Expression): Int = {
    ctx.env.evalVariableAndConstantSubParts(indexExpr)._1.map(v => MosExpressionCompiler.getExpressionType(ctx, v).size).sum
  }

  def compileInPlaceWordOrLongAddition(ctx: CompilationContext, lhs: LhsExpression, addend: Expression, subtract: Boolean, decimal: Boolean): List[AssemblyLine] = {
    if (decimal && !ctx.options.flag(CompilationFlag.DecimalMode)) {
      ErrorReporting.warn("Unsupported decimal operation", ctx.options, lhs.position)
    }
    val env = ctx.env
    val b = env.get[Type]("byte")
    val w = env.get[Type]("word")
    val targetBytes: List[List[AssemblyLine]] = getStorageForEachByte(ctx, lhs)
    val lhsIsStack = targetBytes.head.head.opcode == TSX
    val targetSize = targetBytes.size
    val addendType = MosExpressionCompiler.getExpressionType(ctx, addend)
    var addendSize = addendType.size

    def isRhsComplex(xs: List[AssemblyLine]): Boolean = xs match {
      case AssemblyLine(LDA, _, _, _) :: Nil => false
      case AssemblyLine(LDA, _, _, _) :: AssemblyLine(LDX, _, _, _) :: Nil => false
      case _ => true
    }

    def isRhsStack(xs: List[AssemblyLine]): Boolean = xs.exists(_.opcode == TSX)

    val canUseIncDec = !decimal && targetBytes.forall(_.forall(l => l.opcode != STA || (l.addrMode match {
      case AddrMode.Absolute => true
      case AddrMode.AbsoluteX => true
      case AddrMode.ZeroPage => true
      case AddrMode.ZeroPageX => true
      case _ => false
    })))

    def doDec(lines: List[List[AssemblyLine]]):List[AssemblyLine] = lines match {
      case Nil => Nil
      case x :: Nil => staTo(DEC, x)
      case x :: xs =>
        val label = MosCompiler.nextLabel("de")
        staTo(LDA, x) ++
          List(AssemblyLine.relative(BNE, label)) ++
          doDec(xs) ++
          List(AssemblyLine.label(label)) ++
          staTo(DEC, x)
    }

    val (calculateRhs, addendByteRead0): (List[AssemblyLine], List[List[AssemblyLine]]) = env.eval(addend) match {
      case Some(NumericConstant(0, _)) =>
        return MosExpressionCompiler.compile(ctx, lhs, None, NoBranching)
      case Some(NumericConstant(1, _)) if canUseIncDec && !subtract =>
        if (ctx.options.flags(CompilationFlag.Emit65CE02Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, ZeroPage, l, _)), List(AssemblyLine(STA, ZeroPage, h, _))) =>
              if (l.+(1).quickSimplify == h) {
                return List(AssemblyLine.zeropage(INC_W, l))
              }
            case _ =>
          }
        }
        if (ctx.options.flags(CompilationFlag.EmitNative65816Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, a1@(ZeroPage | Absolute | ZeroPageX | AbsoluteX), l, _)), List(AssemblyLine(STA, a2, h, _))) =>
              if (a1 == a2 && l.+(1).quickSimplify == h) {
                return List(AssemblyLine.accu16, AssemblyLine(INC_W, a1, l), AssemblyLine.accu8)
              }
          }
        }
        val label = MosCompiler.nextLabel("in")
        return staTo(INC, targetBytes.head) ++ targetBytes.tail.flatMap(l => AssemblyLine.relative(BNE, label)::staTo(INC, l)) :+ AssemblyLine.label(label)
      case Some(NumericConstant(-1, _)) if canUseIncDec && subtract =>
        if (ctx.options.flags(CompilationFlag.Emit65CE02Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, ZeroPage, l, _)), List(AssemblyLine(STA, ZeroPage, h, _))) =>
              if (l.+(1).quickSimplify == h) {
                return List(AssemblyLine.zeropage(INC_W, l))
              }
          }
        }
        if (ctx.options.flags(CompilationFlag.EmitNative65816Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, a1@(ZeroPage | Absolute | ZeroPageX | AbsoluteX), l, _)), List(AssemblyLine(STA, a2, h, _))) =>
              if (a1 == a2 && l.+(1).quickSimplify == h) {
                return List(AssemblyLine.accu16, AssemblyLine(INC_W, a1, l), AssemblyLine.accu8)
              }
          }
        }
        val label = MosCompiler.nextLabel("in")
        return staTo(INC, targetBytes.head) ++ targetBytes.tail.flatMap(l => AssemblyLine.relative(BNE, label)::staTo(INC, l)) :+ AssemblyLine.label(label)
      case Some(NumericConstant(1, _)) if canUseIncDec && subtract =>
        if (ctx.options.flags(CompilationFlag.Emit65CE02Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, ZeroPage, l, _)), List(AssemblyLine(STA, ZeroPage, h, _))) =>
              if (l.+(1).quickSimplify == h) {
                return List(AssemblyLine.zeropage(DEC_W, l))
              }
          }
        }
        if (ctx.options.flags(CompilationFlag.EmitNative65816Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, a1@(ZeroPage | Absolute | ZeroPageX | AbsoluteX), l, _)), List(AssemblyLine(STA, a2, h, _))) =>
              if (a1 == a2 && l.+(1).quickSimplify == h) {
                return List(AssemblyLine.accu16, AssemblyLine(DEC_W, a1, l), AssemblyLine.accu8)
              }
          }
        }
        val label = MosCompiler.nextLabel("de")
        return doDec(targetBytes)
      case Some(NumericConstant(-1, _)) if canUseIncDec && !subtract =>
        if (ctx.options.flags(CompilationFlag.Emit65CE02Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, ZeroPage, l, _)), List(AssemblyLine(STA, ZeroPage, h, _))) =>
              if (l.+(1).quickSimplify == h) {
                return List(AssemblyLine.zeropage(DEC_W, l))
              }
          }
        }
        if (ctx.options.flags(CompilationFlag.EmitNative65816Opcodes)) {
          targetBytes match {
            case List(List(AssemblyLine(STA, a1@(ZeroPage | Absolute | ZeroPageX | AbsoluteX), l, _)), List(AssemblyLine(STA, a2, h, _))) =>
              if (a1 == a2 && l.+(1).quickSimplify == h) {
                return List(AssemblyLine.accu16, AssemblyLine(DEC_W, a1, l), AssemblyLine.accu8)
              }
          }
        }
        val label = MosCompiler.nextLabel("de")
        return doDec(targetBytes)
      case Some(constant) =>
        addendSize = targetSize
        Nil -> List.tabulate(targetSize)(i => List(AssemblyLine.immediate(LDA, constant.subbyte(i))))
      case None =>
        addendSize match {
          case 1 =>
            val base = MosExpressionCompiler.compile(ctx, addend, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
            if (subtract) {
              if (isRhsComplex(base)) {
                if (isRhsStack(base)) {
                  ErrorReporting.warn("Subtracting a stack-based value", ctx.options)
                  ???
                }
                (base ++ List(AssemblyLine.implied(PHA))) -> List(List(AssemblyLine.implied(TSX), AssemblyLine.absoluteX(LDA, 0x101)))
              } else {
                Nil -> base.map(_ :: Nil)
              }
            } else {
              base -> List(Nil)
            }
          case 2 =>
            val base = MosExpressionCompiler.compile(ctx, addend, Some(MosExpressionCompiler.getExpressionType(ctx, addend) -> RegisterVariable(MosRegister.AX, w)), NoBranching)
            if (isRhsStack(base)) {
              val fixedBase = MosExpressionCompiler.compile(ctx, addend, Some(MosExpressionCompiler.getExpressionType(ctx, addend) -> RegisterVariable(MosRegister.AY, w)), NoBranching)
              if (subtract) {
                ErrorReporting.warn("Subtracting a stack-based value", ctx.options)
                if (isRhsComplex(base)) {
                  ???
                } else {
                  Nil -> fixedBase
                  ???
                }
              } else {
                fixedBase -> List(Nil, List(AssemblyLine.implied(TYA)))
              }
            } else {
              if (subtract) {
                if (isRhsComplex(base)) {
                  (base ++ List(
                    AssemblyLine.implied(PHA),
                    AssemblyLine.implied(TXA),
                    AssemblyLine.implied(PHA))
                    ) -> List(
                    List(AssemblyLine.implied(TSX), AssemblyLine.absoluteX(LDA, 0x102)),
                    List(AssemblyLine.implied(TSX), AssemblyLine.absoluteX(LDA, 0x101)))
                } else {
                  Nil -> base.map(_ :: Nil)
                }
              } else {
                if (lhsIsStack) {
                  val fixedBase = MosExpressionCompiler.compile(ctx, addend, Some(MosExpressionCompiler.getExpressionType(ctx, addend) -> RegisterVariable(MosRegister.AY, w)), NoBranching)
                  fixedBase -> List(Nil, List(AssemblyLine.implied(TYA)))
                } else {
                  base -> List(Nil, List(AssemblyLine.implied(TXA)))
                }
              }
            }
          case _ => Nil -> (addend match {
            case vv: VariableExpression =>
              val source = env.get[Variable](vv.name)
              List.tabulate(addendSize)(i => AssemblyLine.variable(ctx, LDA, source, i))
          })
        }
    }
    val addendByteRead = addendByteRead0 ++ List.fill((targetSize - addendByteRead0.size) max 0)(List(AssemblyLine.immediate(LDA, 0)))

    if (ctx.options.flags(CompilationFlag.EmitNative65816Opcodes)) {
      (removeTsx(targetBytes), calculateRhs, removeTsx(addendByteRead)) match {
        case (
          List(List(AssemblyLine(STA, ta1, tl, _)), List(AssemblyLine(STA, ta2, th, _))),
          Nil,
          List(List(AssemblyLine(LDA, Immediate, al, _)), List(AssemblyLine(LDA, Immediate, ah, _)))) =>
          if (ta1 == ta2 && tl.+(1).quickSimplify == th) {
            return wrapInSedCldIfNeeded(decimal, List(
              AssemblyLine.implied(if(subtract) SEC else CLC),
              AssemblyLine.accu16,
              AssemblyLine(LDA_W, ta1, tl),
              AssemblyLine(if(subtract) SBC_W else ADC_W, WordImmediate, ah.asl(8).+(al).quickSimplify),
              AssemblyLine(STA_W, ta1, tl),
              AssemblyLine.accu8))
          }
        case (
          List(List(AssemblyLine(STA, ta1, tl, _)), List(AssemblyLine(STA, ta2, th, _))),
          Nil,
          List(List(AssemblyLine(LDA, aa1, al, _)), List(AssemblyLine(LDA, aa2, ah, _)))) =>
          if (ta1 == ta2 && aa1 == aa2 && tl.+(1).quickSimplify == th && al.+(1).quickSimplify == ah) {
            return wrapInSedCldIfNeeded(decimal, List(
              AssemblyLine.accu16,
              AssemblyLine.implied(if(subtract) SEC else CLC),
              AssemblyLine(LDA_W, ta1, tl),
              AssemblyLine(if(subtract) SBC_W else ADC_W, aa1, al),
              AssemblyLine(STA_W, ta1, tl),
              AssemblyLine.accu8))
          }
        case (
          List(List(AssemblyLine(STA, ta1, tl, _)), List(AssemblyLine(STA, ta2, th, _))),
          List(AssemblyLine(TSX, _, _, _), AssemblyLine(LDA, AbsoluteX, NumericConstant(al, _), _), AssemblyLine(LDY, AbsoluteX, NumericConstant(ah, _), _)),
          List(Nil, List(AssemblyLine(TYA, _, _, _)))) =>
          if (ta1 == ta2 && tl.+(1).quickSimplify == th && al + 1 == ah) {
            return wrapInSedCldIfNeeded(decimal, List(
              AssemblyLine.accu16,
              AssemblyLine.implied(if(subtract) SEC else CLC),
              AssemblyLine(LDA_W, ta1, tl),
              AssemblyLine(if(subtract) SBC_W else ADC_W, Stack, NumericConstant(al & 0xff, 1)),
              AssemblyLine(STA_W, ta1, tl),
              AssemblyLine.accu8))
          }
        case _ =>
      }
    }
    val buffer = mutable.ListBuffer[AssemblyLine]()
    buffer ++= calculateRhs
    buffer += AssemblyLine.implied(if (subtract) SEC else CLC)
    val extendMultipleBytes = targetSize > addendSize + 1
    val extendAtLeastOneByte = targetSize > addendSize
    for (i <- 0 until targetSize) {
      if (subtract) {
        if (addendSize < targetSize && addendType.isSigned) {
          // TODO: sign extension
          ???
        }
        buffer ++= staTo(LDA, targetBytes(i))
        buffer ++= wrapInSedCldIfNeeded(decimal, ldTo(SBC, addendByteRead(i)))
        buffer ++= targetBytes(i)
      } else {
        if (i >= addendSize) {
          if (addendType.isSigned) {
            val label = MosCompiler.nextLabel("sx")
            buffer += AssemblyLine.implied(TXA)
            if (i == addendSize) {
              buffer += AssemblyLine.immediate(ORA, 0x7f)
              buffer += AssemblyLine.relative(BMI, label)
              buffer += AssemblyLine.immediate(LDA, 0)
              buffer += AssemblyLine.label(label)
              if (extendMultipleBytes) buffer += AssemblyLine.implied(TAX)
            }
          } else {
            buffer += AssemblyLine.immediate(LDA, 0)
          }
        } else {
          buffer ++= addendByteRead(i)
          if (addendType.isSigned && i == addendSize - 1 && extendAtLeastOneByte) {
            buffer += AssemblyLine.implied(TAX)
          }
        }
        buffer ++= wrapInSedCldIfNeeded(decimal, staTo(ADC, targetBytes(i)))
        buffer ++= targetBytes(i)
      }
    }
    for (i <- 0 until calculateRhs.count(a => a.opcode == PHA) - calculateRhs.count(a => a.opcode == PLA)) {
      buffer += AssemblyLine.implied(PLA)
    }
    buffer.toList
  }

  def compileInPlaceByteBitOp(ctx: CompilationContext, v: LhsExpression, param: Expression, operation: Opcode.Value): List[AssemblyLine] = {
    val env = ctx.env
    val b = env.get[Type]("byte")
    (operation, env.eval(param)) match {
      case (EOR, Some(NumericConstant(0, _)))
           | (ORA, Some(NumericConstant(0, _)))
           | (AND, Some(NumericConstant(0xff, _)))
           | (AND, Some(NumericConstant(-1, _))) =>
        Nil
      case _ =>
        if (simplicity(env, v) > simplicity(env, param)) {
          val loadRhs = MosExpressionCompiler.compile(ctx, param, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
          val modifyAcc = simpleOperation(operation, ctx, v, IndexChoice.PreferY, preserveA = true, commutative = true)
          val storeLhs = MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, v)
          loadRhs ++ modifyAcc ++ storeLhs
        } else {
          val loadLhs = MosExpressionCompiler.compile(ctx, v, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
          val modifyLhs = simpleOperation(operation, ctx, param, IndexChoice.PreferY, preserveA = true, commutative = true)
          val storeLhs = MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, v)
          loadLhs ++ modifyLhs ++ storeLhs
        }
    }
  }


  def compileInPlaceWordOrLongBitOp(ctx: CompilationContext, lhs: LhsExpression, param: Expression, operation: Opcode.Value): List[AssemblyLine] = {
    val env = ctx.env
    val b = env.get[Type]("byte")
    val w = env.get[Type]("word")
    val targetBytes: List[List[AssemblyLine]] = getStorageForEachByte(ctx, lhs)
    val lo = targetBytes.head
    val targetSize = targetBytes.size
    val paramType = MosExpressionCompiler.getExpressionType(ctx, param)
    var paramSize = paramType.size
    val extendMultipleBytes = targetSize > paramSize + 1
    val extendAtLeastOneByte = targetSize > paramSize
    val (calculateRhs, addendByteRead) = env.eval(param) match {
      case Some(constant) =>
        paramSize = targetSize
        Nil -> List.tabulate(targetSize)(i => List(AssemblyLine.immediate(LDA, constant.subbyte(i))))
      case None =>
        paramSize match {
          case 1 =>
            val base = MosExpressionCompiler.compile(ctx, param, Some(b -> RegisterVariable(MosRegister.A, b)), NoBranching)
            base -> List(Nil)
          case 2 =>
            val base = MosExpressionCompiler.compile(ctx, param, Some(MosExpressionCompiler.getExpressionType(ctx, param) -> RegisterVariable(MosRegister.AX, w)), NoBranching)
            base -> List(Nil, List(AssemblyLine.implied(TXA)))
          case _ => Nil -> (param match {
            case vv: VariableExpression =>
              val source = env.get[Variable](vv.name)
              List.tabulate(paramSize)(i => AssemblyLine.variable(ctx, LDA, source, i))
          })
        }
    }
    if (ctx.options.flags(CompilationFlag.EmitNative65816Opcodes)) {
      (removeTsx(targetBytes), removeTsx(addendByteRead)) match {
        case (List(List(AssemblyLine(STA, ta1, tl, _)), List(AssemblyLine(STA, ta2, th, _))), List(List(AssemblyLine(LDA, Immediate, al, _)), List(AssemblyLine(LDA, Immediate, ah, _)))) =>
          if (ta1 == ta2 && tl.+(1).quickSimplify == th) {
            return List(
              AssemblyLine.accu16,
              AssemblyLine(LDA_W, ta1, tl),
              AssemblyLine(Opcode.widen(operation).get, WordImmediate, ah.asl(8).+(al).quickSimplify),
              AssemblyLine(STA_W, ta1, tl),
              AssemblyLine.accu8)
          }
        case (List(List(AssemblyLine(STA, ta1, tl, _)), List(AssemblyLine(STA, ta2, th, _))), List(List(AssemblyLine(LDA, aa1, al, _)), List(AssemblyLine(LDA, aa2, ah, _)))) =>
          if (ta1 == ta2 && aa1 == aa2 && tl.+(1).quickSimplify == th && al.+(1).quickSimplify == ah) {
            return List(
              AssemblyLine.accu16,
              AssemblyLine(LDA_W, ta1, tl),
              AssemblyLine(Opcode.widen(operation).get, aa1, al),
              AssemblyLine(STA_W, ta1, tl),
              AssemblyLine.accu8)
          }
        case _ =>
      }
    }
    val AllOnes = (1L << (8 * targetSize)) - 1
    (operation, env.eval(param)) match {
      case (EOR, Some(NumericConstant(0, _)))
           | (ORA, Some(NumericConstant(0, _)))
           | (AND, Some(NumericConstant(AllOnes, _))) =>
        MosExpressionCompiler.compile(ctx, lhs, None, NoBranching)
      case _ =>
        val buffer = mutable.ListBuffer[AssemblyLine]()
        buffer ++= calculateRhs
        for (i <- 0 until targetSize) {
          if (i < paramSize) {
            buffer ++= addendByteRead(i)
            if (paramType.isSigned && i == paramSize - 1 && extendAtLeastOneByte) {
              buffer += AssemblyLine.implied(TAX)
            }
          } else {
            if (paramType.isSigned) {
              val label = MosCompiler.nextLabel("sx")
              buffer += AssemblyLine.implied(TXA)
              if (i == paramSize) {
                buffer += AssemblyLine.immediate(ORA, 0x7f)
                buffer += AssemblyLine.relative(BMI, label)
                buffer += AssemblyLine.immediate(LDA, 0)
                buffer += AssemblyLine.label(label)
                if (extendMultipleBytes) buffer += AssemblyLine.implied(TAX)
              }
            } else {
              buffer += AssemblyLine.immediate(LDA, 0)
            }
          }
          buffer ++= staTo(operation, targetBytes(i))
          buffer ++= targetBytes(i)
        }
        for (i <- 0 until calculateRhs.count(a => a.opcode == PHA) - calculateRhs.count(a => a.opcode == PLA)) {
          buffer += AssemblyLine.implied(PLA)
        }
        buffer.toList
    }
  }


  private def getStorageForEachByte(ctx: CompilationContext, lhs: LhsExpression): List[List[AssemblyLine]] = {
    val env = ctx.env
    lhs match {
      case v: VariableExpression =>
        val variable = env.get[Variable](v.name)
        List.tabulate(variable.typ.size) { i => AssemblyLine.variable(ctx, STA, variable, i) }
      case IndexedExpression(variable, index) =>
        List(MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, lhs))
      case SeparateBytesExpression(h: LhsExpression, l: LhsExpression) =>
        if (simplicity(ctx.env, h) < 'J' || simplicity(ctx.env, l) < 'J') {
          // a[b]:c[d] is the most complex expression that doesn't cause the following warning
          ErrorReporting.warn("Too complex expression given to the `:` operator, generated code might be wrong", ctx.options, lhs.position)
        }
        List(
          getStorageForEachByte(ctx, l).head,
          MosExpressionCompiler.preserveRegisterIfNeeded(ctx, MosRegister.A, getStorageForEachByte(ctx, h).head))
      case _ =>
        ???
    }
  }
  private def getLoadForEachByte(ctx: CompilationContext, expr: Expression, size: Int): List[List[AssemblyLine]] = {
    val env = ctx.env
    env.eval(expr) match {
      case Some(c) =>
        List.tabulate(size) { i => List(AssemblyLine.immediate(CMP, c.subbyte(i))) }
      case None =>
        expr match {
          case v: VariableExpression =>
            val variable = env.get[Variable](v.name)
            List.tabulate(size) { i =>
              if (i < variable.typ.size) {
                AssemblyLine.variable(ctx, CMP, variable, i)
              } else if (variable.typ.isSigned) {
                val label = MosCompiler.nextLabel("sx")
                AssemblyLine.variable(ctx, LDA, variable, variable.typ.size - 1) ++ List(
                  AssemblyLine.immediate(ORA, 0x7F),
                  AssemblyLine.relative(BMI, label),
                  AssemblyLine.immediate(CMP, 0),
                  AssemblyLine.label(label))
              } else List(AssemblyLine.immediate(CMP, 0))
            }
          case expr@IndexedExpression(variable, index) =>
            List.tabulate(size) { i =>
              if (i == 0) MosExpressionCompiler.compileByteStorage(ctx, MosRegister.A, expr)
              else List(AssemblyLine.immediate(CMP, 0))
            }
          case SeparateBytesExpression(h: LhsExpression, l: LhsExpression) =>
            if (simplicity(ctx.env, h) < 'J' || simplicity(ctx.env, l) < 'J') {
              // a[b]:c[d] is the most complex expression that doesn't cause the following warning
              ErrorReporting.warn("Too complex expression given to the `:` operator, generated code might be wrong", ctx.options, expr.position)
            }
            List.tabulate(size) { i =>
              if (i == 0) getStorageForEachByte(ctx, l).head
              else if (i == 1) MosExpressionCompiler.preserveRegisterIfNeeded(ctx, MosRegister.A, getStorageForEachByte(ctx, h).head)
              else List(AssemblyLine.immediate(CMP, 0))
            }
          case _ =>
            ???
        }
    }
  }

  private def removeTsx(codes: List[List[AssemblyLine]]): List[List[AssemblyLine]] = codes.map {
    case List(AssemblyLine(TSX, _, _, _), AssemblyLine(op, AbsoluteX, NumericConstant(nn, _), _)) if nn >= 0x100 && nn <= 0x1ff =>
      List(AssemblyLine(op, Stack, NumericConstant(nn & 0xff, 1)))
    case x => x
  }
}
