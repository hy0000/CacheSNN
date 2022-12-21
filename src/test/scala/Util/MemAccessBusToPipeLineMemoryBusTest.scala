package Util

import CacheSNN.CacheSnnTest.simConfig
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim.SparseMemory
import spinal.lib.bus.simple.PipelinedMemoryBus
import spinal.lib.sim._

import scala.util.Random

class MemAccessBusToPipeLineMemoryBusTest extends AnyFunSuite {
  val config = MemAccessBusConfig(64, 20)
  val complied = simConfig.compile(new MemAccessBusToPipeLineMemoryBus(config))

  case class PipelineMemorySim(p: PipelinedMemoryBus, clockDomain: ClockDomain, readDelay: Int) {
    val mem = SparseMemory()
    val rspQueue = Array.fill(readDelay)(BigInt(0))
    val rspValid = Array.fill(readDelay)(false)
    var queuePush = 0
    var queuePop = 1
    clockDomain.onSamplings {
      queuePush = (queuePush + 1) % readDelay
      queuePop = (queuePop + 1) % readDelay
      p.rsp.valid #= rspValid(queuePop)
      p.rsp.data #= rspQueue(queuePop)
      rspQueue(queuePop) = 0
      rspValid(queuePop) = false
    }

    StreamReadyRandomizer(p.cmd, clockDomain)
    // p.cmd.ready #= true
    p.rsp.valid #= false
    StreamMonitor(p.cmd, clockDomain) { cmd =>
      if (cmd.write.toBoolean) {
        mem.writeBigInt(cmd.address.toLong << 3, cmd.data.toBigInt, 8)
      } else {
        rspQueue(queuePush) = mem.readBigInt(cmd.address.toLong << 3, 8)
        rspValid(queuePush) = true
      }
    }
  }

  test("MemAccessBus to PipeLineMemoryBus test") {
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000)
      val mem = PipelineMemorySim(dut.io.output, dut.clockDomain, 3)
      val addr = Random.nextInt(256) << 3
      val len = Random.nextInt(256)
      dut.io.input.cmd.address #= addr
      dut.io.input.cmd.len #= len
      dut.io.input.cmd.write #= true
      dut.io.input.cmd.valid #= true
      //StreamReadyRandomizer(dut.io.input.rsp, dut.clockDomain)
      dut.io.input.rsp.ready #= true
      for (i <- 0 to len) {
        dut.io.input.cmd.data #= i
        dut.clockDomain.waitSamplingWhere(dut.io.input.cmd.valid.toBoolean && dut.io.input.cmd.ready.toBoolean)
      }
      dut.io.input.cmd.write #= false
      for (_ <- 0 until 2) {
        for (i <- 0 to len) {
          dut.clockDomain.waitSamplingWhere(dut.io.input.rsp.valid.toBoolean && dut.io.input.rsp.ready.toBoolean)
          assert(dut.io.input.rsp.fragment.toBigInt == i)
          assert(dut.io.input.rsp.last.toBoolean == (i == len))
        }
      }
      dut.clockDomain.waitSampling(10)
    }
  }
}
