package CacheSNN

import CacheSnnTest.simConfig
import sim.{Apb3MemSlave, BasePacket}
import RingNoC.sim.{NocInterfaceDriver, NocInterfaceMonitor}
import Util.sim.MemAccessBusMemSlave
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class NocCoreTest {

}

class NocUnPackerTest extends AnyFunSuite {
  val complied = simConfig.compile(
    new NocUnPacker(supportMemMaster = true, supportMemSlave = true)
  )

  case class NocUnPackerAgent(nocRecDriver: NocInterfaceDriver,
                              nocSendMonitor: NocInterfaceMonitor,
                              regBusSlave: Apb3MemSlave,
                              dataBusSlave: MemAccessBusMemSlave
                             )

  def initDut(dut:NocUnPacker): NocUnPackerAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(10000)
    dut.io.flattenForeach { bt =>
      if (bt.isInput) {
        bt.assignBigInt(0)
      }
    }
    val nocRecDriver = new NocInterfaceDriver(dut.io.nocRec, dut.clockDomain)
    val nocRspMonitor = new NocInterfaceMonitor(dut.io.rspSend, dut.clockDomain)
    val regBusSlave = Apb3MemSlave(dut.io.regBus, dut.clockDomain)
    val dataBusSlave = MemAccessBusMemSlave(dut.io.dataBus, dut.clockDomain, 3)

    NocUnPackerAgent(
      nocRecDriver,
      nocRspMonitor,
      regBusSlave,
      dataBusSlave
    )
  }

  test("error head test") {
    complied.doSim{dut =>
      val agent = initDut(dut)
      val errorPacket = BasePacket.errorPacket(1, 2)
      val errorPacketRsp = BasePacket.errorPacket(CacheSNN.managerId, 1).copy(field2 = PacketType.ERROR.position)
      agent.nocRecDriver.sendPacket(errorPacket)
      agent.nocSendMonitor.addPacket(errorPacketRsp)
      dut.clockDomain.waitSampling(100)
    }
  }

  test("R_CMD test"){

  }

  test("D_CMD test") {

  }

  test("R_RSP test"){

  }

  test("D_RSP test"){

  }

  test("AER test") {

  }
}