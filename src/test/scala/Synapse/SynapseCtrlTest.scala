package Synapse

import CacheSNN.AER
import CacheSNN.CacheSnnTest._
import CacheSNN.sim.AerPacketManager
import Synapse.SynapseCore.AddrMapping
import Util.sim.MemAccessBusMemSlave
import Util.sim.NumberTool._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
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

/*
pre spike trigger current++ and w++
post spike trigger w--
 */
class SynapseCtrlTest extends AnyFunSuite {
  val compiled = simConfig.compile(new SynapseCtrl)

  val preLen = 1024 // fixed
  val postLen = 512
  case class SynapseCtrlAgent(dut:SynapseCtrl){
    val bus = MemAccessBusMemSlave(dut.io.bus, dut.clockDomain, 3)
    val aerManager = AerPacketManager(dut.io.aerIn, dut.io.aerOut, dut.clockDomain, preLen, postLen / 4)
    val doneQueue = mutable.Queue[SpikeEventSim]()

    StreamDriver(dut.io.spikeEventDone, dut.clockDomain) { s =>
      if (doneQueue.nonEmpty && Random.nextInt(100) < 20) {
        val spike = doneQueue.dequeue()
        s.nid #= spike.nid
        s.cacheLineAddr #= spike.cacheAddr
        true
      } else {
        false
      }
    }

    // init bus slave
    for (i <- 0 until postLen) {
      val currentAddr = AddrMapping.current.base.toLong + (i << 1)
      bus.mem.writeBigInt(currentAddr, 0, 2)
      val postSpikeAddr = AddrMapping.postSpike.base.toLong + (i << 1)
      bus.mem.writeBigInt(postSpikeAddr, 0, 2)
    }

    StreamReadyRandomizer(dut.io.spikeEvent, dut.clockDomain)
    // pre spike action
    StreamMonitor(dut.io.spikeEvent, dut.clockDomain){ s =>
      // add to done queue
      doneQueue.enqueue(new SpikeEventSim(s.nid.toInt, s.cacheLineAddr.toInt))
      // current ++
      for(i <- 0 until postLen){
        val addr = AddrMapping.current.base.toLong + (i<<1)
        val current = bus.mem.readBigInt(addr, 2).toInt
        val currentNew = rawToInt(current, 16) + 1
        bus.mem.writeBigInt(addr, currentNew, 2)
      }

      if(dut.io.csr.learning.toBoolean){
        // weight change
        val addrShift = log2Up(CacheConfig.size / CacheConfig.lines)
        for (i <- 0 until  postLen) {
          val addr = (s.cacheLineAddr.toLong<<addrShift) | (i << 1)
          val weight = bus.mem.readBigInt(addr, 2).toInt
          val postSpikeAddr = AddrMapping.postSpike.base.toLong + (i << 1)
          val postSpike = bus.mem.readBigInt(postSpikeAddr, 2).toInt & 0x1
          val weightNew = rawToInt(weight, 16) + 1 - postSpike
          bus.mem.writeBigInt(addr, weightNew, 2)
        }
      }
    }

    def flush(): Unit = {
      dut.io.csr.flush #= true
      dut.clockDomain.waitSamplingWhere(dut.io.flushed.toBoolean)
      dut.io.csr.flush #= false
    }

    def waiteDone(): Unit = {
      aerManager.aerDriver.waitDone()
      dut.clockDomain.waitSampling(2)
      dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)
    }

    def waitCurrentPacketSend(): Unit ={
      dut.clockDomain.waitSamplingWhere(
        dut.io.aerOut.head.valid.toBoolean && dut.io.aerOut.head.ready.toBoolean &&
          dut.io.aerOut.head.eventType.toEnum == AER.TYPE.CURRENT
      )
      dut.clockDomain.waitSamplingWhere(
        dut.io.aerOut.body.valid.toBoolean && dut.io.aerOut.body.ready.toBoolean &&
          dut.io.aerOut.body.last.toBoolean
      )
      dut.clockDomain.waitSampling()
    }

    def assertCurrentCleared(): Unit = {
      for (i <- 0 to dut.io.csr.len.toInt) {
        val addr = AddrMapping.current.base.toLong + (i << 2)
        val current = bus.mem.readBigInt(addr, 2).toInt
        assert(current==0, s"${current.toHexString} at ${addr.toHexString}")
      }
    }

