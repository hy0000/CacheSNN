package CacheSNN

import RingNoC.{NocInterfaceLocal, NocPackageHead, PackageBase}
import Util.{MemAccessBus, MemAccessBusConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.bram.{BRAM, BRAMConfig}

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

class BasePackageHead extends NocPackageHead {
  def pType: PackageType.C = custom(47 downto 45).asInstanceOf[PackageType.C]

  def write: Bool = custom(44)

  def len: UInt = custom(43 downto 36).asUInt

  def rAddr: UInt = custom(43 downto 36).asUInt

  def id: UInt = custom(35 downto 32).asUInt

  def rData: Bits = custom(31 downto 0)

  def dAddr: UInt = custom(31 downto 0).asUInt

  def setCustom(pt: PackageType.C, field1b: Bool, field8b: UInt, id: UInt, field32b: Bits): Unit = {
    custom := pt.asBits.resized(3) ## field1b ## field8b.resized(8) ## id.resized(4) ## field32b.resized(32)
  }

  def setRegCmd(write: Bool, addr: UInt, id: UInt, data: Bits): Unit = {
    setCustom(PackageType.R_CMD, write, addr, id, data)
  }

  def setRegRsp(id: UInt, data: Bits): Unit = {
    setCustom(PackageType.R_RSP, False, U(0), id, data)
  }

  def setDataCmd(write: Bool, len: UInt, id: UInt, addr: UInt): Unit = {
    setCustom(PackageType.D_CMD, write, len, id, addr.asBits)
  }

  def setDataRsp(id: UInt, data: Bits): Unit = {
    setCustom(PackageType.D_RSP, False, U(0), id, data)
  }

  def setAer(len: UInt, id: UInt, aerType: AER.TYPE.C, nid: UInt): Unit = {
    val aerField = aerType.asBits.resized(4) ## B(0, 12 bits) ## nid
    setCustom(PackageType.AER, False, len, id, aerField)
  }
}

class BasePackage extends PackageBase[BasePackageHead] {
  val head = Stream(new BasePackageHead)
}

class AerPackageHead extends Bundle {
  val eventType: AER.TYPE.C = AER.TYPE()
  val nid = UInt(16 bits)
}

class AerPackage extends PackageBase[AerPackageHead] {
  val head = Stream(new AerPackageHead)
}

abstract class NocCore extends Component {
  val noc = slave(NocInterfaceLocal())

  val interface = new Bundle {
    val regBus = slave(BRAM(BRAMConfig(32, 8)))
    val dataBus = slave(MemAccessBus(MemAccessBusConfig(64, 32)))
    val aerRaw = slave(new AerPackage)
    val pSend = master(new BasePackage)
  }

  val nocUnpack = new Area {
    val rsp = new BasePackage

  }

  val nocPack = new Area {

  }
}