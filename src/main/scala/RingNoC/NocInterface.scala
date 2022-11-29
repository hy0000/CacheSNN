package RingNoC

import spinal.core._
import spinal.lib._

object NocInterface {
  def apply(dataWidth: Int) = Stream(Fragment(Bits(dataWidth bits)))
}

object NocInterfaceLocal{
  def apply(dataWidth: Int) = new NocInterfaceLocal(dataWidth)
}

class NocInterfaceLocal(dataWidth:Int) extends Bundle with IMasterSlave {
  val send, rec = NocInterface(dataWidth)

  override def asMaster(): Unit = {
    master(send)
    slave(rec)
  }
}