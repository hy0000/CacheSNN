package Util

import spinal.core._

class Dsp48E extends Component{
  val io = new Bundle {
    val A = in SInt(25 bits)
    val B = in SInt(18 bits)
    val D = in SInt(25 bits)
    val Pin = in SInt(48 bits)
    val Pout = out SInt(48 bits)
  }

  val stage0 = new Area {
    val A = RegNext(io.A)
    val D = RegNext(io.D)
  }

  val stage1 = new Area {
    val AD = RegNext(stage0.A + stage0.D)
    val B = RegNext(io.B)
  }

  val stage2 = new Area {
    val M = RegNext(stage1.AD * stage1.B)
  }

  io.Pout := RegNext(stage2.M + io.Pin)
}
