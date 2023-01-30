package Synapse

import CacheSNN.AER
import CacheSNN.CacheSnnTest._
import CacheSNN.sim._
import RingNoC.NocInterfaceLocal
import Synapse.SynapseCore.AddrMapping
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

  val initialWeight = weight.transpose.transpose
  val ltdLut = Array.fill(16)(Random.nextInt(256))
  val ltpLut = Array.fill(16)(Random.nextInt(256))

}

class SynapseCoreTest extends AnyFunSuite {
  val complied = simConfig.compile(new SynapseCore)

  object RegAddr {
    val fieldR = 0x0
    val field0 = 0x4
    val field1 = 0x8
    val field2 = 0xC
  }

  object RegConfig {
    object Field2 {
      val refractory = 1L
      val inferenceOnly = refractory << 2
      val learning = inferenceOnly | 0x2
      val inferenceFlush = inferenceOnly | 0x1
      val learningFlush = learning | 0x1
    }

    def field0(preLen: Int, postLen:Int, postNidBase:Int): Long = {
      ((postLen/4-1)<<24) | ((preLen/4-1)<<16) | postNidBase
    }

    val fieldRFree = 1L<<16
  }

  val preLen = 256
  val postLen = 512
  val postNidBase = 0xF00

  def initDut(dut:SynapseCore): SynapseCoreAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(10000000)
    val agent = new SynapseCoreAgent(dut.noc, dut.clockDomain)
    // initial flush
    agent.regWrite(RegAddr.field2, RegConfig.Field2.inferenceFlush)
    agent.regWrite(RegAddr.field1, 0x32)
    // set parameter
    agent.regWrite(RegAddr.field0, RegConfig.field0(preLen, postLen, postNidBase))
    // wait flush over
    while (agent.regRead(RegAddr.fieldR)!=RegConfig.fieldRFree){}
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
        agent.sendSpike(preSpike(t), 0, AER.TYPE.PRE_SPIKE)
        agent.waitCurrentReceived()
      }
      assert(agent.current sameElements snnModel.current)
    }
  }
}
