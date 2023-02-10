package Synapse

import CacheSNN.{AER, AerPacket}
import CacheSNN.CacheSnnTest._
import CacheSNN.sim.{AerDriver, AerMonitor, AerPacketManager, AerPacketSim}
import Util.sim.MemAccessBusMemSlave
import Util.sim.NumberTool._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.{breakOut, mutable}
import scala.util.Random

class SpikeSim(val nid: Int){
  def setIndex():Int = nid & ((1<<CacheConfig.setIndexRange.size) - 1)
  def tag():Int = nid>>CacheConfig.setIndexRange.size
}

class SpikeEventSim(nid: Int,
                    val cacheAddr: Int) extends SpikeSim(nid){

  def assertEqual(sPort: SpikeEvent): Unit = {
    assert(sPort.nid.toInt == nid)
    assert(sPort.cacheLineAddr.toInt == cacheAddr, s"${sPort.cacheLineAddr.toInt.toHexString} ${cacheAddr.toHexString}")
  }
}

class MissSpikeSim(nid: Int,
                   cacheAddr: Int,
                   val replaceNid: Int,
                   val action: MissAction.E) extends SpikeEventSim(nid, cacheAddr){

  def assertEqual(sPort: MissSpike): Unit = {
    assertEqual(sPort.asInstanceOf[SpikeEvent])
    assert(sPort.replaceNid.toInt == replaceNid, s"${sPort.replaceNid.toInt} ${replaceNid}")
    assert(sPort.action.toEnum == action, s"${sPort.action.toEnum} ${action}")
  }
}

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
      (tag<<CacheConfig.setIndexRange.length) | setIndex
    }

    override def toString = {
      s"valid:$valid-locked:$locked-tag:${tag.toHexString}-t:$timestamp"
    }
  }

  def currentTime:Int = dut.io.timestamp.toInt

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
    dut.clockDomain.waitSamplingWhere(spikeInQueue.isEmpty)
    dut.clockDomain.waitSamplingWhere(!dut.io.spikeIn.valid.toBoolean)
    def queueEmpty:Boolean = Seq(missQueue, hitQueue, failQueue, doneQueue)
      .map(_.isEmpty).reduce(_ && _)
    dut.clockDomain.waitSamplingWhere(queueEmpty)
    dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)
  }

  def flush(): Unit = {
    dut.io.flush.valid #= true
    for (setIndex <- tagRam.indices) {
      for (way <- 0 until CacheConfig.ways) {
        val t = tagRam(setIndex)(way)
        if (dut.io.csr.learning.toBoolean && t.valid) {
          val writeBackSpike = new MissSpikeSim(
            nid = 0,
            cacheAddr = genCacheAddress(setIndex, way),
            replaceNid = t.nid(setIndex),
            action = MissAction.WRITE_BACK
          )
          missQueue.enqueue(writeBackSpike)
        }
      }
    }
    dut.clockDomain.waitSamplingWhere(dut.io.flush.ready.toBoolean)
    dut.io.flush.valid #= false
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
            println(s"$nid compulsory at s-$setIndex w-$way addr ${missSpike.cacheAddr.toHexString}")
          }
          missQueue.enqueue(missSpike)
        }
      }
    }

    if(!hit && !compulsory){
      val timestampUpBound = 1<<CacheConfig.tagTimestampWidth
      var replaceNid, replaceWay, timestampDiff = 0

      for ((t, way) <- tagSet.zipWithIndex) {
        val replaceLastWayValid = way == CacheConfig.ways - 1 && !t.locked
        val thisTimestampDiff = (dut.io.timestamp.toInt + timestampUpBound - t.timestamp) % timestampUpBound
        val inRefractory = thisTimestampDiff <= dut.io.csr.refractory.toInt
        val replaceValid = !t.locked && !inRefractory && thisTimestampDiff > timestampDiff
        if (replaceValid || (!replace && replaceLastWayValid)) {
          replace = true
          replaceNid = t.nid(setIndex)
          replaceWay = way
          timestampDiff = thisTimestampDiff
        }
      }

      if(replace){
        val t = tagSet(replaceWay)
        val oldTimestamp = t.timestamp
        t.tag = tag
        t.timestamp = currentTime
        t.locked = true
        t.valid = true
        val missSpike = new MissSpikeSim(
          nid = nid,
          cacheAddr = genCacheAddress(setIndex, replaceWay),
          replaceNid = if (dut.io.csr.learning.toBoolean) replaceNid else 0,
          action = if (dut.io.csr.learning.toBoolean) MissAction.REPLACE else MissAction.OVERRIDE
        )
        if (printState) {
          println(s"$nid replace $replaceNid at way $replaceWay timestamp $oldTimestamp")
        }
        missQueue.enqueue(missSpike)
      }else{
        //tagSet.foreach(t => println(t.toString))
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
    missSpike.assertEqual(dut.io.missSpike)
    if(!dut.io.flush.valid.toBoolean){
      doneQueue.enqueue(missSpike.asInstanceOf[SpikeEventSim])
    }
  }

  StreamMonitor(dut.io.hitSpike, dut.clockDomain) { s =>
    val hitSpike = hitQueue.dequeue()
    hitSpike.assertEqual(dut.io.hitSpike)
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
    dut.io.timestamp #= 0
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

  test("flush with write back"){
    complied.doSim { dut =>
      val spike = (0 until CacheConfig.lines).map(nid => new SpikeSim(nid))
      val agent = initDutWithSpike(dut, spike)
      agent.waitDone()
      agent.flush()
      dut.clockDomain.waitSamplingWhere(agent.missQueue.isEmpty)
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
      dut.io.timestamp #= 2
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
      dut.io.timestamp #= 1 // in refractory lock
      val missSpike = spike.map(s => new SpikeSim(nid = s.nid + 0xF000))
      agent.sendSpike(missSpike)
      agent.waitDone()
    }
  }

  test("replace LRU test") {
    complied.doSim { dut =>
      val spike = (0 until CacheConfig.lines).map(nid => new SpikeSim(nid))
      val t1HitSpike = spike.take(CacheConfig.setSize) ++ spike.drop(CacheConfig.lines - CacheConfig.setSize)
      val spikeForReplace = spike.slice(CacheConfig.setSize, CacheConfig.setSize + CacheConfig.lines - t1HitSpike.length)
      val t3ReplaceSpike = spikeForReplace.map(s => new SpikeSim(nid = s.nid + 0xF000))
      val agent = initDutWithSpike(dut, spike)

      dut.io.timestamp #= 1
      agent.sendSpike(t1HitSpike)
      agent.waitDone()

      dut.io.timestamp #= 3
      agent.sendSpike(t3ReplaceSpike)

      for (s <- spikeForReplace) {
        dut.clockDomain.waitSamplingWhere(dut.io.missSpike.valid.toBoolean && dut.io.missSpike.ready.toBoolean)
        assert(dut.io.missSpike.replaceNid.toInt === s.nid)
      }

      agent.waitDone()
    }
  }

  test("random test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      dut.io.csr.learning #= Random.nextBoolean()
      val nidBase = Random.nextInt(0xF000)
      val spike = (0 until CacheConfig.lines * 10).map(nid => new SpikeSim(nidBase + nid))

      for(t <- 0 until 10){
        dut.io.timestamp #= t % (1<<CacheConfig.tagTimestampWidth)
        val thisSpike = Random.shuffle(spike).take(100)
        agent.sendSpike(thisSpike)
        agent.waitDone()
      }
    }
  }
}

