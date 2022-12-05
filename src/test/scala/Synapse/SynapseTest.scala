package Synapse

import CacheSNN.CacheSnnTest._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.{Flow, MemReadPort}
import spinal.lib.sim.FlowMonitor
import spinal.lib.bus.amba4.axi.sim.SparseMemory

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

class SynapseTest extends AnyFunSuite {
  val complied = simConfig.compile(new Synapse)

  case class MemSlave(r:MemReadPort[Bits], w:Flow[MemWriteCmd], clockDomain: ClockDomain, readDelay:Int){
    val mem = SparseMemory()
    val rspQueue = Array.fill(readDelay)(BigInt(0))
    var queuePush = 0
    var queuePop = 1
    clockDomain.onSamplings{
      queuePush = (queuePush + 1) % readDelay
      queuePop = (queuePop + 1) % readDelay
      r.rsp #= rspQueue(queuePop)
      rspQueue(queuePop) = 0
    }

    FlowMonitor(r.cmd, clockDomain){addr =>
      rspQueue(queuePush) = mem.readBigInt(addr.toLong<<3, 8)
    }

    if(w!=null){
      FlowMonitor(w, clockDomain) { cmd =>
        mem.writeBigInt(cmd.address.toLong << 3, cmd.data.toBigInt, 8)
      }
    }
  }

  case class ExpLutSim(p:ExpLutQuery, clockDomain: ClockDomain){
    val mem = (0 until 16).toArray
    clockDomain.onSamplings{
      for ((x, y) <- p.x.zip(p.y)) {
        if(x.valid.toBoolean){
          y #= mem(x.payload.toInt)
        }else{
          y #= 0
        }
      }
    }

    def loadData() = ???
  }

  case class SynapseMemSlaves(cache:MemSlave,
                              postSpike:MemSlave,
                              current:MemSlave,
                              ltpLut:ExpLutSim,
                              ltdLut:ExpLutSim){
  }

  def initDut(dut:Synapse):SynapseMemSlaves = {
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
      dut.io.csr.learning #= true
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
