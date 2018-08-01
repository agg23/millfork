package millfork.assembly.z80.opt

import millfork.assembly.AssemblyOptimization
import millfork.assembly.z80.{opt, _}
import millfork.assembly.z80.ZOpcode._
import millfork.env.{CompoundConstant, Constant, MathOperator, NumericConstant}
import millfork.node.ZRegister
import ZRegister._

/**
  * Optimizations valid for Intel8080, Z80, EZ80 and Sharp
  * @author Karol Stasiak
  */
object AlwaysGoodI80Optimizations {

  def change8BitLoadTarget(line: ZLine, newTarget: ZRegister.Value): ZLine = {
    line match {
      case ZLine(LD, TwoRegistersOffset(_, s, o), p, _) => ZLine(LD, TwoRegistersOffset(newTarget, s, o), p)
      case ZLine(LD, TwoRegisters(_, s), p, _) => ZLine(LD, TwoRegisters(newTarget, s), p)
    }
  }

  def for7Registers(f: ZRegister.Value => AssemblyRuleSet) = MultipleAssemblyRules(
    List(ZRegister.A, ZRegister.B, ZRegister.C, ZRegister.D, ZRegister.E, ZRegister.H, ZRegister.L).map(f))

  def for5LargeRegisters(f: ZRegister.Value => AssemblyRuleSet) = MultipleAssemblyRules(
    List(ZRegister.HL, ZRegister.BC, ZRegister.DE, ZRegister.IX, ZRegister.IY).map(f))

  def for6Registers(f: ZRegister.Value => AssemblyRuleSet) = MultipleAssemblyRules(
    List(ZRegister.B, ZRegister.C, ZRegister.D, ZRegister.E, ZRegister.H, ZRegister.L).map(f))

  val UsingKnownValueFromAnotherRegister = new RuleBasedAssemblyOptimization("Using known value from another register",
    needsFlowInfo = FlowInfoRequirement.ForwardFlow,
    for7Registers(register =>
      (Elidable & IsRegular8BitLoadFrom(register) & MatchRegister(register, 0)) ~~> ((code, ctx) =>
        code.map(x => x.copy(
          parameter = NumericConstant(ctx.get[Int](0), 1),
          registers = x.registers match {
            case TwoRegisters(t, _) => TwoRegisters(t, ZRegister.IMM_8)
            case TwoRegistersOffset(t@(ZRegister.MEM_IX_D | ZRegister.MEM_IY_D), _, o) => TwoRegistersOffset(t, ZRegister.IMM_8, o)
            case TwoRegistersOffset(t, ZRegister.MEM_IX_D | ZRegister.MEM_IY_D, _) => TwoRegisters(t, ZRegister.IMM_8)
          }
        )))
    ),
    (Elidable & MatchSourceIxOffsetOf8BitLoad(0) & MatchValueAtMatchedIxOffset(0, 1)) ~~> ((code, ctx) =>
      code.map(x => x.copy(
        parameter = NumericConstant(ctx.get[Int](1), 1),
        registers = x.registers match {
          case TwoRegisters(t, _) => TwoRegisters(t, ZRegister.IMM_8)
          case TwoRegistersOffset(t@(ZRegister.MEM_IX_D | ZRegister.MEM_IY_D), _, o) => TwoRegistersOffset(t, ZRegister.IMM_8, o)
          case TwoRegistersOffset(t, ZRegister.MEM_IX_D | ZRegister.MEM_IY_D, _) => TwoRegisters(t, ZRegister.IMM_8)
        }
      ))
      ),
    for6Registers(register =>
      (Elidable & HasRegisterParam(register) & HasOpcodeIn(Set(AND, ADD, ADC, SUB, SBC, XOR, OR, CP)) & MatchRegister(register, 0)) ~~> ((code, ctx) =>
        code.map(x => x.copy(
          parameter = NumericConstant(ctx.get[Int](0), 1),
          registers = OneRegister(ZRegister.IMM_8)
        )))
    ),
    (Elidable & MatchIxOffset(0) & HasOpcodeIn(Set(AND, ADD, ADC, SUB, SBC, XOR, OR, CP)) & MatchValueAtMatchedIxOffset(0, 1)) ~~> ((code, ctx) =>
      code.map(x => x.copy(
        parameter = NumericConstant(ctx.get[Int](1), 1),
        registers = OneRegister(ZRegister.IMM_8)
      ))
      ),
  )

