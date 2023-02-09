package Util

import CacheSNN.CacheSnnTest._
import spinal.core._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.sim._

import scala.util.Random

class StreamFifoDelay2Test extends AnyFunSuite {
  val depth = 64
  val complied = simConfig.compile(StreamFifoDelay2(Bits(64 bits), depth))

  test("fifo test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(1000000)
      dut.io.flush #= false
      val (_, pushQueue) = StreamDriver.queue(dut.io.push, dut.clockDomain)
      StreamReadyRandomizer(dut.io.pop, dut.clockDomain)
      val data = Array.fill(10000)(BigInt(64, Random))
      for(d <- data){
        pushQueue.enqueue(_ #= d)
      }
      for(d <- data){
        dut.clockDomain.waitSamplingWhere(dut.io.pop.valid.toBoolean && dut.io.pop.ready.toBoolean)
        assert(dut.io.pop.payload.toBigInt==d)
      }
    }
  }
}
