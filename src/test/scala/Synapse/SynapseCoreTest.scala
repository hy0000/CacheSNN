package Synapse

import CacheSNN.AER
import CacheSNN.CacheSnnTest._
import CacheSNN.sim._
import RingNoC.NocInterfaceLocal
import Synapse.SynapseCore.{AddrMapping, RegAddr, RegConfig, maxPreSpike}
import Util.sim.{NumberTool, SnnModel}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.util.Random

class SynapseCoreAgent(noc:NocInterfaceLocal, clockDomain: ClockDomain)
  extends BasePacketAgent(noc, clockDomain){
  var preLen = 256
  var postLen = 512

  val weight = Array.tabulate(preLen, postLen){
    (_, _) => 1//Random.nextInt() % 256
  }

  val current = Array.fill(postLen)(0)
  var currentReceived = false

  override def onAER(p: BasePacketSim): Unit = {
    super.onAER(p)
    val ap = AerPacketSim(p)
    ap.eventType match {
      case AER.TYPE.W_WRITE =>
        weight(ap.nid) = NumberTool.rawToV(ap.data, width = 16, 4).toArray
      case AER.TYPE.W_FETCH =>
        val wFetchPacket = ap.copy(
          dest = ap.src,
          src = ap.dest,
          eventType = AER.TYPE.W_WRITE,
          data = NumberTool.vToRawV(weight(ap.nid), width = 16, 4)
        )
        driver.sendPacket(wFetchPacket)
      case AER.TYPE.CURRENT =>
        val currentData = NumberTool.rawToV(ap.data, width = 16, 4)
        for(i <- 0 until postLen){
          current(i) += currentData(i)
        }
        currentReceived = true
    }
  }

  def waitCurrentReceived(): Unit = {
    clockDomain.waitSamplingWhere(currentReceived)
    currentReceived = false
  }

  def loadStdpParameter(ltd:Array[Int], ltp:Array[Int]): Unit ={
    val ltdRaw = NumberTool.vToRawV(ltd, width = 16, 4)
    val ltpRaw = NumberTool.vToRawV(ltp, width = 16, 4)
    dataWrite(AddrMapping.ltdLut.base.toLong, ltdRaw)
    dataWrite(AddrMapping.ltpLut.base.toLong, ltpRaw)
  }

  def waitFree(): Unit ={
    while (regRead(RegAddr.fieldR)!=RegConfig.fieldRFree){
      clockDomain.waitSampling(100)
    }
  }

  def learningFlush(): Unit ={
    regWrite(RegAddr.field2, RegConfig.Field2.learningFlush)
    waitFree()
  }

  def sendPreSpike(maskSpike:Array[Int]): Unit ={
    val spikeExtend = maskSpike ++ Array.fill(1024 - maskSpike.length)(0)
    sendSpike(spikeExtend, 0, AER.TYPE.PRE_SPIKE)
  }

  def assertSpikeHisRaw(spike:Array[Array[Int]], spikeHisRaw:Seq[BigInt]): Unit ={
    val spikeHis = NumberTool.rawToV(spikeHisRaw, width = 16, 4).map(_ & 0xFFFF)
    val spikeHisTruth = spike.transpose.map{timeLine =>
      NumberTool.vToRaw(timeLine.reverse.take(15), width = 1).toInt << 1
    }
    assert(spikeHis sameElements spikeHisTruth)
  }

  def assertSpikeHis(preSpike: Array[Array[Int]], postSpike: Array[Array[Int]]): Unit ={
    val preSpikeRaw = dataRead(AddrMapping.preSpike.base.toInt, preLen / 4)
    assertSpikeHisRaw(preSpike, preSpikeRaw)
    val posSpikeRaw = dataRead(AddrMapping.postSpike.base.toInt, postLen / 4)
    assertSpikeHisRaw(postSpike, posSpikeRaw)
  }
}

class SynapseCoreTest extends AnyFunSuite {
  val complied = simConfig.compile(new SynapseCore)

  val preLen = 256
  val postLen = 512
  val postNidBase = 0xF00