  val ReloadingKnownValueFromMemory = new RuleBasedAssemblyOptimization("Reloading known value from memory",
    needsFlowInfo = FlowInfoRequirement.ForwardFlow,
    for7Registers(register =>
      Is8BitLoad(ZRegister.MEM_HL, register) ~
        (Linear & Not(Changes(ZRegister.H)) & Not(Changes(ZRegister.L)) & Not(ChangesMemory) & Not(Changes(register)) & Not(IsRegular8BitLoadFrom(ZRegister.MEM_HL))).* ~
        (Elidable & Is8BitLoad(register, ZRegister.MEM_HL)) ~~> { code => code.init }
    ),
    for7Registers(register =>
      Is8BitLoad(ZRegister.MEM_HL, register) ~
        (Linear & Not(Changes(ZRegister.H)) & Not(Changes(ZRegister.L)) & Not(ChangesMemory) & Not(Changes(register)) & Not(IsRegular8BitLoadFrom(ZRegister.MEM_HL))).* ~
        (Elidable & IsRegular8BitLoadFrom(ZRegister.MEM_HL)) ~~> { code =>
        val last = code.last
        code.init :+ last.copy(registers = TwoRegisters(last.registers.asInstanceOf[TwoRegisters].target, register))
      }
    ),
    (Is8BitLoad(ZRegister.MEM_ABS_8, ZRegister.A) & MatchParameter(0)).captureLine(1) ~
      (Linear & DoesntChangeMemoryAt(1) & Not(Changes(ZRegister.A))).* ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.MEM_ABS_8) & MatchParameter(0)) ~~> { code => code.init },

    (Is8BitLoad(ZRegister.MEM_HL, ZRegister.A) & MatchConstantInHL(0)).captureLine(1) ~
      (Linear & DoesntChangeMemoryAt(1) & Not(Changes(ZRegister.A))).* ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.MEM_ABS_8) & MatchParameter(0)) ~~> { code => code.init },

    (Is8BitLoad(ZRegister.MEM_ABS_8, ZRegister.A) & MatchParameter(0)).captureLine(1) ~
      (Linear & DoesntChangeMemoryAt(1) & Not(Changes(ZRegister.A))).* ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.MEM_HL) & MatchConstantInHL(0)) ~~> { code => code.init },

    (Is8BitLoad(ZRegister.MEM_HL, ZRegister.A) & MatchConstantInHL(0)).captureLine(1) ~
      (Linear & DoesntChangeMemoryAt(1) & Not(Changes(ZRegister.A))).* ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.MEM_HL) & MatchConstantInHL(0)) ~~> { code => code.init },

    (Is16BitLoad(ZRegister.MEM_ABS_16, ZRegister.HL) & MatchParameter(0)).captureLine(1) ~
      (Linear & DoesntChangeMemoryAt(1) & Not(Changes(ZRegister.HL))).* ~
      (Elidable & Is16BitLoad(ZRegister.HL, ZRegister.MEM_ABS_16) & MatchParameter(0)) ~~> { code => code.init },

    (Is8BitLoad(ZRegister.MEM_ABS_8, ZRegister.A) & MatchParameter(0) & MatchRegister(ZRegister.A, 2)).captureLine(1) ~
      (Linear & DoesntChangeMemoryAt(1) & Not(Is8BitLoad(ZRegister.A, ZRegister.MEM_ABS_8))).* ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.MEM_ABS_8) & MatchParameter(0)) ~~> { (code, ctx) =>
      code.init :+ ZLine.ldImm8(ZRegister.A, ctx.get[Int](2))
    },

    (Is16BitLoad(ZRegister.MEM_ABS_16, ZRegister.HL) & MatchParameter(0) & MatchConstantInHL(2)).captureLine(1) ~
      (Linear & DoesntChangeMemoryAt(1) & Not(Is16BitLoad(ZRegister.HL, ZRegister.MEM_ABS_16))).* ~
      (Elidable & Is16BitLoad(ZRegister.HL, ZRegister.MEM_ABS_16) & MatchParameter(0)) ~~> { (code, ctx) =>
      code.init :+ ZLine.ldImm16(ZRegister.HL, ctx.get[Constant](2))
    },
  )

  val PointlessLoad = new RuleBasedAssemblyOptimization("Pointless load",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    // 0-6
    for7Registers(register =>
      (Elidable & Is8BitLoadTo(register) & DoesntMatterWhatItDoesWith(register)) ~~> (_ => Nil)
    ),
    // 7-11
    for5LargeRegisters(register =>
      (Elidable & Is16BitLoadTo(register) & DoesntMatterWhatItDoesWith(register)) ~~> (_ => Nil)
    ),
    // 12-18
    for7Registers(register =>
      (Is8BitLoad(register, ZRegister.IMM_8) & MatchImmediate(0)) ~
        (Linear & Not(Changes(register))).* ~
        (Elidable & Is8BitLoad(register, ZRegister.IMM_8) & MatchImmediate(0)) ~~> (_.init)
    ),
    // 19-23
    for5LargeRegisters(register =>
      (Is16BitLoad(register, ZRegister.IMM_16) & MatchImmediate(0)) ~
        (Linear & Not(Changes(register))).* ~
        (Elidable & Is16BitLoad(register, ZRegister.IMM_16) & MatchImmediate(0)) ~~> (_.init)
    ),
    // 24
    (Elidable & Is8BitLoadTo(ZRegister.MEM_HL)) ~
      (Linear & Not(ConcernsMemory) & Not(Changes(ZRegister.HL))).* ~
      Is8BitLoadTo(ZRegister.MEM_HL) ~~> (_.tail),
    // 25
    (Elidable & Is8BitLoadTo(ZRegister.MEM_DE)) ~
      (Linear & Not(ConcernsMemory) & Not(Changes(ZRegister.DE))).* ~
      Is8BitLoadTo(ZRegister.MEM_DE) ~~> (_.tail),
    // 26
    (Elidable & Is8BitLoadTo(ZRegister.MEM_BC)) ~
      (Linear & Not(ConcernsMemory) & Not(Changes(ZRegister.BC))).* ~
      Is8BitLoadTo(ZRegister.MEM_BC) ~~> (_.tail),
    // 27
    (Elidable & MatchTargetIxOffsetOf8BitLoad(0) & MatchUnimportantIxOffset(0)) ~~> (_ => Nil),
    // 28-34
    for7Registers(register =>
      (Elidable & Is8BitLoadTo(register) & NoOffset & MatchSourceRegisterAndOffset(1)) ~
        (Linear & Not(Concerns(register)) & DoesntChangeMatchedRegisterAndOffset(1)).* ~
        (Elidable & IsRegular8BitLoadFrom(register) & DoesntMatterWhatItDoesWith(register)) ~~> { code =>
        val last = code.last
        val head = code.head
        code.tail.init :+ ZLine(LD, (last.registers, head.registers) match {
          case (TwoRegisters(t, _), TwoRegisters(_, s)) => TwoRegisters(t, s)
          case (TwoRegistersOffset(t, _, o), TwoRegisters(_, s)) => TwoRegistersOffset(t, s, o)
          case (TwoRegisters(t, _), TwoRegistersOffset(_, s, o)) => TwoRegistersOffset(t, s, o)
          case _ => ???
        }, head.parameter)
      }
    ),
    // 35-41
    for7Registers(register =>
      (Elidable & Is8BitLoad(register, register)) ~~> (_ => Nil)
    ),
    // 42-48
    for6Registers(register =>
      (Elidable & Is8BitLoadTo(register) & MatchSourceRegisterAndOffset(0) & MatchParameterOrNothing(1)) ~
        (Linear & Not(Concerns(register)) & DoesntChangeMatchedRegisterAndOffset(0)).* ~
        (Elidable & HasOpcodeIn(Set(ADD, ADC, XOR, OR, AND, CP, SUB, SBC)) &
          HasRegisters(OneRegister(register)) & DoesntMatterWhatItDoesWith(register)) ~~> { (code, ctx) =>
        code.tail.init :+ code.last.copy(registers = ctx.get[RegisterAndOffset](0).toOneRegister, parameter = ctx.get[Constant](1))
      }
    ),

    // 49-54
    MultipleAssemblyRules {
      import ZRegister._
      val regs = Seq((BC, B, C), (DE, D, E), (HL, H, L))
      for {
        (t, th, tl) <- regs
        (s, sh, sl) <- regs
        if t != HL
        if t != s
      } yield {
        // TODO: make it a bit more universal
        (Elidable & Is8BitLoad(th, sh)) ~
          (Elidable & Is8BitLoad(tl, sl)) ~
          (HasOpcode(OR) & HasRegisterParam(A)).?.capture(1) ~
          (Elidable & HasOpcodeIn(Set(ADD_16, ADC_16, SBC_16)) & HasRegisters(TwoRegisters(HL, t)) & DoesntMatterWhatItDoesWith(t)) ~~> {
          (code, ctx) =>
            ctx.get[List[ZLine]](1) :+ code.last.copy(registers = TwoRegisters(HL, s))
        }
      }
    },
    // 55-59
    for5LargeRegisters(register =>
      (Is16BitLoad(register, ZRegister.MEM_ABS_16) & MatchParameter(0)).captureLine(1) ~
        (Linear & Not(Changes(register)) & DoesntChangeMemoryAt(1)).* ~
        (Elidable & Is16BitLoad(register, ZRegister.MEM_ABS_16) & MatchParameter(0)) ~~> (_.init)
    ),
    // 60
    (HasOpcode(LD) & MatchSourceRegisterAndOffset(0) & MatchTargetRegisterAndOffset(1)) ~
      Where(ctx => ctx.get[RegisterAndOffset](0).register != ZRegister.MEM_ABS_8 && ctx.get[RegisterAndOffset](1).register != ZRegister.MEM_ABS_8) ~
      (Elidable & HasOpcode(LD) & MatchSourceRegisterAndOffset(1) & MatchTargetRegisterAndOffset(0)) ~~> (_.init)
  )

  val PointlessStackStashing = new RuleBasedAssemblyOptimization("Pointless stack stashing",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    // 0-4
    for5LargeRegisters(register => {
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(register)) ~
        (Linear & Not(HasOpcodeIn(Set(POP,PUSH))) & Not(Changes(register)) & Not(ReadsStackPointer)).* ~
      (Elidable & HasOpcode(POP) & HasRegisterParam(register)) ~~> (_.tail.init)
    }),
    // 5
    (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.DE) & DoesntMatterWhatItDoesWith(ZRegister.D)) ~
      (Linear & Not(HasOpcodeIn(Set(POP,PUSH))) & Not(Changes(ZRegister.D)) & Not(ReadsStackPointer)).* ~
      (Elidable & HasOpcode(POP) & HasRegisterParam(ZRegister.DE) & DoesntMatterWhatItDoesWith(ZRegister.D)) ~~> {code =>
      ZLine.ld8(ZRegister.D, ZRegister.E) :: (code.tail.init :+ ZLine.ld8(ZRegister.E, ZRegister.D))
    },
    // 6
    (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.DE) & DoesntMatterWhatItDoesWith(ZRegister.E)) ~
      (Linear & Not(HasOpcodeIn(Set(POP,PUSH))) & Not(Changes(ZRegister.E)) & Not(ReadsStackPointer)).* ~
      (Elidable & HasOpcode(POP) & HasRegisterParam(ZRegister.DE) & DoesntMatterWhatItDoesWith(ZRegister.E)) ~~> { code =>
      ZLine.ld8(ZRegister.E, ZRegister.D) :: (code.tail.init :+ ZLine.ld8(ZRegister.D, ZRegister.E))
    },
    // 7
    (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.BC) & DoesntMatterWhatItDoesWith(ZRegister.B)) ~
      (Linear & Not(HasOpcodeIn(Set(POP,PUSH))) & Not(Changes(ZRegister.B)) & Not(ReadsStackPointer)).* ~
      (Elidable & HasOpcode(POP) & HasRegisterParam(ZRegister.BC) & DoesntMatterWhatItDoesWith(ZRegister.B)) ~~> (code =>
      ZLine.ld8(ZRegister.B, ZRegister.C) :: (code.tail.init :+ ZLine.ld8(ZRegister.C, ZRegister.B))
      ),
    // 8
    (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.BC) & DoesntMatterWhatItDoesWith(ZRegister.C)) ~
      (Linear & Not(HasOpcodeIn(Set(POP,PUSH))) & Not(Changes(ZRegister.C)) & Not(ReadsStackPointer)).* ~
      (Elidable & HasOpcode(POP) & HasRegisterParam(ZRegister.BC) & DoesntMatterWhatItDoesWith(ZRegister.C)) ~~> { code =>
      ZLine.ld8(ZRegister.C, ZRegister.B) :: (code.tail.init :+ ZLine.ld8(ZRegister.B, ZRegister.C))
    },
    // 9
    (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.HL) & DoesntMatterWhatItDoesWith(ZRegister.H)) ~
      (Linear & Not(HasOpcodeIn(Set(POP,PUSH))) & Not(Changes(ZRegister.H)) & Not(ReadsStackPointer)).* ~
      (Elidable & HasOpcode(POP) & HasRegisterParam(ZRegister.HL) & DoesntMatterWhatItDoesWith(ZRegister.H)) ~~> { code =>
      ZLine.ld8(ZRegister.H, ZRegister.L) :: (code.tail.init :+ ZLine.ld8(ZRegister.L, ZRegister.H))
    },
    // 10
    (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.HL) & DoesntMatterWhatItDoesWith(ZRegister.L)) ~
      (Linear & Not(HasOpcodeIn(Set(POP,PUSH))) & Not(Changes(ZRegister.L)) & Not(ReadsStackPointer)).* ~
      (Elidable & HasOpcode(POP) & HasRegisterParam(ZRegister.HL) & DoesntMatterWhatItDoesWith(ZRegister.L)) ~~> { code =>
      ZLine.ld8(ZRegister.L, ZRegister.H) :: (code.tail.init :+ ZLine.ld8(ZRegister.H, ZRegister.L))
    },
    // 11-15
    for5LargeRegisters(register => {
      (Elidable & HasOpcode(LD_16) & HasRegisters(TwoRegisters(register, ZRegister.IMM_16))) ~
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(register) & DoesntMatterWhatItDoesWith(register)) ~
        (Linear & Not(HasOpcodeIn(Set(POP,PUSH))) & Not(ReadsStackPointer)).* ~
      (Elidable & HasOpcode(POP) & HasRegisterParam(register)) ~~> { code =>
        code.drop(2).init :+ code.head
      }
    }),

  )

  private def simplifiable16BitAddWithSplitTarget(targetH: ZRegister.Value, targetL: ZRegister.Value, target: ZRegister.Value, source: ZRegister.Value) = MultipleAssemblyRules(List(
    (Is8BitLoad(targetH, ZRegister.IMM_8) & MatchImmediate(1)) ~
      (Linear & Not(Changes(target))).* ~
      (Is8BitLoad(targetL, ZRegister.IMM_8) & MatchImmediate(2)) ~
      (Linear & Not(Changes(target)) & Not(Changes(source))).* ~
      (Is16BitLoad(source, ZRegister.IMM_16) & MatchImmediate(0)) ~
      (Linear & Not(Changes(target)) & Not(Changes(source))).* ~
      (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(target, source)) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      val value = (ctx.get[Constant](0) + ctx.get[Constant](1).asl(8) + ctx.get[Constant](2)).quickSimplify
      code.init :+ ZLine.ldImm16(target, value)
    },
    (Is8BitLoad(targetL, ZRegister.IMM_8) & MatchImmediate(2)) ~
      (Linear & Not(Changes(target))).* ~
      (Is8BitLoad(targetH, ZRegister.IMM_8) & MatchImmediate(1)) ~
      (Linear & Not(Changes(target)) & Not(Changes(source))).* ~
      (Is16BitLoad(source, ZRegister.IMM_16) & MatchImmediate(0)) ~
      (Linear & Not(Changes(target)) & Not(Changes(source))).* ~
      (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(target, source)) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      val value = (ctx.get[Constant](0) + ctx.get[Constant](1).asl(8) + ctx.get[Constant](2)).quickSimplify
      code.init :+ ZLine.ldImm16(target, value)
    },
    (Is16BitLoad(source, ZRegister.IMM_16) & MatchImmediate(0)) ~
      (Linear & Not(Changes(targetL)) & Not(Changes(source))).* ~
      (Is8BitLoad(targetL, ZRegister.IMM_8) & MatchImmediate(2)) ~
      (Linear & Not(Changes(target)) & Not(Changes(source))).* ~
      (Is8BitLoad(targetH, ZRegister.IMM_8) & MatchImmediate(1)) ~
      (Linear & Not(Changes(target)) & Not(Changes(source))).* ~
      (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(target, source)) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      val value = (ctx.get[Constant](0) + ctx.get[Constant](1).asl(8) + ctx.get[Constant](2)).quickSimplify
      code.init :+ ZLine.ldImm16(target, value)
    },
    (Is16BitLoad(source, ZRegister.IMM_16) & MatchImmediate(0)) ~
      (Linear & Not(Changes(targetH)) & Not(Changes(source))).* ~
      (Is8BitLoad(targetH, ZRegister.IMM_8) & MatchImmediate(1)) ~
      (Linear & Not(Changes(target)) & Not(Changes(source))).* ~
      (Is8BitLoad(targetL, ZRegister.IMM_8) & MatchImmediate(2)) ~
      (Linear & Not(Changes(target)) & Not(Changes(source))).* ~
      (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(target, source)) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      val value = (ctx.get[Constant](0) + ctx.get[Constant](1).asl(8) + ctx.get[Constant](2)).quickSimplify
      code.init :+ ZLine.ldImm16(target, value)
    },
  ))

  val SimplifiableMaths = new RuleBasedAssemblyOptimization("Simplifiable maths",
    needsFlowInfo = FlowInfoRequirement.BothFlows,
    for6Registers(register =>
      (Elidable & HasOpcode(ADD) & MatchRegister(ZRegister.A, 0) & HasRegisterParam(register) & MatchRegister(register, 1) &
        DoesntMatterWhatItDoesWithFlags) ~~> ((code, ctx) => List(ZLine.ldImm8(ZRegister.A, (ctx.get[Int](0) + ctx.get[Int](1)) & 0xff))),
    ),
    simplifiable16BitAddWithSplitTarget(ZRegister.H, ZRegister.L, ZRegister.HL, ZRegister.BC),
    simplifiable16BitAddWithSplitTarget(ZRegister.H, ZRegister.L, ZRegister.HL, ZRegister.DE),

    (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.BC)) & MatchRegister(ZRegister.BC, 0) & MatchRegister(ZRegister.HL, 1) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      List(ZLine.ldImm16(ZRegister.HL, ctx.get[Int](0) + ctx.get[Int](1)))
    },
    (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.DE)) & MatchRegister(ZRegister.DE, 0) & MatchRegister(ZRegister.HL, 1) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      List(ZLine.ldImm16(ZRegister.HL, ctx.get[Int](0) + ctx.get[Int](1)))
    },
    (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.HL)) & MatchRegister(ZRegister.HL, 1) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      List(ZLine.ldImm16(ZRegister.HL, 2 * ctx.get[Int](1) & 0xffff))
    },

    (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.BC)) & HasRegister(ZRegister.BC, 0) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      Nil
    },
    (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.DE)) & HasRegister(ZRegister.DE, 0) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      Nil
    },

    (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.BC)) & HasRegister(ZRegister.BC, 1) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      List(ZLine.register(ZOpcode.INC_16, ZRegister.HL))
    },
    (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.DE)) & HasRegister(ZRegister.DE, 1) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      List(ZLine.register(ZOpcode.INC_16, ZRegister.HL))
    },


    (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.BC)) & MatchRegister(ZRegister.BC, 0) & MatchConstantInHL(1) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      List(ZLine.ldImm16(ZRegister.HL, (ctx.get[Constant](1) + ctx.get[Int](0)).quickSimplify))
    },
    (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.DE)) & MatchRegister(ZRegister.DE, 0) & MatchConstantInHL(1) & DoesntMatterWhatItDoesWithFlags) ~~> { (code, ctx) =>
      List(ZLine.ldImm16(ZRegister.HL, (ctx.get[Constant](1) + ctx.get[Int](0)).quickSimplify))
    },


    (Elidable & Is8BitLoad(ZRegister.D, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.E, ZRegister.L)) ~
      (Elidable & Is8BitLoadTo(ZRegister.L)) ~
      (Elidable & Is8BitLoadTo(ZRegister.H)) ~
      (HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.DE)) & DoesntMatterWhatItDoesWith(ZRegister.D, ZRegister.E)) ~~> { code =>
      List(
        change8BitLoadTarget(code(2), ZRegister.E),
        change8BitLoadTarget(code(3), ZRegister.D),
        code.last)
    },
    (Elidable & Is8BitLoad(ZRegister.D, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.E, ZRegister.L)) ~
      (Elidable & Is8BitLoadTo(ZRegister.H)) ~
      (Elidable & Is8BitLoadTo(ZRegister.L)) ~
      (HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.DE)) & DoesntMatterWhatItDoesWith(ZRegister.D, ZRegister.E)) ~~> { code =>
      List(
        change8BitLoadTarget(code(2), ZRegister.D),
        change8BitLoadTarget(code(3), ZRegister.E),
        code.last)
    },

    (Elidable & HasOpcodeIn(Set(ADD, OR, XOR, SUB)) & Has8BitImmediate(0) & DoesntMatterWhatItDoesWithFlags) ~~> (_ => Nil),
    (Elidable & HasOpcode(AND) & Has8BitImmediate(0xff) & DoesntMatterWhatItDoesWithFlags) ~~> (_ => Nil),
    (Elidable & HasOpcode(AND) & Has8BitImmediate(0) & DoesntMatterWhatItDoesWithFlags) ~~> (_ => List(ZLine.ldImm8(ZRegister.A, 0))),
    (Elidable & HasOpcode(XOR) & Has8BitImmediate(0xff) & DoesntMatterWhatItDoesWithFlags) ~~> (_ => List(ZLine.implied(CPL))),


    (Elidable & HasOpcode(OR) & Match8BitImmediate(1) & MatchRegister(ZRegister.A, 0)) ~
      (Elidable & HasOpcodeIn(Set(JP, JR)) & HasRegisters(IfFlagSet(ZFlag.S)) & DoesntMatterWhatItDoesWithFlags) ~
      Where(ctx => ctx.get[Constant](1).isInstanceOf[NumericConstant]) ~~> { (code, ctx) =>
      val value = (ctx.get[Int](0) | ctx.get[NumericConstant](1).value).toInt & 0xff
      if (value.&(0x80) == 0) List(ZLine.ldImm8(ZRegister.A, value))
      else List(ZLine.ldImm8(ZRegister.A, value), code.last.copy(registers = NoRegisters))
    },

    (Elidable & HasOpcode(OR) & Match8BitImmediate(1) & MatchRegister(ZRegister.A, 0)) ~
      (Elidable & HasOpcodeIn(Set(JP, JR)) & HasRegisters(IfFlagClear(ZFlag.S)) & DoesntMatterWhatItDoesWithFlags) ~
      Where(ctx => ctx.get[Constant](1).isInstanceOf[NumericConstant]) ~~> { (code, ctx) =>
      val value = (ctx.get[Int](0) | ctx.get[NumericConstant](1).value).toInt & 0xff
      if (value.&(0x80) != 0) List(ZLine.ldImm8(ZRegister.A, value))
      else List(ZLine.ldImm8(ZRegister.A, value), code.last.copy(registers = NoRegisters))
    },

    (Elidable & HasOpcode(ADD) & Match8BitImmediate(1) & MatchRegister(ZRegister.A, 0) & DoesntMatterWhatItDoesWithFlags) ~~> {(code, ctx) =>
      List(ZLine.ldImm8(ZRegister.A, (ctx.get[Constant](1) + ctx.get[Int](0)).quickSimplify))
    },

    (Elidable & HasOpcode(SUB) & Match8BitImmediate(1) & MatchRegister(ZRegister.A, 0) & DoesntMatterWhatItDoesWithFlags) ~~> {(code, ctx) =>
      List(ZLine.ldImm8(ZRegister.A, (NumericConstant(ctx.get[Int](0) & 0xff, 1) - ctx.get[Constant](1)).quickSimplify))
    },

    (Elidable & HasOpcode(OR) & Match8BitImmediate(1) & MatchRegister(ZRegister.A, 0) & DoesntMatterWhatItDoesWithFlags) ~~> {(code, ctx) =>
      List(ZLine.ldImm8(ZRegister.A, CompoundConstant(MathOperator.Or, NumericConstant(ctx.get[Int](0) & 0xff, 1), ctx.get[Constant](1)).quickSimplify))
    },

    (Elidable & HasOpcode(XOR) & Match8BitImmediate(1) & MatchRegister(ZRegister.A, 0) & DoesntMatterWhatItDoesWithFlags) ~~> {(code, ctx) =>
      List(ZLine.ldImm8(ZRegister.A, CompoundConstant(MathOperator.Exor, NumericConstant(ctx.get[Int](0) & 0xff, 1), ctx.get[Constant](1)).quickSimplify))
    },

    (Elidable & HasOpcode(AND) & Match8BitImmediate(1) & MatchRegister(ZRegister.A, 0) & DoesntMatterWhatItDoesWithFlags) ~~> {(code, ctx) =>
      List(ZLine.ldImm8(ZRegister.A, CompoundConstant(MathOperator.And, NumericConstant(ctx.get[Int](0) & 0xff, 1), ctx.get[Constant](1)).quickSimplify))
    },

    (Elidable & HasOpcode(ADD) & Match8BitImmediate(1) & MatchRegister(ZRegister.A, 0)) ~
      (Elidable & HasOpcode(DAA) & DoesntMatterWhatItDoesWithFlags) ~~> {(code, ctx) =>
      List(ZLine.ldImm8(ZRegister.A, CompoundConstant(MathOperator.DecimalPlus, NumericConstant(ctx.get[Int](0) & 0xff, 1), ctx.get[Constant](1)).quickSimplify))
    },

    (Elidable & HasOpcode(SUB) & Match8BitImmediate(1) & MatchRegister(ZRegister.A, 0)) ~
      (Elidable & HasOpcode(DAA) & DoesntMatterWhatItDoesWithFlags) ~~> {(code, ctx) =>
      List(ZLine.ldImm8(ZRegister.A, CompoundConstant(MathOperator.DecimalMinus, NumericConstant(ctx.get[Int](0) & 0xff, 1), ctx.get[Constant](1)).quickSimplify))
    },

    (Elidable & (Is8BitLoadTo(ZRegister.A) | HasOpcode(LD) & HasRegisters(TwoRegisters(ZRegister.A, ZRegister.MEM_ABS_8)))) ~
      (Elidable & HasOpcode(SUB) & Has8BitImmediate(0)) ~
      (Is8BitLoadTo(ZRegister.A) | HasOpcode(LD) & HasRegisters(TwoRegisters(ZRegister.A, ZRegister.MEM_ABS_8))) ~
      (Elidable & HasOpcode(SBC)) ~~> { code =>
      List(code(2), code(3).copy(opcode = SUB))
    },

    (Elidable & HasOpcode(DEC) & MatchSoleRegisterAndOffset(0) & DoesntMatterWhatItDoesWithFlags) ~
    (Elidable & Linear & DoesntConcernMatchedRegisterAndOffset(0)).* ~
    (Elidable & HasOpcode(INC) & MatchSoleRegisterAndOffset(0) & DoesntMatterWhatItDoesWithFlags) ~~> (_.tail.init),

    (Elidable & HasOpcode(INC) & MatchSoleRegisterAndOffset(0) & DoesntMatterWhatItDoesWithFlags) ~
    (Elidable & Linear & DoesntConcernMatchedRegisterAndOffset(0)).* ~
    (Elidable & HasOpcode(DEC) & MatchSoleRegisterAndOffset(0) & DoesntMatterWhatItDoesWithFlags) ~~> (_.tail.init),

    (Elidable & HasOpcode(DEC_16) & MatchSoleRegisterAndOffset(0) & DoesntMatterWhatItDoesWithFlags) ~
    (Elidable & Linear & DoesntConcernMatchedRegisterAndOffset(0)).* ~
    (Elidable & HasOpcode(INC_16) & MatchSoleRegisterAndOffset(0) & DoesntMatterWhatItDoesWithFlags) ~~> (_.tail.init),

    (Elidable & HasOpcode(INC_16) & MatchSoleRegisterAndOffset(0) & DoesntMatterWhatItDoesWithFlags) ~
      (Elidable & Linear & DoesntConcernMatchedRegisterAndOffset(0)).* ~
      (Elidable & HasOpcode(DEC_16) & MatchSoleRegisterAndOffset(0) & DoesntMatterWhatItDoesWithFlags) ~~> (_.tail.init),

    (Elidable & Is8BitLoad(ZRegister.D, ZRegister.B)) ~
      (Elidable & Is8BitLoad(ZRegister.E, ZRegister.C)) ~
      (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.DE))) ~~> { code =>
      ZLine.registers(code.last.opcode, ZRegister.HL, ZRegister.BC) :: code.take(2)
    },
    (Elidable & Is8BitLoad(ZRegister.B, ZRegister.D)) ~
      (Elidable & Is8BitLoad(ZRegister.C, ZRegister.E)) ~
      (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.BC))) ~~> { code =>
      ZLine.registers(code.last.opcode, ZRegister.HL, ZRegister.DE) :: code.take(2)
    },

    (Elidable & HasOpcode(INC) & HasRegisterParam(ZRegister.A)) ~
      (Elidable & HasOpcode(ADD) & Has8BitImmediate(0xff) & DoesntMatterWhatItDoesWithFlags) ~~> (_ => Nil),

    (Elidable & HasOpcode(SUB) & Has8BitImmediate(0)) ~
      (Elidable & Is8BitLoadTo(ZRegister.A)) ~
      (Elidable & HasOpcode(SBC)) ~~> { code =>
      List(code(1), code.last.copy(opcode = SUB))
    },

    (Elidable & HasOpcode(ADD) & Has8BitImmediate(0) & DoesntMatterWhatItDoesWithFlagsExceptCarry) ~~> { _ =>
      List(ZLine.register(OR, A))
    },

    (Elidable & HasOpcode(SUB) & Has8BitImmediate(0) & DoesntMatterWhatItDoesWithFlagsExceptCarry) ~~> { _ =>
      List(ZLine.register(OR, A))
    },

    (Elidable & HasOpcode(ADC) & Has8BitImmediate(0) & HasClear(ZFlag.C) & DoesntMatterWhatItDoesWithFlagsExceptCarry) ~~> { _ =>
      List(ZLine.register(OR, A))
    },

    (Elidable & HasOpcode(ADC) & HasClear(ZFlag.C)) ~~> { code =>
      code.map(_.copy(opcode = ADD))
    },

    (Elidable & HasOpcode(SBC) & HasClear(ZFlag.C)) ~~> { code =>
      code.map(_.copy(opcode = SUB))
    },

    (Elidable & HasOpcodeIn(Set(OR, XOR)) & Has8BitImmediate(0) & DoesntMatterWhatItDoesWithFlags) ~~> ( _ => Nil),

    (Elidable & HasOpcode(OR) & HasRegisterParam(A) & DoesntMatterWhatItDoesWithFlags) ~~> (_ => Nil),

  )

  val FreeHL = new RuleBasedAssemblyOptimization("Free HL",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    (Elidable & Is16BitLoad(ZRegister.HL, ZRegister.IMM_16)) ~
      (Elidable & Is8BitLoadTo(ZRegister.MEM_HL) & DoesntMatterWhatItDoesWith(ZRegister.HL, ZRegister.A)) ~~> (code =>
      List(
        code(1).copy(registers = TwoRegisters(ZRegister.A, code(1).registers.asInstanceOf[TwoRegisters].source)),
        code.head.copy(opcode = LD, registers = TwoRegisters(ZRegister.MEM_ABS_8, ZRegister.A)),
      )),
    (Elidable & Is16BitLoad(ZRegister.HL, ZRegister.IMM_16)) ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.MEM_HL) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> (code =>
      List(
        code.head.copy(opcode = LD, registers = TwoRegisters(ZRegister.A, ZRegister.MEM_ABS_8)),
      )),
    (Elidable & Is16BitLoad(ZRegister.HL, ZRegister.IMM_16)) ~
      (Elidable & IsRegular8BitLoadFrom(ZRegister.MEM_HL) & DoesntMatterWhatItDoesWith(ZRegister.HL) & DoesntMatterWhatItDoesWith(ZRegister.A)) ~~> (code =>
      List(
        code.head.copy(opcode = LD, registers = TwoRegisters(ZRegister.A, ZRegister.MEM_ABS_8)),
        code(1).copy(registers = TwoRegisters(code(1).registers.asInstanceOf[TwoRegisters].target, ZRegister.A)),
      )),

    (Elidable & Is16BitLoad(ZRegister.HL, ZRegister.IMM_16)) ~
      (Elidable & Is8BitLoad(ZRegister.D, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.E, ZRegister.L) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> (code =>
      List(
        code.head.copy(registers = TwoRegisters(ZRegister.DE, ZRegister.IMM_16))
      )),
    (Elidable & Is16BitLoad(ZRegister.HL, ZRegister.IMM_16)) ~
      (Elidable & Is8BitLoad(ZRegister.B, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.C, ZRegister.L) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> (code =>
      List(
        code.head.copy(registers = TwoRegisters(ZRegister.BC, ZRegister.IMM_16))
      )),

    (Elidable & Is8BitLoad(ZRegister.H, ZRegister.B)) ~
    (Elidable & Is8BitLoad(ZRegister.L, ZRegister.C)) ~
    (Elidable & HasOpcodeIn(Set(INC_16, DEC_16, PUSH, POP)) & HasRegisterParam(ZRegister.HL)) ~
      (Elidable & Is8BitLoad(ZRegister.B, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.C, ZRegister.L) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> (code =>
      List(
        code(2).copy(registers = OneRegister(ZRegister.BC))
      )),

    (Elidable & Is8BitLoad(ZRegister.H, ZRegister.D)) ~
    (Elidable & Is8BitLoad(ZRegister.L, ZRegister.E)) ~
    (Elidable & HasOpcodeIn(Set(INC_16, DEC_16, PUSH, POP)) & HasRegisterParam(ZRegister.HL)) ~
      (Elidable & Is8BitLoad(ZRegister.D, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.E, ZRegister.L) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> (code =>
      List(
        code(2).copy(registers = OneRegister(ZRegister.DE))
      )),

    (Elidable & Is8BitLoad(ZRegister.H, ZRegister.D)) ~
    (Elidable & Is8BitLoad(ZRegister.L, ZRegister.E)) ~
    (Elidable & HasOpcodeIn(Set(INC_16, DEC_16, PUSH, POP)) & HasRegisterParam(ZRegister.HL)) ~
      (Elidable & Is8BitLoad(ZRegister.B, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.C, ZRegister.L) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> (code =>
      List(
        ZLine.ld8(ZRegister.B, ZRegister.D),
        ZLine.ld8(ZRegister.C, ZRegister.E),
        code(2).copy(registers = OneRegister(ZRegister.BC))
      )),

    (Elidable & Is8BitLoad(ZRegister.H, ZRegister.B)) ~
    (Elidable & Is8BitLoad(ZRegister.L, ZRegister.C)) ~
    (Elidable & HasOpcodeIn(Set(INC_16, DEC_16, PUSH, POP)) & HasRegisterParam(ZRegister.HL)) ~
      (Elidable & Is8BitLoad(ZRegister.D, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.E, ZRegister.L) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> (code =>
      List(
        ZLine.ld8(ZRegister.D, ZRegister.B),
        ZLine.ld8(ZRegister.E, ZRegister.C),
        code(2).copy(registers = OneRegister(ZRegister.DE))
      )),


    // 2 bytes more, but 3 cycles fewer and frees BC
    (Elidable & Is16BitLoad(ZRegister.BC, ZRegister.IMM_16) & MatchParameter(0)) ~
      (Linear & Not(Concerns(ZRegister.BC)) & Not(Concerns(ZRegister.HL))).*.capture(1) ~
      (Elidable & Is8BitLoad(ZRegister.L, ZRegister.A)) ~
      (Elidable & Is8BitLoadTo(ZRegister.H) & Has8BitImmediate(0)) ~
      (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.BC)) &
        DoesntMatterWhatItDoesWithFlagsExceptCarry &
        DoesntMatterWhatItDoesWith(ZRegister.BC)) ~~> { (code, ctx) =>
      val offset = ctx.get[Constant](0)
      ctx.get[List[ZLine]](1) ++ List(
        ZLine.imm8(ADD, offset.loByte),
        ZLine.ld8(ZRegister.L, ZRegister.A),
        ZLine.ldImm8(ZRegister.A, 0),
        ZLine.imm8(ADC, offset.hiByte),
        ZLine.ld8(ZRegister.H, ZRegister.A))
    },


    (Elidable & Is8BitLoad(ZRegister.H, ZRegister.D)) ~
      (Elidable & Is8BitLoad(ZRegister.L, ZRegister.E)) ~
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.HL) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> {_ =>
      List(ZLine.register(PUSH, ZRegister.DE))
    },
    (Elidable & Is8BitLoad(ZRegister.H, ZRegister.B)) ~
      (Elidable & Is8BitLoad(ZRegister.L, ZRegister.C)) ~
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.HL) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> {_ =>
      List(ZLine.register(PUSH, ZRegister.BC))
    },
    (Elidable & Is8BitLoad(ZRegister.D, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.E, ZRegister.L)) ~
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.DE) & DoesntMatterWhatItDoesWith(ZRegister.DE)) ~~> {_ =>
      List(ZLine.register(PUSH, ZRegister.HL))
    },
    (Elidable & Is8BitLoad(ZRegister.B, ZRegister.H)) ~
      (Elidable & Is8BitLoad(ZRegister.C, ZRegister.L)) ~
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.BC) & DoesntMatterWhatItDoesWith(ZRegister.BC)) ~~> {_ =>
      List(ZLine.register(PUSH, ZRegister.HL))
    },

    (Elidable & Is8BitLoad(ZRegister.L, ZRegister.E)) ~
      (Elidable & Is8BitLoad(ZRegister.H, ZRegister.D)) ~
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.HL) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> {_ =>
      List(ZLine.register(PUSH, ZRegister.DE))
    },
    (Elidable & Is8BitLoad(ZRegister.L, ZRegister.C)) ~
      (Elidable & Is8BitLoad(ZRegister.H, ZRegister.B)) ~
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.HL) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> {_ =>
      List(ZLine.register(PUSH, ZRegister.BC))
    },
    (Elidable & Is8BitLoad(ZRegister.E, ZRegister.L)) ~
      (Elidable & Is8BitLoad(ZRegister.D, ZRegister.H)) ~
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.DE) & DoesntMatterWhatItDoesWith(ZRegister.DE)) ~~> {_ =>
      List(ZLine.register(PUSH, ZRegister.HL))
    },
    (Elidable & Is8BitLoad(ZRegister.C, ZRegister.L)) ~
      (Elidable & Is8BitLoad(ZRegister.B, ZRegister.H)) ~
      (Elidable & HasOpcode(PUSH) & HasRegisterParam(ZRegister.BC) & DoesntMatterWhatItDoesWith(ZRegister.BC)) ~~> {_ =>
      List(ZLine.register(PUSH, ZRegister.HL))
    },

    (Elidable & Is8BitLoad(ZRegister.H, ZRegister.B)) ~
      (Elidable & Is8BitLoad(ZRegister.L, ZRegister.C)) ~
      (Elidable & HasOpcode(EX_DE_HL) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> { _ =>
      List(
        ZLine.ld8(ZRegister.D, ZRegister.B),
        ZLine.ld8(ZRegister.E, ZRegister.C))
    },

    (Elidable & HasOpcode(LD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.MEM_ABS_16)) & MatchParameter(0)) ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.L) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> { (code, ctx) =>
      List(ZLine.ldAbs8(ZRegister.A, ctx.get[Constant](0)))
    },

    (Elidable & HasOpcode(LD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.MEM_ABS_16)) & MatchParameter(0)) ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.H) & DoesntMatterWhatItDoesWith(ZRegister.HL)) ~~> { (code, ctx) =>
      List(ZLine.ldAbs8(ZRegister.A, (ctx.get[Constant](0) + 1).quickSimplify))
    },

    (Elidable & HasOpcode(LD_16) & HasRegisters(TwoRegisters(ZRegister.DE, ZRegister.MEM_ABS_16)) & MatchParameter(0)) ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.E) & DoesntMatterWhatItDoesWith(ZRegister.DE)) ~~> { (code, ctx) =>
      List(ZLine.ldAbs8(ZRegister.A, ctx.get[Constant](0)))
    },

    (Elidable & HasOpcode(LD_16) & HasRegisters(TwoRegisters(ZRegister.DE, ZRegister.MEM_ABS_16)) & MatchParameter(0)) ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.D) & DoesntMatterWhatItDoesWith(ZRegister.DE)) ~~> { (code, ctx) =>
      List(ZLine.ldAbs8(ZRegister.A, (ctx.get[Constant](0) + 1).quickSimplify))
    },

    (Elidable & HasOpcode(LD_16) & HasRegisters(TwoRegisters(ZRegister.BC, ZRegister.MEM_ABS_16)) & MatchParameter(0)) ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.C) & DoesntMatterWhatItDoesWith(ZRegister.DE)) ~~> { (code, ctx) =>
      List(ZLine.ldAbs8(ZRegister.A, ctx.get[Constant](0)))
    },

    (Elidable & HasOpcode(LD_16) & HasRegisters(TwoRegisters(ZRegister.BC, ZRegister.MEM_ABS_16)) & MatchParameter(0)) ~
      (Elidable & Is8BitLoad(ZRegister.A, ZRegister.B) & DoesntMatterWhatItDoesWith(ZRegister.BC)) ~~> { (code, ctx) =>
      List(ZLine.ldAbs8(ZRegister.A, (ctx.get[Constant](0) + 1).quickSimplify))
    },

    (Elidable & Is8BitLoad(ZRegister.D, ZRegister.B)) ~
    (Elidable & Is8BitLoad(ZRegister.E, ZRegister.C)) ~
      (Not(Concerns(ZRegister.DE)) & Not(Concerns(ZRegister.BC))).* ~
      (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.DE)) & DoesntMatterWhatItDoesWith(ZRegister.DE, ZRegister.BC)) ~~> { code =>
      code.drop(2).init :+ code.last.copy(registers = TwoRegisters(ZRegister.HL, ZRegister.BC))
    },

    (Elidable & Is8BitLoad(ZRegister.B, ZRegister.D)) ~
    (Elidable & Is8BitLoad(ZRegister.C, ZRegister.E)) ~
      (Not(Concerns(ZRegister.DE)) & Not(Concerns(ZRegister.BC))).* ~
      (Elidable & HasOpcode(ADD_16) & HasRegisters(TwoRegisters(ZRegister.HL, ZRegister.BC)) & DoesntMatterWhatItDoesWith(ZRegister.DE, ZRegister.BC)) ~~> { code =>
      code.drop(2).init :+ code.last.copy(registers = TwoRegisters(ZRegister.HL, ZRegister.DE))
    },
  )

  val UnusedCodeRemoval = new RuleBasedAssemblyOptimization("Unreachable code removal",
    needsFlowInfo = FlowInfoRequirement.NoRequirement,
    (HasOpcodeIn(Set(JP, JR)) & HasRegisters(NoRegisters)) ~ (Not(HasOpcode(LABEL)) & Elidable).+ ~~> (c => c.head :: Nil)
  )

  val UnusedLabelRemoval = new RuleBasedAssemblyOptimization("Unused label removal",
    needsFlowInfo = FlowInfoRequirement.JustLabels,
    (Elidable & HasOpcode(LABEL) & HasCallerCount(0)) ~~> (_ => Nil)
  )

  val BranchInPlaceRemoval = new RuleBasedAssemblyOptimization("Branch in place",
    needsFlowInfo = FlowInfoRequirement.NoRequirement,
    (HasOpcodeIn(Set(JP, JR)) & MatchJumpTarget(0) & Elidable) ~
      HasOpcodeIn(ZOpcodeClasses.NoopDiscards).* ~
      (HasOpcode(LABEL) & MatchJumpTarget(0)) ~~> (c => c.last :: Nil),
  )

  val SimplifiableShifting = new RuleBasedAssemblyOptimization("Simplifiable shifting",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlags) ~~> { _ =>
      List(
        ZLine.implied(RRCA),
        ZLine.imm8(AND, 0x80))
    },
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlags) ~~> { _ =>
      List(
        ZLine.implied(RRCA),
        ZLine.implied(RRCA),
        ZLine.imm8(AND, 0xc0))
    },
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SLA) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlags) ~~> { _ =>
      List(
        ZLine.implied(RRCA),
        ZLine.implied(RRCA),
        ZLine.implied(RRCA),
        ZLine.imm8(AND, 0xe0))
    },
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlags) ~~> { _ =>
      List(
        ZLine.implied(RLCA),
        ZLine.imm8(AND, 1))
    },
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlags) ~~> { _ =>
      List(
        ZLine.implied(RLCA),
        ZLine.implied(RLCA),
        ZLine.imm8(AND, 3))
    },
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A)) ~
    (Elidable & HasOpcode(SRL) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlags) ~~> { _ =>
      List(
        ZLine.implied(RLCA),
        ZLine.implied(RLCA),
        ZLine.implied(RLCA),
        ZLine.imm8(AND, 7))
    },
    (Elidable & HasOpcode(RR) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlagsExceptCarry) ~~> {_ =>
      List(ZLine.implied(RRA))
    },
    (Elidable & HasOpcode(RRC) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlagsExceptCarry) ~~> {_ =>
      List(ZLine.implied(RRCA))
    },
    (Elidable & HasOpcode(RL) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlagsExceptCarry) ~~> {_ =>
      List(ZLine.implied(RLA))
    },
    (Elidable & HasOpcode(RLC) & HasRegisterParam(ZRegister.A) & DoesntMatterWhatItDoesWithFlagsExceptCarry) ~~> {_ =>
      List(ZLine.implied(RLCA))
    },
  )

  val PointlessExdehl = new RuleBasedAssemblyOptimization("Pointless EX DE,HL",
      needsFlowInfo = FlowInfoRequirement.NoRequirement,

      (Elidable & HasOpcode(EX_DE_HL)) ~
        (Elidable & (
          HasOpcode(LD_16) & (HasRegisters(TwoRegisters(DE, IMM_16)) | HasRegisters(TwoRegisters(HL, IMM_16)) | HasRegisters(TwoRegisters(BC, IMM_16))) |
          Is8BitLoad(A, H) |
          Is8BitLoad(A, L) |
          Is8BitLoad(A, D) |
          Is8BitLoad(A, E) |
          Is8BitLoad(H, A) |
          Is8BitLoad(L, A) |
          Is8BitLoad(D, A) |
          Is8BitLoad(E, A) |
          HasOpcodeIn(Set(POP, PUSH, INC_16, DEC_16)) |
          HasOpcodeIn(Set(INC, DEC, ADD, ADC, SUB, SBC, RLA, RLA, RRCA, RRCA, RL, RR, RRC, RLC, AND, XOR, OR, CP))
          )).* ~
      (Elidable & HasOpcode(EX_DE_HL)) ~~> { code =>
        code.tail.init.map { line =>
          line.registers match {
            case OneRegister(HL) => line.copy(registers = OneRegister(DE))
            case OneRegister(DE) => line.copy(registers = OneRegister(HL))
            case TwoRegisters(HL, source) => line.copy(registers = TwoRegisters(DE, source))
            case TwoRegisters(DE, source) => line.copy(registers = TwoRegisters(HL, source))
            case TwoRegisters(H, r) => line.copy(registers = TwoRegisters(D, r))
            case TwoRegisters(L, r) => line.copy(registers = TwoRegisters(E, r))
            case TwoRegisters(D, r) => line.copy(registers = TwoRegisters(H, r))
            case TwoRegisters(E, r) => line.copy(registers = TwoRegisters(L, r))
            case TwoRegisters(r, H) => line.copy(registers = TwoRegisters(r, D))
            case TwoRegisters(r, L) => line.copy(registers = TwoRegisters(r, E))
            case TwoRegisters(r, D) => line.copy(registers = TwoRegisters(r, H))
            case TwoRegisters(r, E) => line.copy(registers = TwoRegisters(r, L))
            case _ => line
          }
        }
      },


    )


  val All: List[AssemblyOptimization[ZLine]] = List[AssemblyOptimization[ZLine]](
    BranchInPlaceRemoval,
    FreeHL,
    PointlessLoad,
    PointlessStackStashing,
    ReloadingKnownValueFromMemory,
    SimplifiableMaths,
    SimplifiableShifting,
    UnusedCodeRemoval,
    UnusedLabelRemoval,
    UsingKnownValueFromAnotherRegister,
  )
}

