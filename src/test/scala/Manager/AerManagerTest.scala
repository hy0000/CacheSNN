package Manager

import CacheSNN.CacheSnnTest.simConfig
import CacheSNN.sim._
import CacheSNN.AER
import RingNoC.sim._
import Util.sim.NumberTool._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.sim._

import scala.collection.mutable
import scala.util.Random

case class NidMapSim(nidBase: Int,
                     len: Int,
                     addrBase: Int,
                     dest: Int)
case class PostNidMapSim(nidBase: Int,
                         len: Int)

case class AerManagerAgent(dut:AerManager) {
  val mainMem = AxiMemorySim(dut.io.axi, dut.clockDomain, AxiMemorySimConfig())
  mainMem.start()
  val aerDriver = AerDriver(dut.io.aer, dut.clockDomain)
  val aerPacketRec = Array.fill(16)(mutable.Queue[AerPacketSim]())
  val (_, preSpikeCmdQueue) = StreamDriver.queue(dut.io.preSpikeCmd, dut.clockDomain)

  new NocInterfaceMonitor(dut.io.localSend, dut.clockDomain) {
    override def onPacket(p: NocPacket) = {
      val bp = BasePacketSim(p)
      val aerP = AerPacketSim(bp)
      aerPacketRec(bp.dest).enqueue(aerP)
    }
  }

  def setNidMap(nidMap: Seq[NidMapSim]): Unit ={
    dut.io.nidMap.flattenForeach(bt => setBigInt(bt, 0))
    for((m, p) <- nidMap.zip(dut.io.nidMap)){
      p.valid #= true
      p.nidBase #= m.nidBase
      p.len #= m.len
      p.addrBase #= m.addrBase
      p.dest #= m.dest
    }
  }

  def setPostNidMap(nidMap: Seq[PostNidMapSim]): Unit ={
    dut.io.postNidMap.flattenForeach(bt => setBigInt(bt, 0))
    for ((m, p) <- nidMap.zip(dut.io.postNidMap)) {
      p.valid #= true
      p.nidBase #= m.nidBase
      p.len #= m.len
    }
  }
}

class AerManagerTest extends AnyFunSuite {
  val complied = simConfig.compile(new AerManager)

  def initDut(dut:AerManager):AerManagerAgent = {
    dut.clockDomain.forkStimulus(2)
    SimTimeout(100000)
    AerManagerAgent(dut)
  }

  case class PreSpikeInfo(spikeRaw:Seq[BigInt], addrBase:Int, nidBase:Int, dest:Int)

  test("pre spike test"){
    complied.doSim{ dut =>
      val agent = initDut(dut)
      val n = 7
      val preSpike = (0 until n).map{i =>
        val spike = Seq.fill(1024)(Random.nextInt(1))
        PreSpikeInfo(
          spikeRaw = vToRawV(spike, width = 1, n = 64),
          addrBase = 0xF00 + i,
          nidBase = 0x10 + i,
          dest = i
        )
      }

      for(s <- preSpike){
        for(i <- s.spikeRaw.indices){
          val addr = (s.addrBase<<10) + i*8
          agent.mainMem.memory.writeBigInt(addr, s.spikeRaw(i), width = 8)
        }
        agent.preSpikeCmdQueue.enqueue{cmd =>
          cmd.dest #= s.dest
          cmd.nidBase #= s.nidBase
          cmd.addrBase #= s.addrBase
        }
      }

      for((s, dest) <- preSpike.zipWithIndex){
        dut.clockDomain.waitSamplingWhere(agent.aerPacketRec(dest).nonEmpty)
        val aer = agent.aerPacketRec(dest).dequeue()
        assert(aer.data == s.spikeRaw)
        assert(aer.nid == (s.nidBase<<10))
      }
    }
  }

