package Synapse

import CacheSNN.CacheSnnTest._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.core._
import spinal.lib.bus.simple._

import scala.util.Random

object BufferTest {
  def busTest(bus:PipelinedMemoryBus, clockDomain: ClockDomain, n:Int): Unit ={

    def clearValid(): Unit ={
      if(Random.nextInt(10)<4){
        bus.cmd.valid #= false
        clockDomain.waitSampling(Random.nextInt(4))
      }
    }

    val wordByteWith = log2Up(bus.config.dataWidth / 8)

    bus.cmd.valid #= false
    fork {
      bus.cmd.write #= true
      for (i <- 0 until n) {
        bus.cmd.valid #= true
        bus.cmd.address #= i << wordByteWith
        bus.cmd.data #= i
        clockDomain.waitSamplingWhere(bus.cmd.ready.toBoolean)
        clearValid()
      }
      bus.cmd.write #= false
      for (i <- 0 until n) {
        bus.cmd.valid #= true
        bus.cmd.address #= i << wordByteWith
        clockDomain.waitSamplingWhere(bus.cmd.ready.toBoolean)
        clearValid()
      }
    }
    for (i <- 0 until n) {
      clockDomain.waitSamplingWhere(bus.rsp.valid.toBoolean)
      assert(bus.rsp.data.toBigInt == i)
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

class BufferTest extends AnyFunSuite {
  val size = 8 KiB
  val p = PipelinedMemoryBusConfig(log2Up(size), SynapseCore.busDataWidth)
  val complied = simConfig.compile(new Buffer(p, size))

  test("bus test") {
    complied.doSim { dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.mem.read.cmd.valid #= false
      dut.io.mem.write.valid #= false
      BufferTest.busTest(dut.io.bus, dut.clockDomain, 128)
    }
  }

  test("mem rw test") {
    complied.doSim { dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.bus.cmd.valid #= false
      BufferTest.memRwTest(dut.io.mem, dut.clockDomain, dut.readDelay, 64)
    }
  }
}

class CacheTest extends AnyFunSuite {
  val size = 128 KiB
  val p = PipelinedMemoryBusConfig(log2Up(size), SynapseCore.busDataWidth)
  val complied = simConfig.compile(new Cache(p))

  test("bmb test") {
    complied.doSim { dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.synapseData.read.cmd.valid #= false
      dut.io.synapseData.write.valid #= false
      BufferTest.busTest(dut.io.bus, dut.clockDomain, 256)
    }
  }

  test("rw test"){
    complied.doSim { dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.bus.cmd.valid #= false
      BufferTest.memRwTest(dut.io.synapseData, dut.clockDomain, dut.readDelay, 256)
    }
  }

  test("rw address conflict test"){
    intercept[Throwable] {
      complied.doSim{ dut =>
        val readAddr = 0x010
        val writeAddr = 0x020
        dut.clockDomain.forkStimulus(2)
        dut.io.bus.cmd.valid #= false
        dut.io.synapseData.read.cmd.valid #= true
        dut.io.synapseData.read.cmd.payload #= readAddr
        dut.io.synapseData.write.valid #= true
        dut.io.synapseData.write.address #= writeAddr
        dut.clockDomain.waitSampling(2)
      }
    }
  }
}

class ExpLutTest extends AnyFunSuite {
  val size = 32 Byte
  val p = PipelinedMemoryBusConfig(log2Up(size), SynapseCore.busDataWidth)
  val complied = simConfig.compile(new ExpLut(p))

  test("write and read test"){
    complied.doSim {dut =>
      dut.clockDomain.forkStimulus(2)
      val data = Seq.fill(SynapseCore.timeWindowWidth)(randomInt16)
      val rawData = data.grouped(SynapseCore.busDataWidth/16).map(v => vToRaw(v, 16))
      // write data
      dut.io.bus.cmd.write #= true
      dut.io.bus.cmd.valid #= true
      for((d, i) <- rawData.zipWithIndex) {
        dut.io.bus.cmd.address #= i << log2Up(dut.io.bus.config.dataWidth / 8)
        dut.io.bus.cmd.data #= d
        dut.clockDomain.waitSamplingWhere(dut.io.bus.cmd.ready.toBoolean)
      }
      dut.io.bus.cmd.valid #= false
      // read test
      val xSeq = Seq.tabulate(100, 4){(_, _) => Random.nextInt(4)}
      fork{
        for(x <- xSeq){
          dut.io.query.x.zip(x).foreach(z => z._1 #= z._2)
          dut.clockDomain.waitSampling()
        }
      }
      dut.clockDomain.waitSampling(dut.readDelay + 1)
      for(x <- xSeq){
        for((y, xi) <- dut.io.query.y.zip(x)){
          assert(y.toInt==data(xi))
        }
        dut.clockDomain.waitSampling()
      }
    }
  }
}