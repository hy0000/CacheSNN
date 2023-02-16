package Neuron

import CacheSNN.{AER, PacketType}
import CacheSNN.CacheSnnTest._
import CacheSNN.sim.{AerPacketSim, BasePacketAgent, BasePacketSim}
import Neuron.sim.NeuronCoreConfigSim
import RingNoC.NocInterfaceLocal
import Util.sim.MemReadWriteMemSlave
import Util.sim.NumberTool._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.StreamReadyRandomizer

import scala.collection.mutable
import scala.util.Random

case class NeuronJob(nidBase:Int,
                     mapInfo: Array[MapInfo],
                     threshold: Int){
  def spikeRaw: Seq[BigInt] = {
    val current = mapInfo.map(_.current)
    val currentSum = current.transpose.map(_.sum)
    val spikes = currentSum.map(_ >= threshold).map(booleanToInt)
    vToRawV(spikes, width = 1, 64)
  }
  
  def toNeuronCoreConfig:NeuronCoreConfigSim = {
    NeuronCoreConfigSim(
      nidBase = nidBase,
      acc = mapInfo.length-1,
      srcList = mapInfo.map(_.src),
      threshold = threshold,
      spikeLen = mapInfo.head.current.length
    )
  }
}

case class MapInfo(current: Array[Int],
                   src: Int){
  def currentRaw: Seq[BigInt] = vToRawV(current, 16, 4)
}

class NeuronCoreAgent(noc:NocInterfaceLocal, clockDomain: ClockDomain)
  extends BasePacketAgent(noc, clockDomain){

  val threshold = 256

  val jobQueue = mutable.Queue[NeuronJob]()

  def addJob(job: NeuronJob): Unit = {
    jobQueue.enqueue(job)
  }

  val coreRecSpike = Array.fill(6)(0)

  override def onAER(p: BasePacketSim): Unit = {
    val aerP = AerPacketSim(p)
    val targetJob = jobQueue.find(job => job.nidBase==aerP.nid).get
    assert(aerP.eventType == AER.TYPE.POST_SPIKE)
    assert(aerP.nid == targetJob.nidBase, s"${aerP.nid} ${targetJob.nidBase}")
    for(i <- targetJob.spikeRaw.indices){
      assert(aerP.data(i) == targetJob.spikeRaw(i), s"${aerP.data(i).toString(16)} ${ targetJob.spikeRaw(i).toString(16)} at $i")
    }
    coreRecSpike(aerP.dest) += 1
  }

  def runTest(): Unit = {
    import Neuron.sim.NeuronRegAddr._
    val cfg = jobQueue.map(_.toNeuronCoreConfig)
    val regs = NeuronCoreConfigSim.genRegField(cfg)
    regWrite(NidField, regs.nidField)
    regWrite(MapField, regs.mapField)
    regWrite(Threshold0, regs.threshold0)
    regWrite(Threshold1, regs.threshold1)
    regWrite(LenField, regs.lenField)
    //clear current
    dataWrite(addr = 0, data = Seq.fill(256)(BigInt(0)))
    dataWrite(addr = 256*8, data = Seq.fill(256)(BigInt(0)))
    // send current packet
    for(job <- jobQueue){
      for(mapInfo <- job.mapInfo){
        val dest = 3
        val p = new AerPacketSim(dest, mapInfo.src, 0, AER.TYPE.CURRENT, job.nidBase, mapInfo.currentRaw)
        sendAer(p)
      }
    }
    // wait fire
    clockDomain.waitSamplingWhere(coreRecSpike(3)==jobQueue.size)
    for (job <- jobQueue) {
      for (mapInfo <- job.mapInfo) {
        assert(coreRecSpike(mapInfo.src)==1)
      }
    }
  }
}

class NeuronCoreTest extends AnyFunSuite {
  val complied = simConfig.compile(new NeuronCore)