  test("post spike test") {
    complied.doSim { dut =>
      val agent = initDut(dut)
      val n = 3
      val postSpikeBufAddr = 0xA00L
      dut.io.postAddrBase #= postSpikeBufAddr

      val postNidMapSim = (0 until n).map { i =>
        PostNidMapSim(
          nidBase = i, len = 7
        )
      }
      val postSpikePacket = (0 until n).map{src =>
        val spike = Seq.fill(512)(Random.nextInt(1))
        AerPacketSim(
          dest = 3, src = src, id = 0,
          eventType = AER.TYPE.POST_SPIKE,
          nid = postNidMapSim(src).nidBase<<10,
          data = vToRawV(spike, width = 1, n = 64)
        )
      }

      agent.setPostNidMap(postNidMapSim)
      agent.aerDriver.sendPacket(postSpikePacket)

      for(i <- 0 until n){
        dut.clockDomain.waitSamplingWhere(dut.io.nidEpochDone(i).toBoolean)
        for(j <- 0 until 8){
          val addr = postSpikeBufAddr + i * 8*8 + j * 8
          val spikeRaw = agent.mainMem.memory.readBigInt(addr, length = 8)
          assert(spikeRaw == postSpikePacket(i).data(j))
        }
      }
    }
  }

  test("weight write test") {
    complied.doSim { dut =>
      val agent = initDut(dut)
      val n = 4
      val nidMapSim = (0 until n).map { i =>
        NidMapSim(
          nidBase = i,
          len = 127,
          addrBase = 0x10000 + i * 512,
          dest = i
        )
      }
      agent.setNidMap(nidMapSim)

      val weightWritePacket = (0 until n).flatMap{src =>
        val m = Random.nextInt(60)
        val nidOffsets = Random.shuffle((0 until 512).toList).take(m)
        nidOffsets.map{nidOff =>
          val length = nidMapSim(src).len + 1
          val data = Seq.fill(length)(BigInt(64, Random))
          AerPacketSim(
            dest = 3, src = src, id = 0,
            eventType = AER.TYPE.W_WRITE,
            nid = (nidMapSim(src).nidBase << 10) + nidOff,
            data = data
          )
        }
      }

      agent.aerDriver.sendPacket(weightWritePacket)
      agent.aerDriver.waitDone()
      dut.clockDomain.waitSampling(200 * 8) // wait data wb
      for(wp <- weightWritePacket){
        val nidOff = wp.nid % 1024
        val addrBase = (nidMapSim(wp.src).addrBase + nidOff) << 10
        for((dTruth, i) <- wp.data.zipWithIndex){
          val addr = addrBase + i*8
          val d = agent.mainMem.memory.readBigInt(addr, length = 8)
          assert(d==dTruth, s"at addr ${addr.toHexString} i$i")
        }
      }
    }
  }

  test("weight fetch test") {
    complied.doSim { dut =>
      val agent = initDut(dut)
      val n = 4
      val nidMapSim = (0 until n).map { i =>
        NidMapSim(
          nidBase = i,
          len = 127,
          addrBase = 0x10000 + i * 512,
          dest = i
        )
      }
      agent.setNidMap(nidMapSim)

      val weightFetchPacket = (0 until n).flatMap { src =>
        val m = Random.nextInt(60)
        val nidOffsets = Random.shuffle((0 until 512).toList).take(m)
        nidOffsets.map { nidOff =>
          AerPacketSim(
            dest = 3, src = src, id = 0,
            eventType = AER.TYPE.W_FETCH,
            nid = (nidMapSim(src).nidBase << 10) + nidOff,
            data = Seq()
          )
        }
      }

      val dataSeq = weightFetchPacket.map{p =>
        val length = nidMapSim(p.src).len + 1
        Seq.fill(length)(BigInt(64, Random))
      }

      // init data to memory
      for((dSeq, wp) <- dataSeq.zip(weightFetchPacket)){
        val nidOff = wp.nid % 1024
        val addrBase = (nidMapSim(wp.src).addrBase + nidOff) << 10
        for((d, i) <- dSeq.zipWithIndex){
          val addr = addrBase + i * 8
          agent.mainMem.memory.writeBigInt(addr, d, width = 8)
        }
      }

      agent.aerDriver.sendPacket(weightFetchPacket)

      for((p, i) <- weightFetchPacket.zipWithIndex){
        if(agent.aerPacketRec(p.src).isEmpty){
          dut.clockDomain.waitSamplingWhere(agent.aerPacketRec(p.src).nonEmpty)
        }
        val recP = agent.aerPacketRec(p.src).dequeue()
        assert(recP.nid == p.nid)
        assert(recP.data == dataSeq(i))
      }
    }
  }

