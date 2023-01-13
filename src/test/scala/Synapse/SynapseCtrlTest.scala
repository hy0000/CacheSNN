package Synapse

import CacheSNN.CacheSnnTest._
import Synapse.SynapseCore.AddrMapping
import Util.sim.MemAccessBusMemSlave
import Util.sim.NumberTool._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.sim.{FlowDriver, StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.mutable
import scala.util.Random

class SpikeDecoderTest extends AnyFunSuite {
  val complied = simConfig.compile(new SpikeDecoder)

  test("decode test"){
    complied.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val nidBase = Random.nextInt(0xFF00)
      val spikeMask = Seq.fill(SynapseCore.maxPreSpike)(Random.nextInt(2))
      val spikeMaskRaw = spikeMask.grouped(SynapseCore.busDataWidth)
        .map(g => vToRaw(g, 1))
      val validNid = spikeMask.zipWithIndex.filter(z => z._1==1)
        .map(z => z._2 + nidBase)
      val validNidQueue = mutable.Queue(validNid:_*)

      dut.io.nidBase #= nidBase
      val (_, spikeInQueue) = FlowDriver.queue(dut.io.maskSpike, dut.clockDomain)
      spikeMaskRaw.foreach(s => spikeInQueue.enqueue(_ #= s))

      StreamReadyRandomizer(dut.io.spikeOut, dut.clockDomain)
      StreamMonitor(dut.io.spikeOut, dut.clockDomain){ s =>
        val nid = validNidQueue.dequeue()
        assert(s.nid.toInt==nid)
      }

      dut.clockDomain.waitSamplingWhere(dut.io.maskSpike.valid.toBoolean)
      dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)
    }
  }
}

class SpikeUpdaterTest extends AnyFunSuite {
  val complied = simConfig.compile(new SpikeUpdater)

  case class TestConfig(preLen: Int, postLen: Int, epoch: Int,
                        preSpike: Array[Array[Int]], postSpike: Array[Array[Int]])

  def tb(dut: SpikeUpdater, c: TestConfig): Unit ={
    import c._
    dut.clockDomain.forkStimulus(2)
    SimTimeout(100000)
    dut.io.preLen #= preLen / 4 - 1
    dut.io.postLen #= postLen / 4 - 1
    dut.io.run.valid #= false

    val bus = MemAccessBusMemSlave(dut.io.bus, dut.clockDomain, 3)

    def spikeRead(base: BigInt, i: Int): Int = {
      val addr = base.toLong + (i << 1)
      bus.mem.readBigInt(addr, 2).toInt
    }

    def spikeWrite(base: BigInt, i: Int, d: BigInt): Unit = {
      val addr = base.toLong + (i << 1)
      bus.mem.writeBigInt(addr, d, 2)
    }

    // init spike ram
    for (i <- 0 until preLen) {
      spikeWrite(AddrMapping.preSpike.base, i, 0)
    }
    for (i <- 0 until postLen) {
      spikeWrite(AddrMapping.postSpike.base, i, 0)
    }

    val (maskSpikeDriver, maskSpikeQueue) = StreamDriver.queue(dut.io.maskSpike, dut.clockDomain)
    maskSpikeDriver.transactionDelay = () => 0

    val preSpikeHisTruth = Array.fill(preLen)(0)
    val postSpikeHisTruth = Array.fill(postLen)(0)

    val mask16 = (1 << 16) - 1

    for (t <- 0 until epoch) {
      // update pre spike
      for (i <- 0 until preLen) {
        val preSpikeInserted = spikeRead(AddrMapping.preSpike.base, i) | preSpike(t)(i)
        spikeWrite(AddrMapping.preSpike.base, i, d = preSpikeInserted)
        preSpikeHisTruth(i) = (preSpikeInserted << 1) & mask16
      }
      // update post spike
      for (i <- 0 until postLen) {
        postSpikeHisTruth(i) = ((postSpikeHisTruth(i) << 1) & mask16) | postSpike(t)(i)
      }

      // drive dut
      dut.io.run.valid #= true
      val maskPostSpike = vToRawV(postSpike(t), 1, 64)
      maskPostSpike.foreach { s =>
        maskSpikeQueue.enqueue(_ #= s)
      }
      dut.clockDomain.waitSamplingWhere(dut.io.run.ready.toBoolean)
      dut.io.run.valid #= false

      // assert pre spike
      for (i <- 0 until preLen) {
        val preSpikeHis = spikeRead(AddrMapping.preSpike.base, i)
        assert(preSpikeHis == preSpikeHisTruth(i), s"at t-$t i-$i pre")
      }
      // assert post spike
      for (i <- 0 until postLen) {
        val postSpikeHis = spikeRead(AddrMapping.postSpike.base, i)
        assert(postSpikeHis == postSpikeHisTruth(i), s"at t-$t i-$i post")
      }
    }
  }

  test("spike update simple test") {
    val preLen = 64 // at least 64
    val postLen = 64
    val epoch = 1
    val preSpike = Array.tabulate(epoch, preLen)((_, _) => 1)
    val postSpike = Array.tabulate(epoch, postLen)((_, _) => 1)
    val testConfig = TestConfig(preLen, postLen, epoch, preSpike, postSpike)
    complied.doSim { dut =>
      tb(dut, testConfig)
    }
  }

  test("spike update random test"){
    val preLen = 1024
    val postLen = 512
    val epoch = 24
    val preSpike = Array.tabulate(epoch, preLen)((_, _) => Random.nextInt(2))
    val postSpike = Array.tabulate(epoch, postLen)((_, _) => Random.nextInt(2))
    val testConfig = TestConfig(preLen, postLen, epoch, preSpike, postSpike)
    complied.doSim{dut =>
      tb(dut, testConfig)
    }
  }
}

class SynapseCtrlTest {

}