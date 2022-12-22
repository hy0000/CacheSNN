package Util.sim

import Util.MemAccessBus
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim.SparseMemory

case class MemAccessBusMemSlave(bus:MemAccessBus, clockDomain: ClockDomain, readDelay:Int) {
  val mem = SparseMemory()

  case class Rsp(data:BigInt, last:Boolean, valid:Boolean = true)

  def defaultRsp:Rsp = Rsp(0, last = false, valid = false)

  val rspQueue = Array.fill(readDelay)(defaultRsp)
  var queuePush = 0
  var queuePop = 1

  bus.cmd.ready #= true
  bus.rsp.valid #= false

  clockDomain.onSamplings {
    if(bus.rsp.ready.toBoolean){
      queuePush = (queuePush + 1) % readDelay
      queuePop = (queuePop + 1) % readDelay
      bus.rsp.valid #= rspQueue(queuePop).valid
      bus.rsp.fragment #= rspQueue(queuePop).data
      bus.rsp.last #= rspQueue(queuePop).last
      rspQueue(queuePop) = defaultRsp
    }
  }

  fork{
    while (true) {
      clockDomain.waitSamplingWhere(bus.cmd.valid.toBoolean)
      val len = bus.cmd.len.toInt
      if (bus.cmd.write.toBoolean) {
        for (i <- 0 to len) {
          mem.writeBigInt(bus.cmd.address.toLong + (i << 3), bus.cmd.data.toBigInt, 8)
          if (i != len) {
            clockDomain.waitSamplingWhere(bus.cmd.valid.toBoolean)
          }
        }
      } else {
        bus.cmd.ready #= false
        val address = bus.cmd.address.toLong
        for (i <- 0 to len) {
          val data = mem.readBigInt(address + (i << 3), 8)
          val last = i == len
          rspQueue(queuePush) = Rsp(data, last)
          clockDomain.waitSamplingWhere(bus.rsp.ready.toBoolean)
        }
        bus.cmd.ready #= true
      }
    }
  }
}