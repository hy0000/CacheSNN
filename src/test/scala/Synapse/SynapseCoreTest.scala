package Synapse

import CacheSNN.CacheSnnTest._
import CacheSNN.sim._
import Synapse.SynapseCore.AddrMapping
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class SynapseCoreTest extends AnyFunSuite {
  val complied = simConfig.compile(new SynapseCore)

  def initDut(dut:SynapseCore): BasePacketAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(1000000)
    new BasePacketAgent(dut.noc, dut.clockDomain)
  }

  test("address mapping test"){
    complied.doSim{ dut =>
      val agent = initDut(dut)
      agent.addressMappingTest(AddrMapping.postSpike)
      agent.addressMappingTest(AddrMapping.preSpike)
    }
  }
}
