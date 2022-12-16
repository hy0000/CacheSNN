package CacheSNN

import RingNoC.{NocInterface, NocInterfaceLocal}
import Util.{MemAccessBus, MemAccessBusConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.bram.{BRAM, BRAMConfig}
import spinal.lib.fsm._

import scala.language.implicitConversions


/**
 *NoC package format
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

class BasePackageHead extends Bundle {
  val dest = UInt(4 bits)
  val packageType: PackageType.C = PackageType()
  val field0 = Bool()
  val field1 = Bits(8 bits)
  val id = UInt(4 bits)
  val field2 = Bits(32 bits)

  def toNocCustomField: Bits = {
    packageType.asBits.resized(3) ## field0 ## field1 ## id ## field2
  }

  def assignFromNocCustomField(b:Bits): Unit ={
    packageType := b(47 downto 45).asInstanceOf[PackageType.C]
    field0 := b(44)
    field2 := b(43 downto 36)
    id := b(35 downto 32).asUInt
    field2 := b(31 downto 0)
  }
}

abstract class PackageBase[T<:Data] extends Bundle with IMasterSlave {
  // behaviour is like the axi aw/w channel
  // body should not fire earlier than head
  val head:Stream[T]
  val body = Stream(Fragment(Bits(64 bits)))

  override def asMaster(): Unit = {
    master(head, body)
  }
}

class BasePackage extends PackageBase[BasePackageHead] {
  val head = Stream(new BasePackageHead)
}

class AerPackageHead extends Bundle {
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

class AerPackage extends PackageBase[AerPackageHead] {
  val head = Stream(new AerPackageHead)
}

abstract class NocCore extends Component {
  val noc = master(NocInterfaceLocal())

  val interface = new Bundle {
    val regBus = master(BRAM(BRAMConfig(32, 8))) // for reg ctrl
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32))) // as memory slave for accessing
    val aer = master(new AerPackage) // decoded aer data
    val localSend = slave(new BasePackage) // for user logic to send raw packet
  }

  val nocUnPacker = new NocUnPacker
  val nocPacker = new NocPacker

  nocUnPacker.io.nocRec << noc.rec
  nocUnPacker.io.regBus <> interface.regBus
  nocUnPacker.io.dataBus <> interface.dataBus
  nocUnPacker.io.aer <> interface.aer

  nocPacker.io.rspRecId << nocUnPacker.io.rspRecId
  nocPacker.io.localSend <> interface.localSend

  noc.send << StreamArbiterFactory.fragmentLock.lowerFirst.on(
    Seq(nocUnPacker.io.rspSend, nocPacker.io.send)
  )
}

class NocUnPacker extends Component {
  val io = new Bundle {
    val nocRec = slave(NocInterface())
    val regBus = master(BRAM(BRAMConfig(32, 8)))
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32)))
    val aer = master(new AerPackage)
    val rspRecId = master(Stream(UInt(4 bits)))
    val rspSend = master(NocInterface())
  }
  stub()
}

class NocPacker extends Component {
  val io = new Bundle {
    val send = master(NocInterface())
    val rspRecId = slave(Stream(UInt(4 bits)))
    val localSend = slave(new BasePackage)
  }
  val maxPending = 16
  stub()
}