class MissSpikeManagerAgent(dut:MissSpikeManager) {
  val (missSpikeDriver, missSpikeQueue) = StreamDriver.queue(dut.io.missSpike, dut.clockDomain)
  val cache = MemAccessBusMemSlave(dut.io.bus, dut.clockDomain, 3)
  val readySpikeQueue = mutable.Queue[SpikeEventSim]()
  val aerFetchedQueue = mutable.Queue[AerPacketSim]()

  StreamReadyRandomizer(dut.io.readySpike, dut.clockDomain)
  StreamReadyRandomizer(dut.io.aerOut.head, dut.clockDomain)
  StreamReadyRandomizer(dut.io.aerOut.body, dut.clockDomain)
  dut.io.readySpike.ready #= true
  dut.io.aerOut.head.ready #= true
  dut.io.aerOut.body.ready #= true

  StreamMonitor(dut.io.readySpike, dut.clockDomain){sPort =>
    val readySpike = readySpikeQueue.dequeue()
    readySpike.assertEqual(sPort)
  }

  val aerDriver = AerDriver(dut.io.aerIn, dut.clockDomain)
  val aerMonitor = AerMonitor(dut.io.aerOut, dut.clockDomain)

  StreamMonitor(dut.io.missSpike, dut.clockDomain){ missSpike =>
    if(missSpike.action.toEnum!=MissAction.WRITE_BACK){
      val p = aerFetchedQueue.dequeue()
      aerDriver.sendPacket(p)
    }
  }