  def initDut(dut:SynapseCore): SynapseCoreAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(1000000)
    val agent = new SynapseCoreAgent(dut.noc, dut.clockDomain)
    // initial flush
    agent.regWrite(RegAddr.field2, RegConfig.Field2.inferenceFlush)
    agent.regWrite(RegAddr.field1, 0x32)
    // set parameter
    agent.regWrite(RegAddr.field0, RegConfig.field0(preLen, postLen, postNidBase))
    // clear spike ram
    agent.dataWrite(
      addr = AddrMapping.preSpike.base.toLong,
      data = Seq.fill(preLen / 4)(0)
    )
    agent.dataWrite(
      addr = AddrMapping.postSpike.base.toLong,
      data = Seq.fill(postLen / 4)(0)
    )
    // wait flush over
    agent.waitFree()
    agent
  }

  test("address mapping test"){
    complied.doSim{ dut =>
      val agent = initDut(dut)
      agent.addressMappingTest(AddrMapping.postSpike)
      agent.addressMappingTest(AddrMapping.preSpike)
    }
  }

  test("inference only test") {
    complied.doSim{ dut =>
      val agent = initDut(dut)
      agent.regWrite(RegAddr.field2, RegConfig.Field2.inferenceOnly)

      val epoch = 20
      val preSpike = Array.tabulate(epoch, preLen) {
        (_, _) => if (Random.nextBoolean()) 1 else 0
      }
      val current = Array.fill(postLen)(0)
      for (t <- 0 until epoch) {
        for (nid <- 0 until preLen) {
          if (preSpike(t)(nid) == 1) {
            for (j <- 0 until postLen) {
              current(j) += agent.weight(nid)(j)
            }
          }
        }
        agent.sendPreSpike(preSpike(t))
        agent.waitCurrentReceived()
      }
      assert(agent.current sameElements current)

      // assert spike ram is zero
      val zeroSpikePre = Array.tabulate(epoch, preLen)((_, _) => 0)
      val zeroSpikePost = Array.tabulate(epoch, postLen)((_, _) => 0)
      agent.assertSpikeHis(zeroSpikePre, zeroSpikePost)
    }
  }

  test("spike update test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      agent.regWrite(RegAddr.field2, RegConfig.Field2.learning)

      val epoch = 20
      val preSpike = Array.tabulate(epoch, preLen) {
        (t, nid) => if (t==nid) 1 else 0
      }
      val postSpike = Array.tabulate(epoch, postLen) {
        (t, nid) => if (t==nid) 1 else 0
      }

      for (t <- 0 until epoch) {
        agent.sendPreSpike(preSpike(t))
        agent.waitCurrentReceived()
        agent.sendSpike(postSpike(t), 0, AER.TYPE.POST_SPIKE)
        if (t == epoch - 1) {
          agent.learningFlush()
        }
      }
      agent.assertSpikeHis(preSpike, postSpike)
    }
  }

  test("learning test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      agent.regWrite(RegAddr.field2, RegConfig.Field2.learning)

      val epoch = 20
      val preSpike = Array.tabulate(epoch, preLen) {
        (_, _) => if (Random.nextBoolean()) 1 else 0
      }
      val postSpike = Array.tabulate(epoch, postLen){
        (_, _) => if (Random.nextBoolean()) 1 else 0
      }
      val snnModel = new SnnModel(preLen, postLen)
      agent.loadStdpParameter(
        ltd = snnModel.ltdLut,
        ltp = snnModel.ltpLut
      )

      for (t <- 0 until epoch) {
        agent.sendPreSpike(preSpike(t))
        agent.waitCurrentReceived()
        agent.sendSpike(postSpike(t), 0, AER.TYPE.POST_SPIKE)
        if (t == epoch - 1) {
          agent.learningFlush()
        }
      }

      snnModel.spikeUpdate(preSpike, postSpike)
      agent.assertSpikeHis(preSpike, postSpike)
      for(i <- 0 until preLen) {
        for(j <- 0 until postLen){
          assert(agent.weight(i)(j)==snnModel.weight(i)(j), s"at $i $j")
        }
      }
      assert(agent.current sameElements snnModel.current)
    }
  }

  test("hit rate test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      agent.regWrite(RegAddr.field2, RegConfig.Field2.inferenceOnly)

      val epoch = 20
      val preSpike = Array.tabulate(epoch, preLen) {
        (_, _) => if (Random.nextBoolean()) 1 else 0
      }

      for (t <- 0 until epoch) {
        agent.sendPreSpike(preSpike(t))
        agent.waitCurrentReceived()
      }

      val cnt = agent.regRead(RegAddr.fieldCnt)
      val hitCnt = cnt & 0xFFFF
      val missCnt = cnt >> 16
      println(s"hit rate ${hitCnt.toFloat / (hitCnt + missCnt)}")
      val cntCleared = agent.regRead(RegAddr.fieldCnt)
      assert(cntCleared == 0)
    }
  }
}
