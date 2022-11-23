package NoC

import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple.PipelinedMemoryBus

case class NocConfig(dataWidth:Int = 64,
                     regAddrWidth:Int = 5)

case class NocInterface(c:NocConfig) extends Bundle with IMasterSlave {
  import c._

  val regCtrl = PipelinedMemoryBus(regAddrWidth, dataWidth)
  val dataSend = Stream(Bits(dataWidth bits))
  val dataRec = Stream(Bits())

  override def asMaster(): Unit = {
    master(regCtrl, dataRec)
    slave(dataSend)
  }
}