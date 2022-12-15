package Manager

import CacheSNN.{NocCore, CacheSNN}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.Apb3
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}

class Manager extends NocCore {
  val io = new Bundle {
    val externalMemory = master(Axi4(CacheSNN.externalMemoryAxi4Config))
    val ctrl = slave(Apb3(CacheSNN.apbConfig))
  }
  stub()
}
