package Manager

import CacheSNN.CacheSnnTest.simConfig
import CacheSNN.sim._
import CacheSNN.{AER, PacketType}
import Manager.sim._
import RingNoC.NocInterfaceLocal
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._

import scala.util.Random


case class ManagerCoreNocAgent(noc:NocInterfaceLocal, clockDomain: ClockDomain)
  extends BasePacketAgent(noc, clockDomain){

  val nocDataMem = Array.fill(16)(SparseMemory())
  val nocRegMem = Array.fill(16)(SparseMemory())

  override def onRegCMD(p: BasePacketSim) = {
    val mem = nocRegMem(p.dest)
    val addr = p.field1.toLong
    val rspBp = if (p.write) {
      mem.writeBigInt(addr, p.field2, width = 4)
      p.toRspPacket()
    } else {
      val readData = mem.readBigInt(addr, length = 4)
      p.toRspPacket(field2 = readData.toLong)
    }
    driver.sendPacket(rspBp)
  }

  override def onDataCMD(p: BasePacketSim) = {
    val mem = nocDataMem(p.dest)
    val len = p.field1.toInt
    val addrBase = p.field2.toLong
    val rspBp = if (p.write) {
      for (i <- 0 to len) {
        mem.writeBigInt(addrBase + i * 8, p.data(i), width = 8)
      }
      p.toRspPacket()
    } else {
      val readData = (0 to len).map { i =>
        mem.readBigInt(addrBase + i * 8, length = 8)
      }
      p.toRspPacket(field1 = len, data = readData)
    }
    driver.sendPacket(rspBp)
  }
}

case class ManagerCoreAgent(dut: ManagerCore)
  extends ManagerCoreCtrl(dut.io.axiLite, dut.clockDomain) {

  val mainMem = AxiMemSim(dut.io.axi, dut.clockDomain)
  val nocAgent = ManagerCoreNocAgent(dut.noc, dut.clockDomain)
}

class ManagerCoreTest extends AnyFunSuite {
  val complied = simConfig.compile(new ManagerCore)

  def initDut(dut:ManagerCore): ManagerCoreAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(100000)
    ManagerCoreAgent(dut)
  }

  test("reg wr test"){
    complied.doSim{ dut =>
      val agent = initDut(dut)
      val data = BigInt(32, Random)
      val mAddr = 0x10L
      val writePacket = BasePacketSim(
        dest = Random.nextInt(16),
        src = 3,
        packetType = PacketType.R_CMD,
        write = true,
        id = 0,
        field1 = 0x1C,
        field2 = data
      )
      val readPacket = writePacket.copy(
        write = false,
        field2 = 0
      )
      agent.sendBp(writePacket, mAddr)
      agent.sendBp(readPacket, mAddr)
      agent.waitNocCmdDone(2)
      val memData = agent.mainMem.read(mAddr)
      assert(memData==data, s"${memData.toString(16)} ${data.toString(16)}")
    }
  }

  test("data wr test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      val data = Seq.fill(2)(BigInt(32, Random))
      val nocAddr = 0x0L
      val mAddrSrc = 0xF0L
      val mAddrDest = 0x1000L

      agent.mainMem.write(mAddrSrc, data)

      val writePacket = BasePacketSim(
        dest = Random.nextInt(16),
        src = 3,
        packetType = PacketType.D_CMD,
        write = true,
        id = 0,
        field1 = data.length - 1,
        field2 = nocAddr,
        data = data
      )
      val readPacket = writePacket.copy(
        write = false,
        data = Seq()
      )

      agent.sendBp(writePacket, mAddrSrc)
      agent.sendBp(readPacket, mAddrDest)
      agent.waitNocCmdDone(2)
      agent.mainMem.assertData(mAddrDest, data)
    }
  }

  test("snn epoch test"){
    // only test no stuck
    complied.doSim { dut =>
      val agent = initDut(dut)
      val postSpikeAddrBase = 0xE000
      val weightAddrBase = 0x0
      val preSpikeAddrBase = 0x1000
      val preSpikeNid = 0x800
      val nPreSpike = 1024
      val postSpikeNid = preSpikeNid + nPreSpike
      val nPostSpike = 512
      val dest = 0
      val thisId = 3
      agent.nocAgent.dest = dest
      agent.nocAgent.src = thisId

      val preSpikeRaw = SpikeFun.randomPreSpikeRaw(nPreSpike)
      val postSpike = SpikeFun.randomSpike(nPostSpike)

      val nidMapSim = Seq(NidMapSim(
        nidBase = preSpikeNid >> 10,
        len = 127,
        addrBase = weightAddrBase,
        dest = dest
      ))

      val postNidMapSim = Seq(PostNidMapSim(nidBase = postSpikeNid>>10, len = 7))

      // generate weight
      val m = Random.nextInt(60)
      val nidOffsets = Random.shuffle((0 until 512).toList).take(m)
      val weightWritePacket = nidOffsets.map { nidOff =>
        val length = nidMapSim.head.len + 1
        val data = Seq.fill(length)(BigInt(64, Random))
        AerPacketSim(
          dest = thisId, src = dest, id = 0,
          eventType = AER.TYPE.W_WRITE,
          nid = (nidMapSim.head.nidBase << 10) + nidOff,
          data = data
        )
      }

      val weightFetchPacket = weightWritePacket.map { p =>
        p.copy(dest = p.src, src = p.dest, eventType = AER.TYPE.W_FETCH, data = Seq())
      }

      agent.setNidMap(nidMapSim)
      agent.setPostNidMap(postNidMapSim, postSpikeAddrBase)
      agent.mainMem.write(preSpikeAddrBase, preSpikeRaw)
      agent.sendPreSpike(0, preSpikeNid, preSpikeAddrBase)
      agent.nocAgent.sendAer(weightWritePacket)
      agent.nocAgent.sendAer(weightFetchPacket)
      agent.nocAgent.sendSpike(postSpike, postSpikeNid, AER.TYPE.POST_SPIKE)
      agent.waitEpochDone(id = 0)
    }
  }
}