    def assertCurrentTruth(preSpikes: Array[Array[Int]]): Unit ={
      val currentTruth = preSpikes.transpose.map(_.sum)
      val currentTruthRaw = vToRawV(currentTruth, 16, 4)
      for(i <- 0 to dut.io.csr.len.toInt){
        assert(
          aerManager.currentData(i)==currentTruthRaw(i),
          s"${aerManager.currentData(i).toString(16)} ${currentTruthRaw(i).toString(16)} at $i"
        )
      }
    }
  }

  def initDut(dut: SynapseCtrl): SynapseCtrlAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(10000000)
    dut.io.csr.learning #= false
    dut.io.csr.refractory #= 1
    dut.io.csr.preLen #= preLen / 4 - 1
    dut.io.csr.len #= postLen / 4 - 1
    dut.io.csr.postNidBase #= 0
    dut.io.csr.flush #= false
    SynapseCtrlAgent(dut)
  }

  test("flush test"){
    compiled.doSim{ dut =>
      val agent = initDut(dut)
      agent.flush()
    }
  }

  test("inference only test"){
    compiled.doSim { dut =>
      val agent = initDut(dut)
      agent.flush()
      val epoch = 20
      val nidBase = 0
      val preSpike = Array.tabulate(epoch, preLen){
        (_, _) => if(Random.nextInt(10) < 1) 1 else 0
      }

      for((s, t) <- preSpike.zipWithIndex){
        agent.aerManager.sendSpike(s, nidBase, AER.TYPE.PRE_SPIKE)
        agent.waiteDone()
        agent.assertCurrentCleared()
      }

      val totalSpike = preSpike.flatten.sum
      for (i <- 0 to dut.io.csr.len.toInt) {
        val current = rawToV(agent.aerManager.currentData(i), 16, 4)
        current.foreach{ c =>
          assert(c==totalSpike, s"$i")
        }
      }
    }
  }

  test("learning test"){
    compiled.doSim { dut =>
      val agent = initDut(dut)
      agent.flush()
      dut.io.csr.learning #= true
      for (i <- 0 until preLen) {
        for (j <- 0 until postLen / 4) {
          agent.aerManager.memSpikeData(i)(j) = BigInt(0x00FF00FF00FF00FFL)
        }
      }

      val epoch = 20
      val preSpike = Array.tabulate(epoch, preLen) {
        (_, _) => if (Random.nextInt(10) < 1) 1 else 0
      }
      val postSpike = Array.tabulate(epoch, postLen) {
        (_, _) => if (Random.nextInt(10) < 1) 1 else 0
      }

      for (t <- 0 until epoch) {
        agent.aerManager.sendSpike(preSpike(t), 0, AER.TYPE.PRE_SPIKE)
        agent.waitCurrentPacketSend()
        agent.aerManager.sendSpike(postSpike(t),  0, AER.TYPE.POST_SPIKE)
        agent.waiteDone()
        agent.assertCurrentCleared()
      }

      val totalSpike = preSpike.flatten.sum
      for (i <- 0 to dut.io.csr.len.toInt) {
        val current = rawToV(agent.aerManager.currentData(i), 16, 4)
        current.foreach { c =>
          assert(c == totalSpike, s"$i")
        }
      }

      agent.flush()
      agent.waiteDone()

      val weightTruth = Array.tabulate(preLen, postLen) {
        (_, _) => 0xFF
      }
      for(t <- 0 until epoch){
        for (preNid <- 0 until preLen) {
          if (preSpike(t)(preNid) != 0) {
            for (postNid <- 0 until postLen) {
              weightTruth(preNid)(postNid) += 1
              if (t > 0){
                weightTruth(preNid)(postNid) -= postSpike(t - 1)(postNid)
              }
            }
          }
        }
      }

      for (preNid <- 0 until preLen) {
        for (postNid <- 0 until postLen) {
          val wRaw = agent.aerManager.memSpikeData(preNid)(postNid / 4)
          val w = rawToV(wRaw, 16, 4)(postNid % 4)
          val wTruth = weightTruth(preNid)(postNid)
          assert(w==wTruth, s"$w $wTruth at $preNid $postNid raw-${wRaw.toString(16)}")
        }
      }
    }
  }
}