package Util.sim

import Util.MemReadWrite
import spinal.core.ClockDomain
import spinal.core.sim._

class MemReadWriteMemSlave(bus:MemReadWrite, clockDomain: ClockDomain) {
  def readDelay = 2
  val wordCount = 1<<bus.addrWidth
  val initValue = BigInt(0)
  val mem = Array.fill(wordCount)(initValue)

  val rspQueue = Array.fill(readDelay)(initValue)
  var queuePush = 0
  var queuePop = 1

  clockDomain.onSamplings {
    queuePush = (queuePush + 1) % readDelay
    queuePop = (queuePop + 1) % readDelay
    bus.read.rsp #= rspQueue(queuePop)
    rspQueue(queuePop) = initValue

    if(bus.read.cmd.valid.toBoolean){
      rspQueue(queuePush) = mem(bus.read.cmd.payload.toInt)
    }
    if(bus.write.valid.toBoolean){
      mem(bus.write.address.toInt) = bus.write.data.toBigInt
    }
  }
}
