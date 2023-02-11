package Manager

import CacheSNN.{CacheSNN, NocCore}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

class Manager extends NocCore {
  override val supportAsMemMaster = true
  override val supportAsMemSlave = false

  val io = new Bundle {
    val externalMemory = master(Axi4(CacheSNN.axiMasterConfig))
    val ctrl = slave(AxiLite4(CacheSNN.axiLiteSlaveConfig))
  }
  stub()
}