  // 1. send preSpike
  // 2. weight access
  // 3. send post spike after weight done
  test("mix test") {
    complied.doSim { dut =>
      val agent = initDut(dut)
      val postSpikeAddrBase = 0xE00
      val weightAddrBase = 0x0
      val preSpikeAddrBase = 0x100
      val preSpikeNid = 1024
      val postSpikeNid = 1024 + 512

      // generate pre spike
      val preSpike = Seq.fill(1024)(Random.nextInt(1))
      val preSpikeInfo = PreSpikeInfo(
        spikeRaw = vToRawV(preSpike, width = 1, n = 64),
        addrBase = preSpikeAddrBase,
        nidBase = preSpikeNid>>10,
        dest = 0
      )
      for (i <- preSpikeInfo.spikeRaw.indices) {
        val addr = (preSpikeInfo.addrBase << 10) + i * 8
        agent.mainMem.memory.writeBigInt(addr, preSpikeInfo.spikeRaw(i), width = 8)
      }

      // generate post spike
      val postNidMapSim = Seq(PostNidMapSim(nidBase = postSpikeNid>>10, len = 7))
      val postSpike = Seq.fill(512)(Random.nextInt(1))
      val postSpikePacket = AerPacketSim(
        dest = 3, src = 0, id = 0,
        eventType = AER.TYPE.POST_SPIKE,
        nid = postNidMapSim.head.nidBase << 10,
        data = vToRawV(postSpike, width = 1, n = 64)
      )

      val nidMapSim = Seq(NidMapSim(
        nidBase = preSpikeNid>>10,
        len = 127,
        addrBase = weightAddrBase,
        dest = 0
      ))

      // generate weight
      val m = Random.nextInt(60)
      val nidOffsets = Random.shuffle((0 until 512).toList).take(m)
      val weightWritePacket = nidOffsets.map { nidOff =>
        val length = nidMapSim.head.len + 1
        val data = Seq.fill(length)(BigInt(64, Random))
        AerPacketSim(
          dest = 3, src = 0, id = 0,
          eventType = AER.TYPE.W_WRITE,
          nid = (nidMapSim.head.nidBase << 10) + nidOff,
          data = data
        )
      }

      val weightFetchPacket = weightWritePacket.map{p =>
        p.copy(dest = p.src, src = p.dest, eventType = AER.TYPE.W_FETCH, data = Seq())
      }

      // set reg
      agent.setNidMap(nidMapSim)
      agent.setPostNidMap(postNidMapSim)
      dut.io.postAddrBase #= postSpikeAddrBase

      // send preSpike
      agent.preSpikeCmdQueue.enqueue { cmd =>
        cmd.dest #= preSpikeInfo.dest
        cmd.nidBase #= preSpikeInfo.nidBase
        cmd.addrBase #= preSpikeInfo.addrBase
      }
      dut.clockDomain.waitSamplingWhere(agent.preSpikeCmdQueue.isEmpty)

      // write weight to main men then fetch
      agent.aerDriver.sendPacket(weightWritePacket)
      agent.aerDriver.sendPacket(weightFetchPacket)
      // send post spike
      agent.aerDriver.sendPacket(postSpikePacket)

      // wait done
      dut.clockDomain.waitSamplingWhere(dut.io.nidEpochDone(0).toBoolean)

      val weightWritePacketRec = agent.aerPacketRec.head.drop(1) // drop pre spike packet
      for ((recP, i) <- weightWritePacketRec.zipWithIndex) {
        val memData = (0 to nidMapSim.head.len).map{j =>
          val nidOffset = recP.nid % 1024
          val addr = ((weightAddrBase + nidOffset) << 10) + j * 8
          agent.mainMem.memory.readBigInt(addr, length = 8)
        }
        assert(recP.nid == weightFetchPacket(i).nid)
        assert(recP.data == memData)
      }
    }
  }
}
