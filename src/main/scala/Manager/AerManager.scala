package Manager

import CacheSNN._
import RingNoC.NocInterface
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class PreSpikeCmd() extends Bundle {
  val nidBase = UInt(6 bits)
  val addrBase = UInt(22 bits)
}

class AerManager extends Component {
  val io = new Bundle {
    val cmd = slave(Stream(PreSpikeCmd()))
    val aer = slave(new AerPacket)
    val axi = master(Axi4(CacheSNN.axiMasterConfig.copy(idWidth = 1)))
    val localSend = master(NocInterface())
  }
  stub()
}
