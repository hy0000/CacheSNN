package Util

import spinal.core._
import spinal.lib._

case class MemWriteCmd(dataWidth:Int, addrWidth:Int) extends Bundle {
  val address = UInt(addrWidth bits)
  val data = Bits(dataWidth bits)
}

case class MemReadWrite(dataWidth:Int, addrWidth:Int) extends Bundle with IMasterSlave {
  val read = MemReadPort(Bits(dataWidth bits), addrWidth)
  val write = Flow(MemWriteCmd(dataWidth, addrWidth))

  override def asMaster(): Unit = {
    master(read, write)
  }
}
