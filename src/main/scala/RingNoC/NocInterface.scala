package RingNoC

import spinal.core._
import spinal.core.internals.BaseNode
import spinal.lib._

class NocInterface extends Bundle {
  val flit = Bits(64 bits)

  def setHead(dest: UInt, src: UInt, custom: Bits): Unit = {
    flit := Seq(
      dest.resized(4), B(0, 4 bits),
      src.resized(4), B(0, 4 bits),
      custom.resized(48)
    ).reduce(_ ## _).asBits
  }

  def dest: UInt = flit(63 downto 60).asUInt
  def src: UInt = flit(55 downto 52).asUInt
  def custom: Bits = flit(47 downto 0)
}

object NocInterface {
  def apply() = Stream(Fragment(new NocInterface))
}

class NocPackageHead extends Bundle {
  val dest = UInt(4 bits)
  val src = UInt(4 bits)
  val custom = Bits(48 bits)
}

class NocPackage extends Bundle with IMasterSlave {
  // behaviour is like the axi aw/w channel
  // body should not fire earlier than head
  val head = Stream(new NocPackageHead)
  val body = Stream(Fragment(Bits(64 bits)))

  override def asMaster(): Unit = {
    master(head)
    slave(body)
  }
}

object NocInterfaceLocal{
  def apply() = new NocInterfaceLocal()
}

class NocInterfaceLocal extends Bundle with IMasterSlave {
  val send, rec = new NocPackage

  override def asMaster(): Unit = {
    master(send)
    slave(rec)
  }
}