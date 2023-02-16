package CacheSNN

import CacheSnnTest.simConfig
import sim._
import Manager.sim.{ManagerCoreCtrl, NidMapSim, PostNidMapSim}
import Neuron.sim.NeuronCoreConfigSim
import Util.sim.NumberTool._
import Util.sim.SnnModel
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

object CacheSnnTest {
  val simConfig = SpinalSimConfig(_spinalConfig = MySpinalConfig)
}

class CacheSnnTest extends AnyFunSuite {
  val complied = simConfig.compile(new CacheSNN)

  case class CacheSnnAgent(dut:CacheSNN) extends ManagerCoreCtrl(dut.io.axiLite, dut.clockDomain){
    val mainMem = AxiMemSim(dut.io.axi, dut.clockDomain)

    val weightAddrBase   = 0x0L
    val preSpikeBufAddr  = 0x100000L
    val postSpikeBufAddr = 0x100100L
    val regBuffAddr      = 0x100200L
    val dataBuffAddr     = 0x101000L

    val synapseCoreDest = Seq(0, 5, 1, 4)
    val neuronCoreId = 2

    var bpCnt = 0
    override def sendBp(bp: BasePacketSim, mAddr: Long) = {
      super.sendBp(bp, mAddr)
      bpCnt += 1 % (1<<16)
    }

    def waitBpDone() = {
      super.waitNocCmdDone(bpCnt)
    }

    def loadWeight(w:Array[Array[Int]]): Unit ={
      for (i <- w.indices) {
        val weightRaw = vToRawV(w(i), width = 16, 4)
        val addr = weightAddrBase + i * 1024
        mainMem.write(addr, weightRaw)
      }
    }

    def initial(): Unit ={
      import Synapse.SynapseCore._
      dut.clockDomain.waitSampling(100)
      for(dest <- synapseCoreDest){
        // set neuron core id and manager core id
        val bp0 = BasePacketSim.regWrite(
          dest = dest, src = 0, id = 0,
          addr = RegAddr.field1,
          data = 0x32
        )
        sendBp(bp0, mAddr = 0)
        flush(id = dest)
      }

      // wait synapseCore flushed
      dut.clockDomain.waitSampling(200)
      for(dest <- synapseCoreDest){
        waitFree(dest)
      }
    }

    def flush(id: Int): Unit = {
      import Synapse.SynapseCore._
      val bp = BasePacketSim.regWrite(
        dest = id, src = 0, id = 0,
        addr = RegAddr.field2,
        data = RegConfig.Field2.inferenceFlush
      )
      sendBp(bp, mAddr = 0)
    }

    def waitFree(id: Int): Unit = {
      def isFree(id: Int): Boolean = {
        import Synapse.SynapseCore._
        val bp = BasePacketSim.regRead(
          dest = id, src = 0, id = 0,
          addr = RegAddr.fieldR,
        )
        sendBp(bp, mAddr = regBuffAddr)
        waitBpDone()
        (mainMem.read(regBuffAddr)>>16)==1
      }
      while (!isFree(id)){
        dut.clockDomain.waitSampling(100)
      }
    }

    def sendPreSpike(dest:Int, nid:Int, preSpike: Array[Int]): Unit = {
      require(preSpike.length==1024)
      val spikeRaw = vToRawV(preSpike, 1, 64)
      mainMem.write(preSpikeBufAddr, spikeRaw)
      super.sendPreSpike(dest, nid, preSpikeBufAddr)
    }

    def setPostNidMap(nidMap: Seq[PostNidMapSim]): Unit = {
      super.setPostNidMap(nidMap, postAddr = postSpikeBufAddr)
    }

    def setNeuronCoreParam(neuronCoreConfig: Seq[NeuronCoreConfigSim]): Unit = {
      import Neuron.sim.NeuronRegAddr._
      val regs = NeuronCoreConfigSim.genRegField(neuronCoreConfig)
      Seq(NidField, MapField, Threshold0, Threshold1, LenField)
        .zip(Seq(regs.nidField, regs.mapField, regs.threshold0, regs.threshold1, regs.lenField))
        .foreach{case (addr, v) =>
          val bp = BasePacketSim.regWrite(
            dest = neuronCoreId, src = 0, id = 0,
            addr = addr, data = v
          )
          sendBp(bp, mAddr = 0)
        }
    }

    def setSynapseCoreParam(dest:Int, preLen:Int, postLen:Int, postNid:Int, refractory:Int, learning:Boolean): Unit ={
      import Synapse.SynapseCore._
      val bp0 = BasePacketSim.regWrite(
        dest = dest, src = 0, id = 0,
        addr = RegAddr.field0, data = RegConfig.field0(preLen, postLen, postNid)
      )
      val bp2 = BasePacketSim.regWrite(
        dest = dest, src = 0, id = 0,
        addr = RegAddr.field2, data = if(learning) RegConfig.Field2.learning else RegConfig.Field2.inferenceOnly
      )
      sendBp(bp0, mAddr = 0)
      sendBp(bp2, mAddr = 0)
    }

    def waitPostSpike(id: Int): Seq[Int] ={
      waitEpochDone(id)
      val addr = postSpikeBufAddr + id*8*8
      val spikeRaw = mainMem.read(addr, length = 8)
      rawToV(spikeRaw, width = 1, 64).map(_.abs)
    }
  }

  def initDut(dut:CacheSNN): CacheSnnAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(100000)
    CacheSnnAgent(dut)
  }

  test("single core inference test"){
    complied.doSim{ dut =>
      val agent = initDut(dut)
      val (preLen, postLen) = (1024, 512)
      val snn = new SnnModel(preLen, postLen)

      val synapseCoreId = 0
      val preSpikeNid = 0x0
      val postSpikeNid = preSpikeNid + 1024
      val threshold = 24000

      snn.weightRandomize()
      agent.loadWeight(snn.weight)

      val nidMapSim = Seq(NidMapSim(
        nidBase = preSpikeNid >> 10,
        len = 127,
        addrBase = (agent.weightAddrBase>>10).toInt,
        dest = synapseCoreId
      ))
      val postNidMapSim = Seq(PostNidMapSim(nidBase = postSpikeNid>>10, len = 7))
      val neuronCoreConfig = Seq(NeuronCoreConfigSim(
        nidBase = postSpikeNid,
        acc = 0, srcList = Seq(synapseCoreId), threshold = threshold , spikeLen = postLen / 64
      ))

      // config synapse core
      agent.initial()
      agent.setNidMap(nidMapSim)
      agent.setPostNidMap(postNidMapSim)
      agent.setNeuronCoreParam(neuronCoreConfig)
      agent.setSynapseCoreParam(synapseCoreId, preLen, postLen, postSpikeNid, refractory = 1, learning = false)
      agent.waitBpDone()
      val epoch = 1
      // TODO: fix neuron core to support multi epoch
      for(t <- 0 until epoch){
        val preSpike = SpikeFun.randomSpike(preLen)
        agent.sendPreSpike(dest = synapseCoreId, preSpikeNid, preSpike)
        val postSpike = agent.waitPostSpike(id = 0)
        snn.spikeForward(preSpike)
        val postSpikeTruth = snn.spikeFire(threshold)
        assert(postSpike == postSpikeTruth.toSeq)
      }
    }
  }
}