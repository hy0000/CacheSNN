package CacheSNN

import RingNoC.NocInterfaceLocal
import Util.{MemAccessBus, MemAccessBusConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.bram.{BRAM, BRAMConfig, BRAMSlaveFactory}

/**
 *NoC package format
 *|----------------------- 64b head ------------------------------|- 64b body -|
 *|---- noc protocol ----|------------- custom -------------------|
 *| 4b   | 4b | 4b  | 4b | 48b                                    |   -        |
 *| dest | 0  | src | 0  | —                                      |            |
 *
 *                       |------------ sys protocol --------------|
 *                       | 3b    | 1b  | 8b   | 4b    | 32b       |
 *                       | R_CMD | w   | addr | id    | data      |   -        |
 *                       | R_RSP | 0          | id    | data      |   -        |
 *                       | D_CMD | w   | len  | id    | addr      |   data     |
 *                       | D_RSP | 0   | len  | id    | 0         |   data     |
 *                       | AER   | 0   | len  | -                 |   —        |
 *
 *                                            |--- AER protocol --|
 *                                            | 4b    | 16b | 16b |
 *                                            | pre   | 0   | nid |   nid mask |
 *                                            | post  | 0   | nid |   nid mask |
 *                                            | curr  | 0   | nid |   data     |
 *                                            | W_R/W | 0   | nid |   data     |
 **/
object PACKAGE_TYPE extends SpinalEnum {
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

  def apply() = new AerPackage

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

class AerPackage extends Bundle with IMasterSlave {
  val flit = Stream(Fragment(Bits(64 bits)))

  override def asMaster() = {
    master(flit)
  }
}

abstract class NocCore extends Component {
  val interface = new Bundle {
    val noc = slave(NocInterfaceLocal(CacheSNN.nocConfig))
    val data = slave(MemAccessBus(MemAccessBusConfig(64, 32)))
    val rawSend = master(cloneOf(noc.send))
    val aerS = slave(AER())
    val aerM = master(AER())
  }

  val regBus = BRAM(BRAMConfig(32, 8))
  val regSlaveFactory = BRAMSlaveFactory(regBus)

  stub()
}