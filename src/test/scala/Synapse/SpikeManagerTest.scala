package Synapse

import CacheSNN.CacheSnnTest._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.{breakOut, mutable}
import scala.util.Random


class SpikeManagerTest extends AnyFunSuite {

}

class SpikeSim(val nid: Int){
  def setIndex():Int = nid & ((1<<CacheConfig.setIndexRange.size) - 1)
  def tag():Int = nid>>CacheConfig.setIndexRange.size
}

class SpikeEventSim(nid: Int,
                    val cacheAddr: Int) extends SpikeSim(nid)

class MissSpikeSim(nid: Int,
                   cacheAddr: Int,
                   val replaceNid: Int,
                   val action: MissAction.E) extends SpikeEventSim(nid, cacheAddr)

class SpikeCacheManagerAgent(dut:SpikeCacheManager) {
  val printState = false
  val (spikeInDriver, spikeInQueue) = StreamDriver.queue(dut.io.spikeIn, dut.clockDomain)

  def sendSpike(s: SpikeSim): Unit = {
    spikeInQueue.enqueue(_.nid #= s.nid)
  }

  def sendSpike(ss: Seq[SpikeSim]): Unit = {
    for (s <- ss) {
      sendSpike(s)
    }
  }

  class TagSim {
    var valid = false
    var locked = false
    var tag = 0
    var timestamp = 0

    def nid(setIndex:Int): Int = {
      (tag<<CacheConfig.setIndexRange.size) | setIndex
    }
  }

  def currentTime:Int = dut.io.csr.timestamp.toInt

  val tagRam = Array.tabulate(CacheConfig.setSize, CacheConfig.ways){
    (_, _) => new TagSim
  }

  val missQueue = mutable.Queue[MissSpikeSim]()
  val hitQueue = mutable.Queue[SpikeEventSim]()
  val failQueue = mutable.Queue[SpikeSim]()
  val doneQueue = mutable.Queue[SpikeEventSim]()

  def genCacheAddress(setIndex:Int, way:Int): Int ={
    setIndex*CacheConfig.ways + way
  }

  def waitDone(): Unit = {
    def queueEmpty:Boolean = Seq(spikeInQueue, missQueue, hitQueue, failQueue, doneQueue)
      .map(_.isEmpty).reduce(_ && _)
    dut.clockDomain.waitSamplingWhere(queueEmpty)
    dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)
  }

  def flush(): Unit = {
    for (setIndex <- tagRam.indices) {
      for (way <- 0 until CacheConfig.ways) {
        val t = tagRam(setIndex)(way)
        if (dut.io.csr.learning.toBoolean && t.valid) {
          val writeBackSpike = new MissSpikeSim(
            nid = t.nid(setIndex),
            cacheAddr = genCacheAddress(setIndex, way),
            replaceNid = 0,
            action = MissAction.WRITE_BACK
          )
          missQueue.enqueue(writeBackSpike)
        }
      }
    }
  }

  def allocate(nid: Int): Unit ={
    val tag = nid >> CacheConfig.setIndexRange.size
    val setIndex = nid & ((1 << CacheConfig.setIndexRange.size) - 1)
    val tagSet = tagRam(setIndex)
    var hit, compulsory, replace = false

    for ((t, way) <- tagSet.zipWithIndex) {
      if (t.tag == tag && t.valid && !hit) {
        hit = true
        t.timestamp = currentTime
        t.locked = true
        val hitSpike = new SpikeEventSim(
          nid = nid,
          cacheAddr = genCacheAddress(setIndex, way)
        )
        if(printState){
          println(s"$nid hit")
        }
        hitQueue.enqueue(hitSpike)
        breakOut
      }
    }

    if(!hit){
      for ((t, way) <- tagSet.zipWithIndex) {
        if (!t.valid && !compulsory) {
          compulsory = true
          t.tag = tag
          t.timestamp = currentTime
          t.locked = true
          t.valid = true
          val missSpike = new MissSpikeSim(
            nid = nid,
            cacheAddr = genCacheAddress(setIndex, way),
            replaceNid = 0,
            action = MissAction.OVERRIDE
          )
          if(printState){
            println(s"$nid compulsory")
          }
          missQueue.enqueue(missSpike)
        }
      }
    }

    if(!hit && !compulsory){
      val timestampUpBound = 1<<CacheConfig.tagTimestampWidth
      for ((t, way) <- tagSet.zipWithIndex) {
        val isLastWay = way == CacheConfig.ways - 1
        val oldTimestamp = t.timestamp
        val inRefractory = (dut.io.csr.timestamp.toInt + timestampUpBound - oldTimestamp) % timestampUpBound <= dut.io.csr.refractory.toInt
        val replaceValid = !t.locked && (isLastWay || !inRefractory)
        if (replaceValid && !replace) {
          replace = true
          val replaceNid = t.nid(setIndex)
          t.tag = tag
          t.timestamp = currentTime
          t.locked = true
          t.valid = true
          val missSpike = new MissSpikeSim(
            nid = nid,
            cacheAddr = genCacheAddress(setIndex, way),
            replaceNid = replaceNid,
            action = if(dut.io.csr.learning.toBoolean) MissAction.REPLACE else MissAction.OVERRIDE
          )
          if(printState){
            println(s"$nid replace $replaceNid at way $way timestamp $oldTimestamp")
          }
          missQueue.enqueue(missSpike)
        }
      }
    }

    if(!hit && !compulsory && !replace){
      val failSpike = new SpikeSim(nid)
      if(printState){
        println(s"$nid fail")
      }
      failQueue.enqueue(failSpike)
    }
  }

