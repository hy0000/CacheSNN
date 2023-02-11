package Manager

import CacheSNN._
import RingNoC.NocInterface
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class BpCmd() extends Bundle {
  val head = Bits(64 bits)
  val mAddr = UInt(32 bits)
}

class BpManager extends Component {
  val io = new Bundle {
    val cmd = slave(Stream(BpCmd()))
    val localSend = master(NocInterface())
    val readRsp = slave(BaseReadRsp())
    val writeRsp = slave(BaseWriteRsp())
    val axi = master(Axi4(CacheSNN.axiMasterConfig.copy(idWidth = 1)))
  }
  stub()
}
