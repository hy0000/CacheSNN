package RingNoC

import spinal.core._
import spinal.lib._

case class NocConfig(dataWidth:Int)

object NocInterface {
  def apply(c:NocConfig) = Stream(Fragment(Bits(c.dataWidth bits)))
}

object NocInterfaceLocal{
  def apply(c:NocConfig) = new NocInterfaceLocal(c)
}

class NocInterfaceLocal(c:NocConfig) extends Bundle with IMasterSlave {
  val send, rec = NocInterface(c)

  override def asMaster(): Unit = {
    master(send)
    slave(rec)
  }

  def setHead(dest:UInt, data:Bits): Unit ={
    send.fragment := dest.resized(4) ## data.resized(c.dataWidth - 4)
  }
}