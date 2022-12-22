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

  val dest = 1
  val src = 2

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
      val errorPacket = BasePacket.errorPacket(dest, src)
      val errorPacketRsp = BasePacket.errorPacket(dest = CacheSNN.managerId, src = dest)
        .copy(field2 = PacketType.ERROR.position, data = Seq(), id = 0)

      agent.nocRecDriver.sendPacket(errorPacket)
      agent.nocSendMonitor.addPacket(errorPacketRsp)
      agent.nocSendMonitor.waiteComplete()
    }
  }

  test("R_CMD test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      val addr = 0x10
      val data = 0x12345678
      val id = 0xE
      val writePacket = BasePacket.regWrite(dest, src, id = id, addr = addr, data = data)
      val readPacket = BasePacket.regRead(dest, src, id = id, addr = addr)
      agent.nocRecDriver.sendPacket(writePacket, readPacket)

      val writeRsp = writePacket.toRspPacket()
      val readRspPacket = readPacket.toRspPacket(field2 = data)
      agent.nocSendMonitor.addPacket(writeRsp, readRspPacket)
      agent.nocSendMonitor.waiteComplete()
    }
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