  def initDut(dut: NeuronCore): NeuronCoreAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(100000)
    new NeuronCoreAgent(dut.noc, dut.clockDomain)
  }

  test("one current fire test"){
    complied.doSim{ dut =>
      val agent = initDut(dut)
      val current = Array.fill(512)(1)
      val mapInfo = MapInfo(current = current, src = 0)
      val job = NeuronJob(0x1F<<9, Array(mapInfo), threshold = 1)
      agent.addJob(job)
      agent.runTest()
    }
  }

  test("four current fire test"){
    complied.doSim{ dut =>
      val agent = initDut(dut)
      val mapInfo = Seq(0, 1, 4, 5).map{src =>
        val current = Array.fill(256 + 64)(Random.nextInt(2))
        MapInfo(current, src)
      }
      val job = NeuronJob(0x0, mapInfo.toArray, threshold = 2)
      agent.addJob(job)
      agent.runTest()
    }
  }

  test("four current acc test"){
    complied.doSim{ dut =>
      val agent = initDut(dut)
      val jobs = Seq(0, 1, 4, 5).map{src =>
        val current = Array.fill(512)(randomInt16)
        val mapInfo = MapInfo(current, src)
        NeuronJob(src<<10, Array(mapInfo), threshold = randomInt16)
      }
      for(job <- jobs){
        agent.addJob(job)
      }
      agent.runTest()
    }
  }

  test("two layer parallel test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      val mapInfo0 = Seq(0, 1).map { src =>
        val current = Array.fill(512)(randomInt16)
        MapInfo(current, src)
      }
      val mapInfo1 = MapInfo(Array.fill(256)(randomInt16), 4)

      val job0 = NeuronJob(0xA000, mapInfo0.toArray, threshold = randomInt16)
      val job1 = NeuronJob(0xB000, Array(mapInfo1), threshold = randomInt16)
      agent.addJob(job0)
      agent.addJob(job1)
      agent.runTest()
    }
  }
}

class NeuronComputeTest extends AnyFunSuite {
  val complied = simConfig.compile(new NeuronCompute)

  test("compute test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000)
      val ram = new MemReadWriteMemSlave(dut.io.cRam, dut.clockDomain)
      val len = 512
      val accTimes = Random.nextInt(4)
      val current = Array.tabulate(accTimes+1, len){
        (_, _) => randomInt16 / 8
      }

      val currentSum = current.transpose.map(_.sum)
      val threadHold = currentSum.sum / currentSum.length
      val spikes = currentSum.map(_ >= threadHold).map(booleanToInt)
      val currentSumFired = currentSum.zip(spikes).map(z => z._1 * z._2)

      dut.io.threadHold #= threadHold
      dut.io.current.valid #= false

      // thread for assert spike
      val spikeMonitor = fork {
        val spikeRaw = vToRawV(spikes, width = 1, 64)
        for(i <- 0 until len / 64){
          dut.clockDomain.waitSamplingWhere(dut.io.maskSpike.valid.toBoolean)
          assert(dut.io.maskSpike.address.toInt==i)
          assert(dut.io.maskSpike.data.toBigInt==spikeRaw(i))
        }
      }

      // input
      for(t <- 0 to accTimes){
        dut.io.fire #= t==accTimes
        dut.io.current.valid #= true
        for(i <- 0 until len / 4){
          dut.io.current.last #= i==(len / 4 -1)
          dut.io.current.fragment #= vToRaw(current(t).slice(i*4, (i+1)*4), 16)
          dut.clockDomain.waitSampling()
        }
        dut.io.current.valid #= false
        dut.clockDomain.waitSampling(Random.nextInt(10)+3)
      }

      // assert current sum
      dut.clockDomain.waitSampling(4)
      for(i <- 0 until len / 4){
        val currentRead = ram.mem(i)
        val currentV = rawToV(currentRead, 16, 4)
        for(j <- 0 until 4){
          assert(currentV(j)==currentSumFired(i*4 + j), s"at $i")
        }
      }
      spikeMonitor.join()
    }
  }
}

class SpikeRamTest extends AnyFunSuite {
  val complied = simConfig.compile(new SpikeRam)

  test("read test"){
    complied.doSim(1096100458){ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(1000)
      StreamReadyRandomizer(dut.io.readRsp, dut.clockDomain)
      dut.io.readStart #= false
      val len = Random.nextInt(8)
      dut.io.len #= len

      val spikes = Array.fill(len + 1)(BigInt(64, Random))
      // write data
      for(i <- 0 to len){
        dut.io.write.valid #= true
        dut.io.write.data #= spikes(i)
        dut.io.write.address #= i
        dut.clockDomain.waitSampling()
      }
      dut.io.write.valid #= false

      // read cmd
      fork{
        for (_ <- 0 until 4) {
          dut.io.readStart #= true
          dut.clockDomain.waitSampling()
          dut.io.readStart #= false
          dut.clockDomain.waitSamplingWhere(
            dut.io.readRsp.last.toBoolean &&
              dut.io.readRsp.valid.toBoolean &&
              dut.io.readRsp.ready.toBoolean
          )
        }
      }

      for (t <- 0 until 4) {
        for(i <- 0 to len){
          dut.clockDomain.waitSamplingWhere(dut.io.readRsp.valid.toBoolean && dut.io.readRsp.ready.toBoolean)
          assert(dut.io.readRsp.fragment.toBigInt==spikes(i), s"at t$t i$i")
        }
      }
    }
  }
}