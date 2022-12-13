package Synapse

import CacheSNN.CacheSnnTest._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{FlowMonitor, StreamDriver, StreamMonitor, StreamReadyRandomizer}
import spinal.lib.bus.amba4.axi.sim.SparseMemory
import breeze.linalg._
import breeze.plot._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

/*
tb generates pre-spike and post-spike from random:
* pre-pre-spike time
* ltp spike time
* ltd spike time
* is virtual spike
then input pre and post spike into dut, assert the output
 */
class SpikeTimeDiffTest extends AnyFunSuite {
  import SynapseCore.timeWindowWidth

  val nTestCase = 100
  val spikeMask = (0x1<<timeWindowWidth)-1

  val complied = simConfig.compile(new SpikeTimeDiff)

  case class RandomEventSim() {
    // pre-pre-spike time
    private val ppsTime: Int = Random.nextInt(16) + 1
    private val ppsValid: Boolean = ppsTime < 16
    private val virtual: Boolean = Random.nextInt(10) <= 1

    val pps = 1 << ppsTime
    val preSpike = ((Random.nextInt() << ppsTime) | pps) & spikeMask | (if (virtual) 0 else 1)

    // ltp time should not earlier than ppsTime
    private val ltpTime: Seq[Int] = Seq.fill(4)(if(ppsValid) Random.nextInt(ppsTime) + 1 else Random.nextInt(15) + 1)
    // ltd time should not earlier than ltp time
    private val ltdTime: Seq[Int] = ltpTime.map(t => Random.nextInt(t) + 1)

    val postSpikeFromPpsToPsExits = Seq.fill(4)(Random.nextBoolean())
    val ltdValid = postSpikeFromPpsToPsExits.map(_ && !virtual)
    val ltpValid = postSpikeFromPpsToPsExits.map(_ && ppsValid)

    val postSpike = (0 until 4).map{i =>
      /*
      preSpike   0 1 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1
      pps            *
      ppsToPs          * * * * * * * * * * * * * * * * *
      postSpike  0 0 0 0 0 0 0 1 0 0 1 1 0 1 0 0 0 0 0 0
      ltpGapMask 0 0 1 1 1 1 1 0 0 0 0 0 0 0 0 0 0 0 0 0
      ltdGapMask 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 1 1 1 1 1
      mask       1 1 0 0 0 0 0 1 1 1 1 1 1 1 0 0 0 0 0 0
      */
      if(postSpikeFromPpsToPsExits(i)){
        val ltpGapMask = ((1 << ppsTime) - (1 << ltpTime(i))) << 1
        val ltdGapMaks = (1 << ltdTime(i)) - 1
        val ltdSpike = 1 << ltdTime(i)
        val ltpSpike = 1 << ltpTime(i)
        val mask = ~(ltpGapMask | ltdGapMaks) & spikeMask
        (Random.nextInt() | ltdSpike | ltpSpike) & mask
      }else{
        val mask = ~((1<<ppsTime+1) - 1) & spikeMask
        Random.nextInt() & mask
      }
    }

    val ltpDeltaT = ltpTime.zip(ltpValid).map(z => if(z._2) ppsTime - z._1 else 0)
    val ltdDeltaT = ltdTime.zip(ltdValid).map(z => if(z._2) z._1 else 0)
  }