  def sendSpike(spike:Seq[MissSpikeSim]): Unit ={
    for(s <- spike) {
      missSpikeQueue.enqueue{port =>
        port.action #= s.action
        port.nid #= s.nid
        port.replaceNid #= s.replaceNid
        port.cacheLineAddr #= s.cacheAddr
      }

      if (s.action != MissAction.OVERRIDE) {
        // read data form cache
        val addrBase = s.cacheAddr << dut.cacheLineAddrOffset
        val data64 = (0 to dut.io.len.toInt).map { lineOffset =>
          val addr = addrBase | (lineOffset << 3)
          cache.mem.readBigInt(addr, 8)
        }
        val aerWbPacket = AerPacketSim(0, 0, 0, AER.TYPE.W_WRITE, s.replaceNid, data64)
        aerMonitor.addPacket(aerWbPacket)
      }
      if(s.action != MissAction.WRITE_BACK){
        val aerFetchPacket = AerPacketSim(0, 0, 0, AER.TYPE.W_FETCH, s.nid, Seq())
        aerMonitor.addPacket(aerFetchPacket)
        readySpikeQueue.enqueue(s.asInstanceOf[SpikeEventSim])
        val fetchedPacket = aerFetchPacket.copy(
          eventType = AER.TYPE.W_WRITE ,
          data = Seq.fill(128)(BigInt(randomUIntN(20)))
        )
        aerFetchedQueue.enqueue(fetchedPacket)
      }
    }
  }

  def waiteDone(): Unit ={
    dut.clockDomain.waitSamplingWhere(missSpikeQueue.isEmpty)
    dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)
  }
}

class MissSpikeManagerTest extends AnyFunSuite {
  val complied = simConfig.compile(new MissSpikeManager)

  def seqMissSpikeGen(action: MissAction.E): Seq[MissSpikeSim] = {
    (0 until CacheConfig.lines).map { nid =>
      new MissSpikeSim(
        nid = nid,
        cacheAddr = nid,
        replaceNid = nid + 0xF000,
        action = action
      )
    }
  }

  def initDut(dut: MissSpikeManager): MissSpikeManagerAgent = {
    dut.clockDomain.forkStimulus(2)
    SimTimeout(1000000)
    dut.io.len #= 127
    new MissSpikeManagerAgent(dut)
  }

  def initWithSpike(dut: MissSpikeManager): MissSpikeManagerAgent = {
    val agent = initDut(dut)
    val initSpike = seqMissSpikeGen(MissAction.OVERRIDE)
    agent.sendSpike(initSpike)
    dut.clockDomain.waitSamplingWhere(dut.io.missSpike.ready.toBoolean && dut.io.missSpike.valid.toBoolean)
    agent.waiteDone()
    agent
  }

