package Synapse

import CacheSNN.AER
import CacheSNN.CacheSnnTest._
import CacheSNN.sim._
import RingNoC.NocInterfaceLocal
import Synapse.SynapseCore.{AddrMapping, RegAddr, RegConfig, maxPreSpike}
import Util.SnnModel
import Util.sim.NumberTool
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
      val snnModel = new SnnModel(preLen, postLen)
      for (t <- 0 until epoch) {
        snnModel.spikeForward(preSpike(t))
        agent.sendPreSpike(preSpike(t))
        agent.waitCurrentReceived()
      }
      assert(agent.current sameElements snnModel.current)
    }
  }

  test("learning test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      agent.regWrite(RegAddr.field2, RegConfig.Field2.learning)

      val epoch = 2
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
      //assert(agent.current sameElements snnModel.current)
    }
  }
}