  StreamMonitor(dut.io.spikeIn, dut.clockDomain) { s =>
    allocate(s.nid.toInt)
  }

  StreamMonitor(dut.io.synapseEventDone, dut.clockDomain) { s =>
    val setIndex = s.nid.toInt & ((1 << CacheConfig.setIndexRange.size) - 1)
    val way = s.cacheLineAddr.toInt & (CacheConfig.ways - 1)
    tagRam(setIndex)(way).locked = false
  }

  StreamMonitor(dut.io.missSpike, dut.clockDomain) { s =>
    val missSpike = missQueue.dequeue()
    assert(s.nid.toInt == missSpike.nid, s"${s.nid.toInt} ${missSpike.nid}")
    assert(s.replaceNid.toInt == missSpike.replaceNid, s"${s.replaceNid.toInt} ${missSpike.replaceNid} at nid ${missSpike.nid}")
    assert(s.cacheLineAddr.toInt == missSpike.cacheAddr)
    assert(s.action.toEnum == missSpike.action, s"${s.action.toEnum} ${missSpike.action}")
    if(!dut.io.flush.valid.toBoolean){
      doneQueue.enqueue(missSpike.asInstanceOf[SpikeEventSim])
    }
  }

  StreamMonitor(dut.io.hitSpike, dut.clockDomain) { s =>
    val hitSpike = hitQueue.dequeue()
    assert(s.nid.toInt == hitSpike.nid)
    assert(s.cacheLineAddr.toInt == hitSpike.cacheAddr)
    doneQueue.enqueue(hitSpike)
  }

  StreamMonitor(dut.io.failSpike, dut.clockDomain) { s =>
    val failSpike = failQueue.dequeue()
    assert(s.nid.toInt == failSpike.nid)
    spikeInQueue.enqueue{spikeIn =>
      spikeIn.nid #= failSpike.nid
    }
  }

  StreamDriver(dut.io.synapseEventDone, dut.clockDomain) { s =>
    if(doneQueue.nonEmpty && Random.nextInt(100)<20){
      val spike = doneQueue.dequeue()
      s.nid #= spike.nid
      s.cacheLineAddr #= spike.cacheAddr
      true
    }else{
      false
    }
  }
}

class SpikeCacheManagerTest extends AnyFunSuite {

  val complied = simConfig.compile(new SpikeCacheManager)

  def initDut(dut:SpikeCacheManager): SpikeCacheManagerAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(100000)
    dut.io.csr.learning #= false
    dut.io.csr.refractory #= 1
    dut.io.csr.timestamp #= 0
    val agent = new SpikeCacheManagerAgent(dut)

    StreamReadyRandomizer(dut.io.missSpike, dut.clockDomain)
    StreamReadyRandomizer(dut.io.hitSpike, dut.clockDomain)
    StreamReadyRandomizer(dut.io.failSpike, dut.clockDomain)
    dut.io.missSpike.ready #= true
    dut.io.hitSpike.ready #= true
    dut.io.failSpike.ready #= true

    // flush
    dut.io.flush.valid #= true
    dut.clockDomain.waitSamplingWhere(dut.io.flush.ready.toBoolean)
    dut.io.flush.valid #= false
    dut.io.csr.learning #= true
    agent
  }

  def initDutWithSpike(dut:SpikeCacheManager, spike:Seq[SpikeSim]): SpikeCacheManagerAgent = {
    val agent = initDut(dut)
    agent.sendSpike(spike)
    agent.waitDone()
    agent
  }

  test("flush test") {
    complied.doSim { dut =>
      initDut(dut)
    }
  }

  test("allocate test") {
    complied.doSim{ dut =>
      val spike = (0 until CacheConfig.lines).map(nid => new SpikeSim(nid))
      initDutWithSpike(dut, spike)
    }
  }

  test("hit test"){
    complied.doSim { dut =>
      val spike = (0 until CacheConfig.lines).map(nid => new SpikeSim(nid))
      val agent = initDutWithSpike(dut, spike)
      agent.sendSpike(spike)
      agent.waitDone()
    }
  }

  test("replace test"){
    complied.doSim { dut =>
      val spike = (0 until CacheConfig.lines).map(nid => new SpikeSim(nid))
      val agent = initDutWithSpike(dut, spike)

      dut.clockDomain.waitSampling(50)
      dut.io.csr.timestamp #= 2
      val missSpike = spike.map(s => new SpikeSim(nid=s.nid + 0xF000))
      agent.sendSpike(missSpike)
      agent.waitDone()
    }
  }

  test("replace last way test"){
    complied.doSim { dut =>
      val spike = (0 until CacheConfig.lines).map(nid => new SpikeSim(nid))
      val agent = initDutWithSpike(dut, spike)

      dut.clockDomain.waitSampling(50)
      dut.io.csr.timestamp #= 1 // in refractory lock
      val missSpike = spike.map(s => new SpikeSim(nid = s.nid + 0xF000))
      agent.sendSpike(missSpike)
      agent.waitDone()
    }
  }

  test("random test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      val nidBase = Random.nextInt(0xF000)
      val spike = (0 until CacheConfig.lines * 10).map(nid => new SpikeSim(nidBase + nid))

      for(t <- 0 until 10){
        dut.io.csr.timestamp #= t % (1<<CacheConfig.tagTimestampWidth)
        val thisSpike = Random.shuffle(spike).take(100)
        agent.sendSpike(thisSpike)
        agent.waitDone()
      }
    }
  }
}