  def testBench(dut:SpikeTimeDiff, spikeSeq: Seq[RandomEventSim]): Unit ={
    dut.clockDomain.forkStimulus(2)
    dut.io.preSpike #= 0
    dut.io.postSpike.foreach(_ #= 0)
    dut.clockDomain.waitSampling()
    fork {
      for(spike <- spikeSeq){
        dut.io.preSpike #= spike.preSpike
        dut.clockDomain.waitSampling()
      }
    }
    fork {
      dut.clockDomain.waitSampling(dut.postSpikeInputDelay)
      for (spike <- spikeSeq){
        dut.io.postSpike.zip(spike.postSpike).foreach(z => z._1 #= z._2)
        dut.clockDomain.waitSampling()
      }
    }
    dut.clockDomain.waitSampling(dut.outputDelay)
    for(spike <- spikeSeq){
      dut.clockDomain.waitSampling()
      assert(dut.io.ltdValid.map(_.toBoolean)==spike.ltdValid)
      assert(dut.io.ltpValid.map(_.toBoolean)==spike.ltpValid)
      assert(dut.io.ltdDeltaT.map(_.toInt)==spike.ltdDeltaT)
      assert(dut.io.ltpDeltaT.map(_.toInt)==spike.ltpDeltaT)
    }
  }

  test("spike time diff test"){
    complied.doSim { dut =>
      val spikeSeq = Seq.fill(nTestCase)(RandomEventSim())
      testBench(dut, spikeSeq)
    }
  }
}

object SynapseTest {
  // memory sim slaves, use for cache/postSpike/current
  case class MemSlave(r: MemReadPort[Bits], w: Flow[MemWriteCmd], clockDomain: ClockDomain, readDelay: Int) {
    val mem = SparseMemory()
    val rspQueue = Array.fill(readDelay)(BigInt(0))
    var queuePush = 0
    var queuePop = 1
    clockDomain.onSamplings {
      queuePush = (queuePush + 1) % readDelay
      queuePop = (queuePop + 1) % readDelay
      r.rsp #= rspQueue(queuePop)
      rspQueue(queuePop) = 0
    }

    FlowMonitor(r.cmd, clockDomain) { addr =>
      rspQueue(queuePush) = mem.readBigInt(addr.toLong << 3, 8)
    }

    if (w != null) {
      FlowMonitor(w, clockDomain) { cmd =>
        mem.writeBigInt(cmd.address.toLong << 3, cmd.data.toBigInt, 8)
      }
    }
  }

  case class ExpLutSim(p: ExpLutQuery, clockDomain: ClockDomain) {
    val mem = (0 until 16).toArray
    clockDomain.onSamplings {
      for ((x, y) <- p.x.zip(p.y)) {
        if (x.valid.toBoolean) {
          y #= mem(x.payload.toInt)
        } else {
          y #= 0
        }
      }
    }
  }

  case class SynapseMemSlaves(cache: MemSlave,
                              postSpike: MemSlave,
                              current: MemSlave,
                              ltpLut: ExpLutSim,
                              ltdLut: ExpLutSim)

  def initDut(dut: Synapse): SynapseMemSlaves = {
    dut.clockDomain.forkStimulus(2)
    SimTimeout(100000)
    dut.io.synapseEvent.valid #= false
    val cache = MemSlave(dut.io.synapseData.read, dut.io.synapseData.write, dut.clockDomain, 3)
    val postSpike = MemSlave(dut.io.postSpike, null, dut.clockDomain, 2)
    val current = MemSlave(dut.io.current.read, dut.io.current.write, dut.clockDomain, 2)
    val ltpLut = ExpLutSim(dut.io.ltpQuery, dut.clockDomain)
    val ltdLut = ExpLutSim(dut.io.ltdQuery, dut.clockDomain)
    SynapseMemSlaves(cache, postSpike, current, ltpLut, ltdLut)
  }

  def preSpikeUpdate(sHis: Int, s: Int): Int = {
    ((sHis << 1) & 0xFFFF) | s
  }

  def postSpikeUpdate(sHis: Int, s: Int): Int = {
    ((sHis | s) << 1) & 0xFFFF
  }
}

class SynapseTest extends AnyFunSuite {
  val complied = simConfig.compile(new Synapse)
  import SynapseTest._
  /*
  test pipeline timing
  pre-spike and post-spike trigger ltpT = 14, ltdT = 1,
  default ltpW = 14 + 1, weight = [1, 2, 3, 4], current = 1
  write back weight = [0xF, 0x10, 0x11, 0x12]
  write back current = [0x10, 0x11, 0x12, 0x13]
   */
  test("pipeline simple test"){
    complied.doSim{ dut =>
      val slaves = initDut(dut)
      val preSpike = 1<<15 | 0x1
      val postSpike = 0x0002000200020002L
      val current = 0x0001000100010001L
      val weight = 0x0001000200030004L
      val writeBackWeight = 0x0010001100120013L
      val writeBackCurrent = writeBackWeight + current
      val cacheAddrBase = 0x100
      slaves.cache.mem.writeBigInt(cacheAddrBase<<3, weight, 8)
      slaves.postSpike.mem.writeBigInt(0, postSpike, 8)
      slaves.current.mem.writeBigInt(0, current, 8)
      dut.io.synapseEvent.learning #= true
      dut.io.csr.len #= 0
      fork{
        dut.io.synapseEvent.valid #= true
        dut.io.synapseEvent.preSpike #= preSpike
        dut.io.synapseEvent.cacheAddr #= cacheAddrBase
        dut.clockDomain.waitSamplingWhere(dut.io.synapseEvent.ready.toBoolean)
        dut.io.synapseEvent.valid #= false
      }
      val cacheWriteBack = fork{
        dut.clockDomain.waitSampling(dut.weightWriteBackDelay + 1)
        assert(dut.io.synapseData.write.valid.toBoolean)
        assert(dut.io.synapseData.write.address.toLong==cacheAddrBase)
        assert(dut.io.synapseData.write.data.toBigInt==writeBackWeight)
      }
      val currentWriteBack = fork{
        dut.clockDomain.waitSampling(dut.currentWriteBackDelay + 1)
        assert(dut.io.current.write.valid.toBoolean)
        assert(dut.io.current.write.address.toLong==0)
        assert(dut.io.current.write.data.toBigInt==writeBackCurrent)
        assert(dut.io.synapseEventDone.toBoolean)
      }
      cacheWriteBack.join()
      currentWriteBack.join()
    }
  }
}

object SynapseLearningPlot extends App{
  import SynapseTest._
  import scala.math

  val q = 13
  val tauPre = 2.0
  val tauPost = 2.0
  val threshold = 1 << 14
  val step = 400
  val inSpike = Seq.fill(step)(Random.nextInt(10) >= 7).map(booleanToInt)
  val outSpike = Seq.fill(step)(Seq.fill(4)(Random.nextInt(10) >= 8).map(booleanToInt))
  val weightInit = Seq.fill(4)(1<<q)
  val ltpLutContent = (0 until 16).map(t => 0.01 * math.exp(-t / tauPost)).map(x => quantize(x, q))
  val ltdLutContent = (0 until 16).map(t => -0.05 * math.exp(-t / tauPre)).map(x => quantize(x, q))
  val weightChange: ListBuffer[Seq[Int]] = collection.mutable.ListBuffer()

  simConfig.compile(new Synapse).doSim(66) { dut =>
    val slaves = initDut(dut)
    slaves.cache.mem.writeBigInt(0, vToRaw(weightInit, 16), 8)
    slaves.postSpike.mem.writeBigInt(0, 0, 8)
    slaves.current.mem.writeBigInt(0, 0, 8)
    for (i <- 0 until 16) {
      slaves.ltpLut.mem(i) = ltpLutContent(i)
      slaves.ltdLut.mem(i) = ltdLutContent(i)
    }
    dut.io.synapseEvent.valid #= false
    dut.io.synapseEvent.cacheAddr #= 0
    dut.io.csr.len #= 0
    dut.io.synapseEvent.learning #= true

    var preSpike = 0
    for (t <- 0 until step) {
      // do pre spike triggered stdp
      preSpike = preSpikeUpdate(preSpike, inSpike(t))
      dut.io.synapseEvent.valid #= true
      dut.io.synapseEvent.preSpike #= preSpike
      dut.clockDomain.waitSamplingWhere(dut.io.synapseEvent.ready.toBoolean)
      dut.io.synapseEvent.valid #= false
      dut.clockDomain.waitSamplingWhere(dut.io.synapseEventDone.toBoolean)
      dut.clockDomain.waitSampling()

      // @deprecate instead by random post spike
      // do neuron firing
      /*
      val current = rawToV(slaves.current.mem.readBigInt(0, 8), 16, 4)
      val postSpike = current.map(_ >= threshold).map(booleanToInt)
      val currentRst = current.zip(postSpike).map(z => -(z._2-1) * z._1)
      slaves.current.mem.writeBigInt(0, vToRaw(currentRst, 16), 8)
       */

      // update post spike
      val postSpikeHisOld = rawToV(slaves.postSpike.mem.readBigInt(0, 8), 16, 4)
      val postSpikeHisNew = postSpikeHisOld.zip(outSpike(t)).map(z => postSpikeUpdate(z._1, z._2))
      slaves.postSpike.mem.writeBigInt(0, vToRaw(postSpikeHisNew, 16), 8)
      weightChange.append(rawToV(slaves.cache.mem.readBigInt(0, 8), 16, 4))
    }
  }

  def spikeExtend(sSeq:Seq[Int]):Seq[Double] = {
    sSeq.flatMap(s=>Seq(0, 0, s, 0, 0)).map(_.toDouble)
  }

  val f = Figure()
  val x = linspace(0, step, step)
  val n = 5
  val xs = linspace(0, step, step*5)
  val pPre = f.subplot(n, 1, 0)
  pPre += plot(xs, spikeExtend(inSpike), colorcode = "green")
  for(i <- 0 until 2){
    val p = f.subplot(n, 1, (i*2)+1)
    val w = weightChange.map(_(i).toDouble / math.pow(2, q))
    p += plot(x, w)
    val ps = f.subplot(n, 1, i*2 + 2 )
    ps += plot(xs, spikeExtend(outSpike.map(_(i))), colorcode = "cyan")
  }
  f.saveas("lines.png")
}

class PreSpikeFetchTest extends AnyFunSuite {
  val nPreSpike = 2048
  val complied = simConfig.compile(new PreSpikeFetch)

  case class SpikeEventSim(nid:Int,
                           cacheAddr:Int){
    def nidAddress: Int = (nid % nPreSpike)<<1
    def nidLow: Int = nid % nPreSpike
  }

  def testBench(dut:PreSpikeFetch, spikeEvents:Seq[SpikeEventSim], learning:Boolean): Unit ={
    import SynapseTest._

    dut.clockDomain.forkStimulus(2)
    SimTimeout(100000)
    dut.io.learning #= learning
    StreamReadyRandomizer(dut.io.synapseEvent, dut.clockDomain)

    val (driver, queue) = StreamDriver.queue(dut.io.spikeEvent, dut.clockDomain)
    driver.transactionDelay = () => 0
    spikeEvents.foreach{seSim =>
      queue.enqueue { se =>
        se.cacheAddr #= seSim.cacheAddr
        se.nid #= seSim.nid
      }
    }

    // init default pre-spike history
    val preSpikeMem = MemSlave(dut.io.preSpike.read, dut.io.preSpike.write, dut.clockDomain, 2)
    for(nid <- 0 until nPreSpike){
      preSpikeMem.mem.writeBigInt(nid<<1, nid, 2)
    }

    val preSpikes = if(learning){
      spikeEvents.map{se =>
        preSpikeMem.mem.readBigInt(se.nidAddress, 2).toInt | 1
      }
    }else{
      spikeEvents.map(_ => 0)
    }

    for(i <- spikeEvents.indices){
      dut.clockDomain.waitSamplingWhere(dut.io.synapseEvent.valid.toBoolean && dut.io.synapseEvent.ready.toBoolean)
      val e = spikeEvents(i)
      assert(dut.io.synapseEvent.cacheAddr.toInt == e.cacheAddr)
      assert(dut.io.synapseEvent.nid.toInt == e.nid)
      assert(dut.io.synapseEvent.preSpike.toBigInt == preSpikes(i), f"at nid=${e.nid}")
      if(learning){
        val updatedSpike = preSpikeMem.mem.readBigInt(e.nidAddress, 2)
        assert(updatedSpike == (preSpikes(i) | 1))
      }
    }
  }

  def spikeEventGen: Seq[SpikeEventSim] ={
    val nidBase = randomUIntN(6)<<10
    Seq.fill(1000)(SpikeEventSim(nidBase + Random.nextInt(1024), randomUIntN(CacheConfig.wordAddrWidth)))
    //(0 until 1024).map(nid => SpikeEventSim(nid, randomUIntN(CacheConfig.wordAddrWidth)))
  }

  // test spike event could translate into synapse event
  test("inference only test"){
    complied.doSim{ dut =>
      testBench(dut, spikeEventGen, learning = false)
    }
  }

  test("learning test"){
    complied.doSim { dut =>
      testBench(dut, spikeEventGen, learning = true)
    }
  }
}