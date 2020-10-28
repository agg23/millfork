package millfork.compiler.z80

import millfork.CompilationFlag
import millfork.assembly.z80._
import millfork.compiler.{ComparisonType, _}
import millfork.env.NumericConstant
import millfork.node.{Expression, FunctionCallExpression, LiteralExpression, ZRegister}

/**
  * @author Karol Stasiak
  */
object Z80Comparisons {

  import ComparisonType._

  def compile8BitComparison(ctx: CompilationContext, compType: ComparisonType.Value, l: Expression, r: Expression, branches: BranchSpec): List[ZLine] = {
    handleConstantComparison(ctx, compType, l, r, branches).foreach(return _)
    if (ComparisonType.isSigned(compType) && !ctx.options.flag(CompilationFlag.EmitZ80Opcodes)) {
      return compile8BitComparison(ctx, ComparisonType.toUnsigned(compType),
        FunctionCallExpression("^", List(l, LiteralExpression(0x80, 1).pos(l.position, l.endPosition))).pos(l.position, l.endPosition),
        FunctionCallExpression("^", List(r, LiteralExpression(0x80, 1).pos(r.position, r.endPosition))).pos(r.position, r.endPosition),
        branches)
    }
    (ctx.env.eval(r), compType) match {
      case (Some(NumericConstant(0, _)), GreaterUnsigned) =>
        return compile8BitComparison(ctx, ComparisonType.NotEqual, l, r, branches)
      case (Some(NumericConstant(0, _)), LessOrEqualUnsigned) =>
        return compile8BitComparison(ctx, ComparisonType.Equal, l, r, branches)
      case (Some(NumericConstant(1, _)), ComparisonType.LessUnsigned) =>
        return compile8BitComparison(ctx, ComparisonType.Equal, l, r #-# 1, branches)
      case (Some(NumericConstant(1, _)), ComparisonType.GreaterOrEqualUnsigned) =>
        return compile8BitComparison(ctx, ComparisonType.NotEqual, l, r #-# 1, branches)
      case (Some(NumericConstant(n, 1)), ComparisonType.GreaterUnsigned) if n >= 1 && n <= 254 =>
        return compile8BitComparison(ctx, ComparisonType.GreaterOrEqualUnsigned, l, r #+# 1, branches)
      case (Some(NumericConstant(n, 1)), ComparisonType.LessOrEqualUnsigned) if n >= 1 && n <= 254 =>
        return compile8BitComparison(ctx, ComparisonType.LessUnsigned, l, r #+# 1, branches)
      case _ =>
    }
    compType match {
      case GreaterUnsigned | LessOrEqualUnsigned | GreaterSigned | LessOrEqualSigned =>
        return compile8BitComparison(ctx, ComparisonType.flip(compType), r, l, branches)
      case _ => ()
    }

    var prepareAE = Z80ExpressionCompiler.compileToA(ctx, r) match {
      case List(ZLine0(ZOpcode.LD, TwoRegisters(ZRegister.A, ZRegister.IMM_8), param)) =>
        Z80ExpressionCompiler.compileToA(ctx, l) :+ ZLine.ldImm8(ZRegister.E, param)
      case compiledR => compiledR ++
        List(ZLine.ld8(ZRegister.E, ZRegister.A)) ++
        Z80ExpressionCompiler.stashDEIfChanged(ctx, Z80ExpressionCompiler.compileToA(ctx, l))
    }

    var calculateFlags = if (ComparisonType.isSigned(compType) && ctx.options.flag(CompilationFlag.EmitZ80Opcodes)) {
      val fixup = ctx.nextLabel("co")
      List(
        ZLine.register(ZOpcode.SUB, ZRegister.E),
        ZLine.jump(fixup, IfFlagClear(ZFlag.P)),
        ZLine.imm8(ZOpcode.XOR, 0x80),
        ZLine.label(fixup))
    } else if (ComparisonType.isSigned(compType) && !ctx.options.flag(CompilationFlag.EmitZ80Opcodes)) {
      List(ZLine.register(ZOpcode.SUB, ZRegister.E))
    } else List(ZLine.register(ZOpcode.CP, ZRegister.E))

    (prepareAE.last, calculateFlags.head) match {
      case (
        ZLine0(ZOpcode.LD, TwoRegisters(ZRegister.E, ZRegister.IMM_8), c),
        ZLine0(op, OneRegister(ZRegister.E), _)
        ) =>
        prepareAE = prepareAE.init
        calculateFlags = ZLine.imm8(op, c) :: calculateFlags.tail
      case _ =>
    }

    if (branches == NoBranching) return prepareAE ++ calculateFlags
    val (effectiveCompType, label) = branches match {
      case BranchIfFalse(la) => ComparisonType.negate(compType) -> la
      case BranchIfTrue(la) => compType -> la
    }
    val jump = effectiveCompType match {
      case Equal => List(ZLine.jump(label, IfFlagSet(ZFlag.Z)))
      case NotEqual => List(ZLine.jump(label, IfFlagClear(ZFlag.Z)))
      case LessUnsigned => List(ZLine.jump(label, IfFlagSet(ZFlag.C)))
      case GreaterOrEqualUnsigned => List(ZLine.jump(label, IfFlagClear(ZFlag.C)))
      case LessOrEqualUnsigned => List(ZLine.jump(label, IfFlagSet(ZFlag.Z)), ZLine.jump(label, IfFlagSet(ZFlag.C)))
      case GreaterUnsigned =>
        val x = ctx.nextLabel("co")
        List(
          ZLine.jumpR(ctx, x, IfFlagSet(ZFlag.Z)),
          ZLine.jump(label, IfFlagClear(ZFlag.C)),
          ZLine.label(x))
      case LessSigned =>
        if (ctx.options.flag(CompilationFlag.EmitIntel8080Opcodes)) {
          List(ZLine.jump(label, IfFlagSet(ZFlag.S)))
        } else {
          List(
            ZLine.register(ZOpcode.BIT7, ZRegister.A),
            ZLine.jump(label, IfFlagClear(ZFlag.Z)))
        }
      case GreaterOrEqualSigned =>
        if (ctx.options.flag(CompilationFlag.EmitIntel8080Opcodes)) {
          List(ZLine.jump(label, IfFlagClear(ZFlag.S)))
        } else {
          List(
            ZLine.register(ZOpcode.BIT7, ZRegister.A),
            ZLine.jump(label, IfFlagSet(ZFlag.Z)))
        }
      case LessOrEqualSigned =>
        if (ctx.options.flag(CompilationFlag.EmitIntel8080Opcodes)) {
          List(
            ZLine.jump(label, IfFlagSet(ZFlag.Z)),
            ZLine.jump(label, IfFlagSet(ZFlag.S)))
        } else {
          List(
            ZLine.jump(label, IfFlagSet(ZFlag.Z)),
            ZLine.register(ZOpcode.BIT7, ZRegister.A),
            ZLine.jump(label, IfFlagClear(ZFlag.Z)))
        }
      case GreaterSigned =>
        val x = ctx.nextLabel("co")
        if (ctx.options.flag(CompilationFlag.EmitIntel8080Opcodes)) {
          List(
            ZLine.jumpR(ctx, x, IfFlagSet(ZFlag.Z)),
            ZLine.jump(label, IfFlagClear(ZFlag.S)),
            ZLine.label(x))
        } else {
          List(
            ZLine.jumpR(ctx, x, IfFlagSet(ZFlag.Z)),
            ZLine.register(ZOpcode.BIT7, ZRegister.A),
            ZLine.jump(label, IfFlagSet(ZFlag.Z)),
            ZLine.label(x))
        }
    }
    prepareAE ++ calculateFlags ++ jump
  }

  private def handleConstantComparison(ctx: CompilationContext, compType: ComparisonType.Value, l: Expression, r: Expression, branches: BranchSpec): Option[List[ZLine]] = {
    (ctx.env.eval(l), ctx.env.eval(r)) match {
      case (Some(NumericConstant(lc, _)), Some(NumericConstant(rc, _))) =>
        val constantCondition = compType match {
          case Equal => lc == rc
          case NotEqual => lc != rc
          case GreaterSigned | GreaterUnsigned => lc > rc
          case LessOrEqualSigned | LessOrEqualUnsigned => lc <= rc
          case GreaterOrEqualSigned | GreaterOrEqualUnsigned => lc >= rc
          case LessSigned | LessUnsigned => lc < rc
        }
        return Some(branches match {
          case BranchIfFalse(b) => if (!constantCondition) List(ZLine.jump(b)) else Nil
          case BranchIfTrue(b) => if (constantCondition) List(ZLine.jump(b)) else Nil
          case _ => Nil
        })
      case _ =>
    }
    None
  }

  def compile16BitComparison(ctx: CompilationContext, compType: ComparisonType.Value, l: Expression, r: Expression, branches: BranchSpec): List[ZLine] = {
    handleConstantComparison(ctx, compType, l, r, branches).foreach(return _)
    compType match {
      case GreaterUnsigned | LessOrEqualUnsigned | GreaterSigned | LessOrEqualSigned =>
        return compile16BitComparison(ctx, ComparisonType.flip(compType), r, l, branches)
      case _ => ()
    }
    import ZRegister._
    import ZOpcode._
    val calculateLeft = Z80ExpressionCompiler.compileToHL(ctx, l)
    val calculateRight = Z80ExpressionCompiler.compileToHL(ctx, r)
    if (branches == NoBranching) {
      return calculateLeft ++ calculateRight
    }
    val fastEqualityComparison: Option[List[ZLine]] = (calculateLeft, calculateRight) match {
      case (List(ZLine0(LD_16, TwoRegisters(HL, IMM_16), NumericConstant(0, _))), _) =>
        Some(calculateRight ++ List(ZLine.ld8(A, H), ZLine.register(OR, L)))
      case (List(ZLine0(LD_16, TwoRegisters(HL, IMM_16), NumericConstant(0xffff, _))), _) =>
        Some(calculateRight ++ List(ZLine.ld8(A, H), ZLine.register(AND, L), ZLine.imm8(CP, 0xff)))
      case (_, List(ZLine0(LD_16, TwoRegisters(HL, IMM_16), NumericConstant(0, _)))) =>
        Some(calculateLeft ++ List(ZLine.ld8(A, H), ZLine.register(OR, L)))
      case (_, List(ZLine0(LD_16, TwoRegisters(HL, IMM_16), NumericConstant(0xffff, _)))) =>
        Some(calculateLeft ++ List(ZLine.ld8(A, H), ZLine.register(AND, L), ZLine.imm8(CP, 0xff)))
      case _ =>
        None
    }
    fastEqualityComparison match {
      case Some(code) =>
        (compType, branches) match {
          case (ComparisonType.Equal, BranchIfTrue(lbl)) => return code :+ ZLine.jumpR(ctx, lbl, IfFlagSet(ZFlag.Z))
          case (ComparisonType.Equal, BranchIfFalse(lbl)) => return code :+ ZLine.jumpR(ctx, lbl, IfFlagClear(ZFlag.Z))
          case (ComparisonType.NotEqual, BranchIfTrue(lbl)) => return code :+ ZLine.jumpR(ctx, lbl, IfFlagClear(ZFlag.Z))
          case (ComparisonType.NotEqual, BranchIfFalse(lbl)) => return code :+ ZLine.jumpR(ctx, lbl, IfFlagSet(ZFlag.Z))
          case _ =>
        }
      case _ =>
    }

    val (calculated, useBC) = calculateRight match {
      case List(ZLine0(LD_16, TwoRegisters(ZRegister.HL, ZRegister.IMM_16), c)) =>
        (calculateLeft :+ ZLine.ldImm16(ZRegister.BC, c)) -> true
      case List(ZLine0(LD_16, TwoRegisters(ZRegister.HL, ZRegister.MEM_ABS_16), c)) if ctx.options.flag(CompilationFlag.EmitZ80Opcodes) =>
        (calculateLeft :+ ZLine.ldAbs16(ZRegister.BC, c)) -> true
      case _ =>
        if (calculateLeft.exists(Z80ExpressionCompiler.changesBC)) {
          if (calculateLeft.exists(Z80ExpressionCompiler.changesDE)) {
            calculateRight ++ List(ZLine.register(ZOpcode.PUSH, ZRegister.HL)) ++ Z80ExpressionCompiler.fixTsx(ctx, calculateLeft) ++ List(ZLine.register(ZOpcode.POP, ZRegister.BC)) -> true
          } else {
            calculateRight ++ List(ZLine.ld8(ZRegister.D, ZRegister.H), ZLine.ld8(ZRegister.E, ZRegister.L)) ++ calculateLeft -> false
          }
        } else {
          calculateRight ++ List(ZLine.ld8(ZRegister.B, ZRegister.H), ZLine.ld8(ZRegister.C, ZRegister.L)) ++ calculateLeft -> true
        }
    }
    val (effectiveCompType, label) = branches match {
      case BranchIfFalse(la) => ComparisonType.negate(compType) -> la
      case BranchIfTrue(la) => compType -> la
    }
    if (ctx.options.flag(CompilationFlag.EmitZ80Opcodes) && !ComparisonType.isSigned(compType)) {
      val calculateFlags = calculated ++ List(
        ZLine.register(ZOpcode.OR, ZRegister.A),
        ZLine.registers(ZOpcode.SBC_16, ZRegister.HL, if (useBC) ZRegister.BC else ZRegister.DE))
      if (branches == NoBranching) return calculateFlags
      val jump = effectiveCompType match {
        case Equal => ZLine.jump(label, IfFlagSet(ZFlag.Z))
        case NotEqual => ZLine.jump(label, IfFlagClear(ZFlag.Z))
        case LessUnsigned => ZLine.jump(label, IfFlagSet(ZFlag.C))
        case GreaterOrEqualUnsigned => ZLine.jump(label, IfFlagClear(ZFlag.C))
        case _ => ???
      }
      calculateFlags :+ jump
    } else if (compType == Equal || compType == NotEqual) {
      val calculateFlags = calculated ++ List(
        ZLine.ld8(A, L),
        ZLine.register(XOR, if (useBC) C else E),
        ZLine.ld8(L, A),
        ZLine.ld8(A, H),
        ZLine.register(XOR, if (useBC) B else D),
        ZLine.register(OR, L))
      if (branches == NoBranching) return calculateFlags
      val jump = effectiveCompType match {
        case Equal => ZLine.jump(label, IfFlagSet(ZFlag.Z))
        case NotEqual => ZLine.jump(label, IfFlagClear(ZFlag.Z))
        case _ => throw new IllegalStateException()
      }
      calculateFlags :+ jump
    } else {
      val calculateFlags = calculated ++ List(
              ZLine.ld8(A, L),
              ZLine.register(SUB, if (useBC) C else E),
              ZLine.ld8(A, H),
              ZLine.register(SBC, if (useBC) B else D))
      if (branches == NoBranching) return calculateFlags

      def fixBit7: List[ZLine] = {
        if (ctx.options.flag(CompilationFlag.EmitZ80Opcodes)) {
          val fixup = ctx.nextLabel("co")
          List(
            ZLine.jump(fixup, IfFlagClear(ZFlag.P)),
            ZLine.imm8(ZOpcode.XOR, 0x80),
            ZLine.label(fixup))
        } else Nil
      }

      val jump = effectiveCompType match {
        case LessUnsigned => List(ZLine.jump(label, IfFlagSet(ZFlag.C)))
        case GreaterOrEqualUnsigned => List(ZLine.jump(label, IfFlagClear(ZFlag.C)))
        case LessSigned =>
          if (ctx.options.flag(CompilationFlag.EmitIntel8080Opcodes)) {
            fixBit7 ++ List(ZLine.jump(label, IfFlagSet(ZFlag.S)))
          } else {
            fixBit7 ++ List(
              ZLine.register(ZOpcode.BIT7, ZRegister.A),
              ZLine.jump(label, IfFlagClear(ZFlag.Z)))
          }
        case GreaterOrEqualSigned =>
          if (ctx.options.flag(CompilationFlag.EmitIntel8080Opcodes)) {
            fixBit7 ++ List(ZLine.jump(label, IfFlagClear(ZFlag.S)))
          } else {
            fixBit7 ++ List(
              ZLine.register(ZOpcode.BIT7, ZRegister.A),
              ZLine.jump(label, IfFlagSet(ZFlag.Z)))
          }
        case _ => ???
      }
      calculateFlags ++ jump
    }
  }

  def compileLongRelativeComparison(ctx: CompilationContext, compType: ComparisonType.Value, l: Expression, r: Expression, branches: BranchSpec): List[ZLine] = {
    handleConstantComparison(ctx, compType, l, r, branches).foreach(return _)
    compType match {
      case Equal | NotEqual => throw new IllegalArgumentException
      case GreaterUnsigned | LessOrEqualUnsigned | GreaterSigned | LessOrEqualSigned =>
        return compileLongRelativeComparison(ctx, ComparisonType.flip(compType), r, l, branches)
      case _ => ()
    }
    val lt = Z80ExpressionCompiler.getExpressionType(ctx, l)
    val rt = Z80ExpressionCompiler.getExpressionType(ctx, r)
    val size = lt.size max rt.size
    val calculateLeft = Z80ExpressionCompiler.compileByteReads(ctx, l, size, ZExpressionTarget.HL)
    val calculateRight = Z80ExpressionCompiler.compileByteReads(ctx, r, size, ZExpressionTarget.BC)
    val preserveHl = isBytesFromHL(calculateLeft)
    val preserveBc = isBytesFromBC(calculateRight)
    val calculateFlags = calculateLeft.zip(calculateRight).zipWithIndex.flatMap { case ((lb, rb), i) =>
      import ZOpcode._
      import ZRegister._
      val sub = if (i == 0) SUB else SBC
      var compareBytes = (lb, rb) match {
        case (List(ZLine0(LD, TwoRegisters(A, _), _)),
        List(ZLine0(LD, TwoRegisters(A, IMM_8), param))) =>
          lb :+ ZLine.imm8(sub, param)
        case (List(ZLine0(LD, TwoRegisters(A, _), _)),
        List(ZLine0(LD, TwoRegisters(A, reg), _))) if reg != MEM_ABS_8 =>
          lb :+ ZLine.register(sub, reg)
        case (List(ZLine0(LD, TwoRegisters(A, _), _)), _) =>
          Z80ExpressionCompiler.stashAFIfChangedF(ctx, rb :+ ZLine.ld8(E, A)) ++ lb :+ ZLine.register(sub, E)
        case _ =>
          if (preserveBc || preserveHl) ??? // TODO: preserve HL/BC for the next round of comparisons
        var compileArgs = rb ++ List(ZLine.ld8(E, A)) ++ Z80ExpressionCompiler.stashDEIfChanged(ctx, lb) ++ List(ZLine.ld8(D, A))
          if (i > 0) compileArgs = Z80ExpressionCompiler.stashAFIfChangedF(ctx, compileArgs)
          compileArgs ++ List(ZLine.ld8(A, D), ZLine.register(sub, E))
      }
      if (i > 0 && preserveBc) compareBytes = Z80ExpressionCompiler.stashBCIfChanged(ctx, compareBytes)
      if (i > 0 && preserveHl) compareBytes = Z80ExpressionCompiler.stashHLIfChanged(ctx, compareBytes)
      compareBytes
    }
    if (branches == NoBranching) return calculateFlags
    val jump = (compType, branches) match {
      case (Equal, BranchIfTrue(label)) => ZLine.jump(label, IfFlagSet(ZFlag.Z))
      case (Equal, BranchIfFalse(label)) => ZLine.jump(label, IfFlagClear(ZFlag.Z))
      case (NotEqual, BranchIfTrue(label)) => ZLine.jump(label, IfFlagClear(ZFlag.Z))
      case (NotEqual, BranchIfFalse(label)) => ZLine.jump(label, IfFlagSet(ZFlag.Z))
      case (LessUnsigned, BranchIfTrue(label)) => ZLine.jump(label, IfFlagSet(ZFlag.C))
      case (LessUnsigned, BranchIfFalse(label)) => ZLine.jump(label, IfFlagClear(ZFlag.C))
      case (GreaterOrEqualUnsigned, BranchIfTrue(label)) => ZLine.jump(label, IfFlagClear(ZFlag.C))
      case (GreaterOrEqualUnsigned, BranchIfFalse(label)) => ZLine.jump(label, IfFlagSet(ZFlag.C))
      case _ => ???
    }
    calculateFlags :+ jump
  }

  def compileLongEqualityComparison(ctx: CompilationContext, compType: ComparisonType.Value, l: Expression, r: Expression, branches: BranchSpec): List[ZLine] = {
    handleConstantComparison(ctx, compType, l, r, branches).foreach(return _)
    val lt = Z80ExpressionCompiler.getExpressionType(ctx, l)
    val rt = Z80ExpressionCompiler.getExpressionType(ctx, r)
    val size = lt.size max rt.size
    val calculateLeft = Z80ExpressionCompiler.compileByteReads(ctx, l, size, ZExpressionTarget.HL)
    val calculateRight = Z80ExpressionCompiler.compileByteReads(ctx, r, size, ZExpressionTarget.BC)
    val preserveHl = isBytesFromHL(calculateLeft)
    val preserveBc = isBytesFromBC(calculateRight)
    val innerLabel = ctx.nextLabel("cp")
    val (jump, epilogue) = (compType, branches) match {
      case (Equal, BranchIfTrue(label)) =>
        ZLine.jump(innerLabel, IfFlagClear(ZFlag.Z)) -> ZLine.jump(label, IfFlagSet(ZFlag.Z))
      case (NotEqual, BranchIfFalse(label)) =>
        ZLine.jump(innerLabel, IfFlagClear(ZFlag.Z)) -> ZLine.jump(label, IfFlagSet(ZFlag.Z))
      case (Equal, BranchIfFalse(label)) =>
        ZLine.jump(label, IfFlagClear(ZFlag.Z)) -> ZLine.jump(label, IfFlagClear(ZFlag.Z))
      case (NotEqual, BranchIfTrue(label)) =>
        ZLine.jump(label, IfFlagClear(ZFlag.Z)) -> ZLine.jump(label, IfFlagClear(ZFlag.Z))
      case (_, NoBranching) => ZLine.implied(ZOpcode.NOP) -> ZLine.implied(ZOpcode.NOP)
      case _ => throw new IllegalArgumentException
    }
    val calculateFlags = calculateLeft.zip(calculateRight).zipWithIndex.flatMap { case ((lb, rb), i) =>
      var compareBytes = {
        import ZOpcode._
        import ZRegister._
        (lb, rb) match {
          case (_, List(ZLine0(LD, TwoRegisters(A, IMM_8), param))) =>
            lb :+ ZLine.imm8(CP, param)
          case (List(ZLine0(LD, TwoRegisters(A, IMM_8), param)), _) =>
            rb :+ ZLine.imm8(CP, param)
          case (List(ZLine0(LD, TwoRegisters(A, _), _)),
          List(ZLine0(LD, TwoRegisters(A, reg), _))) if reg != MEM_ABS_8 =>
            lb :+ ZLine.register(CP, reg)
          case (List(ZLine0(LD, TwoRegisters(A, reg), _)),
          List(ZLine0(LD, TwoRegisters(A, _), _))) if reg != MEM_ABS_8 =>
            rb :+ ZLine.register(CP, reg)
          case (List(ZLine0(LD, TwoRegisters(A, _), _)), _) =>
            (rb :+ ZLine.ld8(E, A)) ++ lb :+ ZLine.register(CP, E)
          case _ =>
            var actualLb = lb
            if (i == 0 && preserveBc) actualLb = Z80ExpressionCompiler.stashBCIfChanged(ctx, actualLb)
            actualLb ++ List(ZLine.ld8(E, A)) ++ Z80ExpressionCompiler.stashDEIfChanged(ctx, rb) :+ ZLine.register(CP, E)
        }
      }
      if (i > 0 && preserveBc) compareBytes = Z80ExpressionCompiler.stashBCIfChanged(ctx, compareBytes)
      if (i > 0 && preserveHl) compareBytes = Z80ExpressionCompiler.stashHLIfChanged(ctx, compareBytes)
      if (i != size - 1 && branches != NoBranching) compareBytes :+ jump else compareBytes
    }
    if (branches == NoBranching) calculateFlags
    else calculateFlags ++ List(epilogue, ZLine.label(innerLabel))
  }


  private def isBytesFromHL(calculateLeft: List[List[ZLine]]) = {
    calculateLeft(1) match {
      case List(ZLine0(ZOpcode.LD, TwoRegisters(ZRegister.A, ZRegister.H), _)) => true
      case _ => false
    }
  }

  private def isBytesFromBC(calculateLeft: List[List[ZLine]]) = {
    calculateLeft(1) match {
      case List(ZLine0(ZOpcode.LD, TwoRegisters(ZRegister.A, ZRegister.B), _)) => true
      case _ => false
    }
  }
}
