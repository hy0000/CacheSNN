package RingNoC

import spinal.core._
import spinal.lib._

class NocInterface extends Bundle {
  val flit = Bits(64 bits)

  def setHead(dest: UInt, src:UInt, custom: Bits): Unit = {
    flit := Seq(
      dest.resize(4),
      B(0, 4 bits),
      src.resize(4),
      B(0, 4 bits),
      custom.resize(48)
    ).reduce(_ ## _).asBits
  }

  def dest: UInt = flit(63 downto 60).asUInt
  def src: UInt = flit(55 downto 52).asUInt
  def custom: Bits = flit(47 downto 0)
}

object NocInterface {
  def apply() = Stream(Fragment(new NocInterface))
}

object NocInterfaceLocal{
  def apply() = new NocInterfaceLocal()
}

class NocInterfaceLocal extends Bundle with IMasterSlave {
  val send, rec = NocInterface()

  override def asMaster(): Unit = {
    master(send)
    slave(rec)
  }
}