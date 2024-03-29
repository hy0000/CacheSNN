package CacheSNN

import RingNoC.NocInterface
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
 *                       | R_RSP | w   | 0    | id  | data              |   -        |
 *                       | D_CMD | w   | len  | id  | addr              |   data     |
 *                       | D_RSP | w   | 0    | id  | 0                 |   data     |
 *                       | ERROR |                                      |            |
 *                       | AER   | 0          | id  |                   |   —        |
 *
 *                                                  |--- AER protocol --|
 *                                                  | 3b    | 13b | 16b |
 *                                                  | pre   | 0   | nid |   nid mask |
 *                                                  | post  | 0   | nid |   nid mask |
 *                                                  | curr  | 0   | nid |   data     |
 *                                                  | W_F   | 0   | nid |            |
 *                                                  | W_W   | 0   | nid |   data     |
 **/
object PacketType extends SpinalEnum {
  val R_CMD = newElement("reg_cmd")
  val R_RSP = newElement("reg_rsp")
  val D_CMD = newElement("data_cmd")
  val D_RSP = newElement("data_rsp")
  val AER = newElement("aer")
  val ERROR = newElement("error")

  defaultEncoding = SpinalEnumEncoding("staticEncoding")(
    R_CMD -> 0,
    R_RSP -> 1,
    D_CMD -> 2,
    D_RSP -> 3,
    AER -> 4,
    ERROR -> 5,
  )
}

object AER {
  val nidWidth = 16

  object TYPE extends SpinalEnum {
    val W_FETCH = newElement("weight_fetch")
    val W_WRITE = newElement("weight_write")
    val PRE_SPIKE = newElement("pre_spike")
    val POST_SPIKE = newElement("post_spike")
    val CURRENT = newElement("current")

    defaultEncoding = SpinalEnumEncoding("staticEncoding")(
      W_FETCH -> 0,
      W_WRITE -> 1,
      PRE_SPIKE -> 2,
      POST_SPIKE -> 3,
      CURRENT -> 4
    )
  }
}

object BasePacketHead {
  def apply(headRaw:Bits): BasePacketHead ={
    val bph = new BasePacketHead
    bph.dest := headRaw(63 downto 60).asUInt
    bph.src := headRaw(55 downto 52).asUInt
    bph.assignFromNocCustomField(headRaw(47 downto 0))
    bph
  }
}

class BasePacketHead extends Bundle {
  val dest = UInt(4 bits)
  val src = UInt(4 bits)
  val packetType: PacketType.C = PacketType()
  val write = Bool()
  val field1 = Bits(8 bits)
  val id = UInt(4 bits)
  val field2 = Bits(32 bits)

  def toNocCustomField: Bits = {
    packetType.asBits.resize(3) ## write ## field1 ## id ## field2
  }

  def assignFromNocCustomField(b:Bits): Unit ={
    packetType.assignFromBits(b(47 downto 45))
    write := b(44)
    field1 := b(43 downto 36)
    id := b(35 downto 32).asUInt
    field2 := b(31 downto 0)
  }

  def toNocRawHead: Bits = {
    dest ## B"0000" ## src ## B"0000" ## toNocCustomField
  }

  def isHeadOnlyPacket: Bool = {
    packetType===PacketType.R_CMD ||
      packetType===PacketType.R_RSP ||
      (!write && (packetType===PacketType.D_CMD || packetType===PacketType.D_RSP))
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
    PacketType.AER.asBits.resize(3) ## B(0, 13 bits) ##
      eventType.asBits.resize(3) ## B(0, 13 bits) ## nid
  }

  def assignFromAerCustomField(b: Bits): Unit = {
    eventType.assignFromBits(b(31 downto 29))
    nid := b(15 downto 0).asUInt
  }

  def isHeadOnlyPacket: Bool = {
    eventType===AER.TYPE.W_FETCH
  }
}

class AerPacket extends PacketBase[AerPacketHead] {
  val head = Stream(new AerPacketHead)

  def toNocInterface(dest: UInt, src: UInt = 0): Stream[Fragment[NocInterface]] = {
    val ret = NocInterface()
    val transBody = RegInit(False)
      .setWhen(head.fire && !head.isHeadOnlyPacket)
      .clearWhen(body.lastFire)

    when(transBody){
      ret.arbitrationFrom(body)
      ret.flit := body.fragment
      ret.last := body.last
      head.setBlocked()
    }otherwise{
      ret.arbitrationFrom(head)
      ret.setHead(dest, src, head.toAerCustomField)
      ret.last := head.isHeadOnlyPacket
      body.setBlocked()
    }
    ret
  }
}

class BaseReadRsp extends Bundle {
  val data = Bits(64 bits)
  val id = UInt(4 bits)
}

object BaseReadRsp {
  def apply(): Stream[Fragment[BaseReadRsp]] = Stream(Fragment(new BaseReadRsp))
}

class BaseWriteRsp extends Bundle {
  val id = UInt(4 bits)
}

object BaseWriteRsp {
  def apply(): Stream[BaseWriteRsp] = Stream(new BaseWriteRsp)
}