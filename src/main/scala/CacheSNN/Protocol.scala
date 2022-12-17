package CacheSNN

import spinal.core._
import spinal.lib._

/**
 *NoC packet format
 *|-------------------------- 64b head ---------------------------------|- 64b body -|
 *|---- noc protocol ----|---------------- custom ----------------------|
 *| 4b   | 4b | 4b  | 4b | 48b                                          |   -        |
 *| dest | 0  | src | 0  | —                                            |            |
 *
 *                       |--------------- base protocol ----------------|
 *                       | 3b    | 1b  | 8b   | 4b  | 32b               |
 *                       | R_CMD | w   | addr | id  | data              |   -        |
 *                       | R_RSP | 0          | id  | data              |   -        |
 *                       | D_CMD | w   | len  | id  | addr              |   data     |
 *                       | D_RSP | 0          | id  | 0                 |   data     |
 *                       | AER   | 0   | len  | id  |                   |   —        |
 *
 *                                                  |--- AER protocol --|
 *                                                  | 4b    | 12b | 16b |
 *                                                  | pre   | 0   | nid |   nid mask |
 *                                                  | post  | 0   | nid |   nid mask |
 *                                                  | curr  | 0   | nid |   data     |
 *                                                  | W_R/W | 0   | nid |   data     |
 **/
object PackageType extends SpinalEnum {
  val R_CMD = newElement("reg-cmd")
  val R_RSP = newElement("reg-rsp")
  val D_CMD = newElement("data-cmd")
  val D_RSP = newElement("data-rsp")
  val AER = newElement("aer")

  defaultEncoding = SpinalEnumEncoding("staticEncoding")(
    R_CMD -> 0,
    R_RSP -> 1,
    D_CMD -> 2,
    D_RSP -> 3,
    AER -> 4,
  )
}

object AER {
  val nidWidth = 16

  object TYPE extends SpinalEnum {
    val W_READ = newElement("weight-read")
    val W_WRITE = newElement("weight-write")
    val PRE_SPIKE = newElement("pre-spike")
    val POST_SPIKE = newElement("pre-spike")
    val CURRENT = newElement("current")

    defaultEncoding = SpinalEnumEncoding("staticEncoding")(
      W_READ -> 0,
      W_WRITE -> 1,
      PRE_SPIKE -> 2,
      POST_SPIKE -> 3,
      CURRENT -> 4
    )
  }
}

class BasePacketHead extends Bundle {
  val dest = UInt(4 bits)
  val packetType: PackageType.C = PackageType()
  val field0 = Bool()
  val field1 = Bits(8 bits)
  val id = UInt(4 bits)
  val field2 = Bits(32 bits)

  def toNocCustomField: Bits = {
    packetType.asBits.resized(3) ## field0 ## field1 ## id ## field2
  }

  def assignFromNocCustomField(b:Bits): Unit ={
    packetType := b(47 downto 45).asInstanceOf[PackageType.C]
    field0 := b(44)
    field2 := b(43 downto 36)
    id := b(35 downto 32).asUInt
    field2 := b(31 downto 0)
  }
}

abstract class PacketBase[T<:Data] extends Bundle with IMasterSlave {
  // behaviour is like the axi aw/w channel
  // body should not fire earlier than head
  val head:Stream[T]
  val body = Stream(Fragment(Bits(64 bits)))

  override def asMaster(): Unit = {
    master(head, body)
  }
}

class BasePacket extends PacketBase[BasePacketHead] {
  val head = Stream(new BasePacketHead)
}

class AerPacketHead extends Bundle {
  val eventType: AER.TYPE.C = AER.TYPE()
  val nid = UInt(16 bits)

  def toAerCustomField: Bits = {
    eventType.asBits.resized(4) ## B(0, 12 bits) ## nid
  }

  def assignFromAerCustomField(b: Bits): Unit = {
    eventType := b(31 downto 28).asInstanceOf[AER.TYPE.C]
    nid := b(15 downto 0).asUInt
  }
}

class AerPacket extends PacketBase[AerPacketHead] {
  val head = Stream(new AerPacketHead)
}