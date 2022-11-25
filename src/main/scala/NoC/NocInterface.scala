package NoC

import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple.PipelinedMemoryBus

case class NocConfig(dataWidth: Int)

case class NocInterface(c:NocConfig) extends Bundle with IMasterSlave {
  import c._

  val send = Stream(Fragment(Bits(dataWidth bits)))
  val rec = Stream(Fragment(Bits(dataWidth bits)))

  override def asMaster(): Unit = {
    master(send)
    slave(rec)
  }
}