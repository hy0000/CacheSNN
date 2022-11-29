package Synapse

import CacheSNN.CacheSnnTest.simConfig
import org.scalatest.funsuite.AnyFunSuite
import spinal.lib.sim._
import spinal.core.sim._
import spinal.core._
import spinal.lib.bus.bmb.Bmb.Cmd.Opcode
import spinal.lib.bus.bmb.{Bmb, BmbParameter}

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

  def memRwTest(mem:MemReadWrite, clockDomain: ClockDomain, readDelay:Int, n:Int): Unit ={
    mem.write.valid #= false
    mem.read.cmd.valid #= false
    fork {
      mem.write.valid #= true
      for (i <- 0 until n) {
        mem.write.address #= i
        mem.write.data #= i
        clockDomain.waitSampling()
      }
      mem.write.valid #= false
    }
    fork {
      clockDomain.waitSampling()
      mem.read.cmd.valid #= true
      for (i <- 0 until n) {
        mem.read.cmd.payload #= i
        clockDomain.waitSampling()
      }
      mem.read.cmd.valid #= false
    }

    clockDomain.waitSampling(readDelay + 2)
    for (i <- 0 until n) {
      assert(mem.read.rsp.toBigInt == i)
      clockDomain.waitSampling()
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
      dut.io.bmb.cmd.valid #= false
      BmbRamTest.memRwTest(dut.io.mem, dut.clockDomain, dut.readDelay, 64)
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
      dut.io.synapseData.read.cmd.valid #= false
      dut.io.synapseData.write.valid #= false
      BmbRamTest.bmbTest(dut.io.bmb, dut.clockDomain, 256)
    }
  }

  test("rw test"){
    complied.doSim { dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.bmb.cmd.valid #= false
      BmbRamTest.memRwTest(dut.io.synapseData, dut.clockDomain, dut.readDelay, 256)
    }
  }

  test("rw address conflict test"){
    intercept[Throwable] {
      complied.doSim{ dut =>
        val readAddr = 0x010
        val writeAddr = 0x020
        dut.clockDomain.forkStimulus(2)
        dut.io.bmb.cmd.valid #= false
        dut.io.synapseData.read.cmd.valid #= true
        dut.io.synapseData.read.cmd.payload #= readAddr
        dut.io.synapseData.write.valid #= true
        dut.io.synapseData.write.address #= writeAddr
        dut.clockDomain.waitSampling(2)
      }
    }
  }
}