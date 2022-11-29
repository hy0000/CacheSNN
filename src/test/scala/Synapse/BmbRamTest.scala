package Synapse

import CacheSNN.CacheSnnTest.simConfig
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.bmb.Bmb.Cmd.Opcode
import spinal.lib.bus.bmb.{Bmb, BmbParameter}
import spinal.lib.sim.StreamReadyRandomizer

import scala.util.Random

object BmbRamTest {
  def bmbTest(bmb:Bmb, clockDomain: ClockDomain, n:Int): Unit ={

    def clearValid(): Unit ={
      if(Random.nextInt(10)<4){
        bmb.cmd.valid #= false
        clockDomain.waitSampling(Random.nextInt(4))
      }
    }

    bmb.cmd.valid #= false
    StreamReadyRandomizer(bmb.rsp, clockDomain)
    fork {
      bmb.cmd.opcode #= Opcode.WRITE
      for (i <- 0 until n) {
        bmb.cmd.valid #= true
        bmb.cmd.context #= i
        bmb.cmd.address #= i << bmb.p.access.wordRangeLength
        bmb.cmd.data #= i
        clockDomain.waitSamplingWhere(bmb.cmd.ready.toBoolean)
        clearValid()
      }
      bmb.cmd.opcode #= Opcode.READ
      for (i <- 0 until n) {
        bmb.cmd.context #= i
        bmb.cmd.valid #= true
        bmb.cmd.address #= i << bmb.p.access.wordRangeLength
        clockDomain.waitSamplingWhere(bmb.cmd.ready.toBoolean)
        clearValid()
      }
    }
    for (i <- 0 until n) {
      clockDomain.waitSamplingWhere(bmb.rsp.valid.toBoolean && bmb.rsp.ready.toBoolean)
      assert(bmb.rsp.context.toInt == i)
    }
    for (i <- 0 until 64) {
      clockDomain.waitSamplingWhere(bmb.rsp.valid.toBoolean && bmb.rsp.ready.toBoolean)
      assert(bmb.rsp.context.toInt == i)
      assert(bmb.rsp.data.toBigInt == i)
    }
  }
}

class BmbRamTest extends AnyFunSuite {
  val size = 8 KiB
  val p = BmbParameter(log2Up(size/8), 64, 0, 8, 3)
  val complied = simConfig.compile(new BmbRam(p, size))

  test("bmb test"){
    complied.doSim{dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.mem.read.cmd.valid #= false
      dut.io.mem.write.valid #= false
      BmbRamTest.bmbTest(dut.io.bmb, dut.clockDomain, 128)
    }
  }

  test("mem rw test"){
    complied.doSim{dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.mem.read.cmd.valid #= false
      dut.io.mem.write.valid #= false
      dut.io.bmb.cmd.valid #= false
      StreamReadyRandomizer(dut.io.bmb.rsp, dut.clockDomain)
      fork {
        dut.io.mem.write.valid #= true
        for (i <- 0 until 64) {
          dut.io.mem.write.address #= i
          dut.io.mem.write.data #= i
          dut.clockDomain.waitSampling()
        }
        dut.io.mem.write.valid #= false
      }
      fork {
        dut.clockDomain.waitSampling()
        dut.io.mem.read.cmd.valid #= true
        for (i <- 0 until 64) {
          dut.io.mem.read.cmd.payload #= i
          dut.clockDomain.waitSampling()
        }
        dut.io.mem.read.cmd.valid #= false
      }

      dut.clockDomain.waitSampling(dut.readDelay + 2)
      for (i <- 0 until 64) {
        assert(dut.io.mem.read.rsp.toBigInt == i)
        dut.clockDomain.waitSampling()
      }
    }
  }
}

class CacheTest extends AnyFunSuite {
  val p = BmbParameter(log2Up(CacheConfig.size / 8), 64, 0, 8, 3)
  val complied = simConfig.compile(new Cache(p))

  test("bmb test") {
    complied.doSim { dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.synapseDataBus.read.cmd.valid #= false
      dut.io.synapseDataBus.write.valid #= false
      BmbRamTest.bmbTest(dut.io.bmb, dut.clockDomain, 256)
    }
  }
}