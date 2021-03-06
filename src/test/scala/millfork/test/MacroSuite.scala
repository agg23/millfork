package millfork.test

import millfork.Cpu
import millfork.test.emu.{EmuBenchmarkRun, EmuCrossPlatformBenchmarkRun, EmuUnoptimizedCrossPlatformRun}
import org.scalatest.{FunSuite, Matchers}

/**
  * @author Karol Stasiak
  */
class MacroSuite extends FunSuite with Matchers {

  test("Most basic test") {
    EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8086, Cpu.Motorola6809)(
      """
        | macro void run(byte x) {
        |    output = x
        | }
        |
        | byte output @$c000
        |
        | void main () {
        |   byte a
        |   a = 7
        |   run(a)
        | }
      """.stripMargin) { m =>
      m.readByte(0xc000) should equal(7)
    }
  }

  test("Macros in assembly") {
    EmuCrossPlatformBenchmarkRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8086, Cpu.Motorola6809)(
      """
        | macro void run(byte x) {
        |    output = x
        | }
        |
        | byte output @$c000
        |
        | void main () {
        |   byte a
        |   a = 7
        |   asm {
        |     + run(a)
        |   }
        | }
      """.stripMargin) { m =>
      m.readByte(0xc000) should equal(7)
    }
  }

  test("Macros with loops and clashing variable names") {
    EmuUnoptimizedCrossPlatformRun(Cpu.Mos, Cpu.Z80, Cpu.Intel8086)(
      """
        | macro void run(byte x) {
        |    while x != 0 {
        |     output += 1
        |     x -= 1
        |    }
        | }
        |
        | byte output @$c000
        |
        | void main () {
        |   output = 0
        |   byte x
        |   x = 3
        |   run(x)
        |   x = 4
        |   run(x)
        | }
      """.stripMargin) { m =>
      m.readByte(0xc000) should equal(7)
    }
  }
}
