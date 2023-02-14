package CacheSNN.sim

import spinal.core._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axi.sim._

object AxiMemSim {
  def apply(axi:Axi4, clockDomain: ClockDomain): AxiMemSim = new AxiMemSim(axi, clockDomain)
}

class AxiMemSim(axi:Axi4, clockDomain: ClockDomain){
  val dWidth = axi.config.bytePerWord
  val mem = AxiMemorySim(axi, clockDomain, AxiMemorySimConfig())
  mem.start()

  def write(addrBase: Long, data:Seq[BigInt]): Unit = {
    for ((d, i) <- data.zipWithIndex) {
      val addr = addrBase + i * dWidth
      mem.memory.writeBigInt(addr, data = d, width = dWidth)
    }
  }

  def read(addrBase: Long, length: Int): Seq[BigInt] ={
    (0 until length).map{ i =>
      val addr = addrBase + i * dWidth
      mem.memory.readBigInt(addr, length = dWidth)
    }
  }

  def assertData(addrBase: Long, data: Seq[BigInt]): Unit ={
    val dInMem = read(addrBase, data.length)
    assert(data == dInMem, s"at ${addrBase.toHexString}")
  }
}
