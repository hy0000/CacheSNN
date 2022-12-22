package CacheSNN

import CacheSnnTest.simConfig
import sim._
import RingNoC.sim.{NocInterfaceDriver, NocInterfaceMonitor}
import Util.sim.MemAccessBusMemSlave
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.StreamReadyRandomizer

import scala.util.Random

class NocCoreTest {

}

class NocUnPackerTest extends AnyFunSuite {
  val supportMemMaster, supportMemSlave = Random.nextBoolean()
  val complied = simConfig.compile(
    new NocUnPacker(supportMemMaster, supportMemSlave)
  )

  val dest = 1
  val src = 2

  case class NocUnPackerAgent(nocRecDriver: NocInterfaceDriver,
                              nocSendMonitor: NocInterfaceMonitor,
                              regBusSlave: Apb3MemSlave,
                              dataBusSlave: MemAccessBusMemSlave,
                              aerMonitor: AerMonitor
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
    val aerMonitor = AerMonitor(dut.io.aer, dut.clockDomain)

    Seq(dut.io.aer.head, dut.io.aer.body, dut.io.readRsp, dut.io.writeRsp)
      .foreach(s => StreamReadyRandomizer(s, dut.clockDomain))

    NocUnPackerAgent(
      nocRecDriver,
      nocRspMonitor,
      regBusSlave,
      dataBusSlave,
      aerMonitor
    )
  }

  test("error head test") {
    complied.doSim{dut =>
      val agent = initDut(dut)
      val errorPacket = BasePacketSim.errorPacket(dest, src)
      val errorPacketRsp = BasePacketSim.errorPacket(dest = CacheSNN.managerId, src = dest)
        .copy(field2 = PacketType.ERROR.position, data = Seq(), id = 0)

      agent.nocRecDriver.sendPacket(errorPacket)
      agent.nocSendMonitor.addPacket(errorPacketRsp)
      agent.nocSendMonitor.waiteComplete()
    }
  }

  test("R_CMD test"){
    if(supportMemSlave){
      complied.doSim { dut =>
        val agent = initDut(dut)
        val addr = 0x10
        val data = 0x12345678
        val id = 0xE
        val writePacket = BasePacketSim.regWrite(dest, src, id = id, addr = addr, data = data)
        val readPacket = BasePacketSim.regRead(dest, src, id = id, addr = addr)
        agent.nocRecDriver.sendPacket(writePacket, readPacket)

        val writeRspPacket = writePacket.toRspPacket()
        val readRspPacket = readPacket.toRspPacket(field2 = data)
        agent.nocSendMonitor.addPacket(writeRspPacket, readRspPacket)
        agent.nocSendMonitor.waiteComplete()
      }
    }
  }

  test("D_CMD test") {
    if(supportMemSlave){
      complied.doSim { dut =>
        val agent = initDut(dut)
        val addr = 0xF00
        val id = 0xA
        val len = Random.nextInt(255) + 1
        val data = (0 until len).map(BigInt(_))
        val writePacket = BasePacketSim.dataWrite(dest, src, id = id, addr = addr, data = data)
        val readPacket = BasePacketSim.dataRead(dest, src, id = id, addr = addr, len = data.length - 1)
        agent.nocRecDriver.sendPacket(writePacket, readPacket)

        val writeRspPacket = writePacket.toRspPacket()
        val readRspPacket = readPacket.toRspPacket(data = data)
        agent.nocSendMonitor.addPacket(writeRspPacket, readRspPacket)
        agent.nocSendMonitor.waiteComplete()
      }
    }
  }

  test("RSP test"){
    if(supportMemMaster){
      complied.doSim { dut =>
        val agent = initDut(dut)
        // construct 4 types of rsp packets
        val regWriteRspPacket = BasePacketSim(dest = src, src = dest, packetType = PacketType.R_RSP, write = true, id = 1, field1 = 0, field2 = 0)
        val regReadRspPacket = regWriteRspPacket.copy(id = 2, write = false, field2 = 0x666)
        val dataWriteRspPacket = regWriteRspPacket.copy(packetType = PacketType.D_RSP, id = 3)
        val dataReadRspPacket = dataWriteRspPacket.copy(id = 4, write = false, field1 = 2, data = Seq(6, 6, 6))

        val packets = Seq(
          regWriteRspPacket,
          regReadRspPacket,
          dataWriteRspPacket,
          dataReadRspPacket
        )

        agent.nocRecDriver.sendPacket(packets: _*)

        // reg write rsp
        dut.clockDomain.waitSamplingWhere(dut.io.writeRsp.valid.toBoolean && dut.io.writeRsp.ready.toBoolean)
        assert(dut.io.writeRsp.id.toInt == 1)
        // reg read rsp
        dut.clockDomain.waitSamplingWhere(dut.io.readRsp.valid.toBoolean && dut.io.readRsp.ready.toBoolean)
        assert(dut.io.readRsp.data.toBigInt == 0x666)
        assert(dut.io.readRsp.id.toInt == 2)
        // data write rsp
        dut.clockDomain.waitSamplingWhere(dut.io.writeRsp.valid.toBoolean && dut.io.writeRsp.ready.toBoolean)
        assert(dut.io.writeRsp.id.toInt == 3)
        // data read rsp
        for (data <- dataReadRspPacket.data) {
          dut.clockDomain.waitSamplingWhere(dut.io.readRsp.valid.toBoolean && dut.io.readRsp.ready.toBoolean)
          assert(dut.io.readRsp.data.toBigInt == data)
          assert(dut.io.readRsp.id.toInt == 4)
        }
      }
    }
  }

  test("AER test") {
    complied.doSim { dut =>
      val agent = initDut(dut)
      def randData: Seq[BigInt] = Seq.fill(16)(Random.nextInt(65536).toBigInt)
      val preSpikePacket = AerPacketSim(dest, src, id=1, AER.TYPE.PRE_SPIKE, nid = 0x666, randData)
      val postSpikePacket = AerPacketSim(dest, src, id=2, AER.TYPE.POST_SPIKE, nid = 0x777, randData)
      val currentPacket = AerPacketSim(dest, src, id=3, AER.TYPE.CURRENT, nid = 0x888, randData)
      val weightFetchPacket = AerPacketSim(dest, src, id=3, AER.TYPE.W_FETCH, nid = 0x999, randData)
      val weightWritePacket = AerPacketSim(dest, src, id=3, AER.TYPE.W_WRITE, nid = 0x1010, randData)

      val packets = Seq(
        preSpikePacket,
        postSpikePacket,
        currentPacket,
        weightFetchPacket,
        weightWritePacket
      )

      agent.nocRecDriver.sendPacket(packets:_*)
      agent.aerMonitor.addPacket(packets:_*)
      agent.aerMonitor.waiteComplete()
    }
  }
}