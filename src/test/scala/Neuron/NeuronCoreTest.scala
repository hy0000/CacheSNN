package Neuron

import CacheSNN.AER
import CacheSNN.CacheSnnTest._
import CacheSNN.sim.{AerPacketSim, BasePacketAgent, BasePacketSim}
import RingNoC.NocInterfaceLocal
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
    for(i <- aerP.data.indices){
      assert(aerP.data(i) == targetJob.spikeRaw(i), s"${aerP.data(i).toString(2)} ${ targetJob.spikeRaw(i).toString(2)} at $i")
    }
    coreRecSpike(aerP.dest) += 1
  }

  def runTest(): Unit = {
    var i = 0
    // reg config
    var nidField, mapField = 0L
    var thresholdField = BigInt(0)
    for(job <- jobQueue){
      val srcList = job.mapInfo.map(_.src)
      val srcOh = Seq(0, 1, 4, 5).map(srcId => srcList.contains(srcId)).map(booleanToInt)
      val srcOhRaw = vToRaw(srcOh, 1).toLong
      nidField |= ((job.nidBase >> 8) | 1) << (i*8)
      mapField |= (((job.mapInfo.length-1)<<6) | (i<<4) | srcOhRaw) << (i*8)
      thresholdField |= job.threshold<<(i*16)
      i += 1
    }
    val threshold0 = (thresholdField & ((1L<<32) - 1)).toLong
    val threshold1 = (thresholdField>>32).toLong
    regWrite(0x00, nidField)
    regWrite(0x04, mapField)
    regWrite(0x08, threshold0)
    regWrite(0x0C, threshold1)
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
        val current = Array.fill(512)(Random.nextInt(2))
        MapInfo(current, src)
      }
      val job = NeuronJob(0x0, mapInfo.toArray, threshold = 2)
      agent.addJob(job)
      agent.runTest()
    }
  }

  test("four current acc test"){

  }

  test("two layer parallel test"){

  }
}

class NeuronComputeTest extends AnyFunSuite {
  val complied = simConfig.compile(new NeuronCompute)

  test("compute test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000)
      val len = 512
      val accTimes = Random.nextInt(4)
      val threadHold = 1<<16 / 8
      val current = Array.tabulate(accTimes+1, len){
        (_, _) => randomInt16 / 8
      }
      dut.io.threadHold #= threadHold
      dut.io.current.valid #= false

      val currentSum = current.transpose.map(_.sum)
      val spikes = currentSum.map(_ >= threadHold).map(booleanToInt)

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
        dut.io.acc #= t!=0
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
        val currentRead = getBigInt(dut.currentMem, i)
        val currentV = rawToV(currentRead, 16, 4)
        for(j <- 0 until 4){
          assert(currentV(j)==currentSum(i*4 + j))
        }
      }
      spikeMonitor.join()
    }
  }
}

class SpikeRamTest extends AnyFunSuite {
  val complied = simConfig.compile(new SpikeRam)

  test("read test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(1000)
      StreamReadyRandomizer(dut.io.readRsp, dut.clockDomain)
      dut.io.readStart #= false

      val spikes = Array.fill(8)(BigInt(64, Random))
      // write data
      for(i <- 0 until 8){
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

      for (_ <- 0 until 4) {
        for(i <- 0 until 8){
          dut.clockDomain.waitSamplingWhere(dut.io.readRsp.valid.toBoolean && dut.io.readRsp.ready.toBoolean)
          assert(dut.io.readRsp.fragment.toBigInt==spikes(i))
        }
      }
    }
  }
}