  test("overwrite test") {
    complied.doSim { dut =>
      initWithSpike(dut)
    }
  }

  test("write back only test"){
    complied.doSim { dut =>
      val agent = initWithSpike(dut)
      val wbSpike = seqMissSpikeGen(MissAction.WRITE_BACK)
      agent.sendSpike(wbSpike)
      agent.waiteDone()
    }
  }

  test("replace test"){
    complied.doSim {dut =>
      val agent = initWithSpike(dut)
      val replaceSpike = seqMissSpikeGen(MissAction.REPLACE)
      agent.sendSpike(replaceSpike)
      agent.waiteDone()
    }
  }
}

class SpikeManagerTest extends AnyFunSuite {
  val complied = simConfig.compile(new SpikeManager)
  val dim = 1024
  val len = 128

  test("test"){
    complied.doSim {dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(1000000)

      val dataManager = AerPacketManager(dut.io.aerIn, dut.io.aerOut, dut.clockDomain, dim, len)
      val memTruth = dataManager.memSpikeData.transpose.transpose // deep copy
      val cache = MemAccessBusMemSlave(dut.io.bus, dut.clockDomain, 3)
      val (spikeDriver, spikeQueue) = StreamDriver.queue(dut.io.spike, dut.clockDomain)
      spikeDriver.transactionDelay = () => 0
      val (_, doneSpikeQueue) = StreamDriver.queue(dut.io.spikeEventDone, dut.clockDomain)

      StreamReadyRandomizer(dut.io.spikeEvent, dut.clockDomain)
      StreamMonitor(dut.io.spikeEvent, dut.clockDomain) { se =>
        val nid = se.nid.toInt
        val cacheLineAddrOffset = log2Up(CacheConfig.size / CacheConfig.lines)
        for (offset <- 0 until len) {
          val addr = (se.cacheLineAddr.toLong << cacheLineAddrOffset) | (offset << 3)
          val d = cache.mem.readBigInt(addr, 8) + 1
          cache.mem.writeBigInt(addr, d, 8)
          memTruth(nid)(offset) = d
        }
        val cacheAddr = se.cacheLineAddr.toInt
        doneSpikeQueue.enqueue { doneSe =>
          doneSe.nid #= nid
          doneSe.cacheLineAddr #= cacheAddr
        }
      }

      dut.io.csr.len #= len - 1
      dut.io.csr.learning #= false
      dut.io.csr.refractory #= 1
      dut.io.timestamp #= 3
      dut.io.flush.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.io.flush.ready.toBoolean)
      dut.io.csr.learning #= true
      dut.io.flush.valid #= false

      val epoch = 7
      val spikeAll = (0 until dim).map(nid => new SpikeSim(nid))
      val epochSpike = Seq.fill(epoch)(Random.shuffle(spikeAll).take(70))
      for((spikeIn, t) <- epochSpike.zipWithIndex){
        for(s <- spikeIn){
          spikeQueue.enqueue(_.nid #= s.nid)
        }
        dut.io.timestamp #= t % (1<<CacheConfig.tagTimestampWidth)
        dut.clockDomain.waitSamplingWhere(spikeQueue.isEmpty)
        dut.clockDomain.waitSampling()
        dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)
      }

      dut.io.flush.valid #= true
      dut.clockDomain.waitSamplingWhere(dut.io.flush.ready.toBoolean)
      dut.io.flush.valid #= false
      dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)
      dut.clockDomain.waitSamplingWhere(dataManager.aerBodyQueue.isEmpty)

      for(nid <- 0 until dim){
        for(i <- 0 until len){
          assert(dataManager.memSpikeData(nid)(i) == memTruth(nid)(i), s"nid $nid at $i")
        }
      }
    }
  }
}