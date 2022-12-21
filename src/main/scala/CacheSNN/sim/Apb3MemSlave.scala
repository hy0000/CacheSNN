package CacheSNN.sim

import spinal.core.ClockDomain
import spinal.lib.bus.amba3.apb.Apb3
import spinal.lib.bus.amba3.apb.sim.Apb3Monitor
import spinal.lib.bus.amba4.axi.sim.SparseMemory

object Apb3MemSlave {
  def apply(apb: Apb3, clockDomain: ClockDomain) = new Apb3MemSlave(apb, clockDomain)
}

class Apb3MemSlave(apb: Apb3, clockDomain: ClockDomain) extends Apb3Monitor(apb, clockDomain){
  val mem = SparseMemory()

  override def onRead(address: BigInt) = {
    mem.read(address.toLong)
  }

  override def onWrite(address: BigInt, value: Byte) = {
    mem.write(address.toLong, value)
  }
}