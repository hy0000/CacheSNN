package Manager

import CacheSNN.CacheSnnTest.simConfig
import CacheSNN.PacketType
import CacheSNN.sim.BasePacketSim
import RingNoC.sim._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axi.sim.SparseMemory
import spinal.lib.sim._

import scala.collection.mutable
import scala.util.Random

case class AccessInfo(dest:Int, addr:Long, mAddr:Long, data:Seq[BigInt])

class BpManagerAgent(dut:BpManager) {
  val mainMem = AxiMemorySim(dut.io.axi, dut.clockDomain, AxiMemorySimConfig())
  mainMem.start()
  val nocMem = Array.fill(16)(SparseMemory())
  val (readRspDriver, readRspQueue) = StreamDriver.queue(dut.io.readRsp, dut.clockDomain)
  val (writeRspDriver, writeRspQueue) = StreamDriver.queue(dut.io.writeRsp, dut.clockDomain)
  val (bpCmdDriver, bpCmdQueue) = StreamDriver.queue(dut.io.cmd, dut.clockDomain)
  StreamReadyRandomizer(dut.io.localSend, dut.clockDomain)

  case class NocRsp(data:Seq[BigInt], id:Int, write:Boolean)
  val rspQueue = Array.fill(16)(mutable.Queue[NocRsp]())

  for(i <- 0 until 16){
    val dest = i
    fork {
      while (true){
        dut.clockDomain.waitSamplingWhere(rspQueue(dest).nonEmpty)
        dut.clockDomain.waitSampling(Random.nextInt(20))
        val rsp = rspQueue(dest).dequeue()
        if (rsp.write) {
          writeRspQueue.enqueue(_.id #= rsp.id)
        } else {
          for (j <- rsp.data.indices) {
            readRspQueue.enqueue { p =>
              p.id #= rsp.id
              p.data #= rsp.data(j)
              p.last #= (j == (rsp.data.length - 1))
            }
          }
        }
      }
    }
  }

  new NocInterfaceMonitor(dut.io.localSend, dut.clockDomain) {
    override def onPacket(p: NocPacket) = {
      val bp = BasePacketSim(p)
      val (addrBase, len, data) = if (bp.packetType == PacketType.R_CMD) {
        (bp.field1.toLong, 0, Seq(bp.field2))
      } else {
        (bp.field2.toLong, bp.field1.toInt, bp.data)
      }

      val mem = nocMem(bp.dest)
      val rsp = if (bp.write) {
        for (i <- 0 to len) {
          mem.writeBigInt(addrBase + i * 8, data(i), width = 8)
        }
        NocRsp(Seq(), bp.id, bp.write)
      } else {
        val data = (0 to len).map{i =>
          mem.readBigInt(addrBase + i * 8, length = 8)
        }
        NocRsp(data, bp.id, bp.write)
      }

      rspQueue(bp.dest).enqueue(rsp)
    }
  }

  def regWrite(dest:Int, addr:Long, data:BigInt): Unit = {
    bpCmdQueue.enqueue{cmd =>
      cmd.dest #= dest
      cmd.write #= true
      cmd.mAddr #= 0
      cmd.field1 #= addr
      cmd.field2 #= data
      cmd.useRegCmd #= true
    }
  }

  def regRead(dest:Int, addr:Long, mAddr:Long): Unit ={
    bpCmdQueue.enqueue { cmd =>
      cmd.dest #= dest
      cmd.write #= false
      cmd.mAddr #= mAddr
      cmd.field1 #= addr
      cmd.field2 #= 0
      cmd.useRegCmd #= true
    }
  }

  def dataAccess(dest:Int, addr:Long, len:Int, write: Boolean, mAddr:Long): Unit ={
    bpCmdQueue.enqueue { cmd =>
      cmd.useRegCmd #= false
      cmd.dest #= dest
      cmd.write #= write
      cmd.mAddr #= mAddr
      cmd.field1 #= len - 1
      cmd.field2 #= addr
    }
  }

  def waitFree(): Unit ={
    dut.clockDomain.waitSamplingWhere(bpCmdQueue.isEmpty)
    dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)
  }

  def assertMainMem(afs:Seq[AccessInfo]): Unit ={
    for(af <- afs){
      for((dTruth, i) <- af.data.zipWithIndex){
        val addr = af.mAddr + i * 8
        val d = mainMem.memory.readBigInt(addr, length = 8)
        assert(d == dTruth, s"${d.toString(16)} ${dTruth.toString(16)} at ${addr.toHexString}")
      }
    }
  }

  def writeData(afs:Seq[AccessInfo]): Unit ={
    for (af <- afs) {
      for ((d, i) <- af.data.zipWithIndex) {
        val addr = af.mAddr + i * 8
        mainMem.memory.writeBigInt(addr, data = d, width = 8)
      }
    }
  }
}

class BpManagerTest extends AnyFunSuite {
  val complied = simConfig.compile(new BpManager)

  def initDut(dut:BpManager): BpManagerAgent = {
    dut.clockDomain.forkStimulus(2)
    SimTimeout(1000000)
    val agent = new BpManagerAgent(dut)
    agent
  }

  test("reg wr test") {
    complied.doSim{ dut =>
      val (n, m) = (6, 20)
      val agent = initDut(dut)
      val mAddrBase = 0xFF0000L
      val accessInfo = Seq.tabulate(n, m){(dest, i) =>
        val addr = i * 8
        val data = (BigInt(dest) << 28) | addr
        val mAddr = mAddrBase + addr + dest * m * 8
        AccessInfo(dest = dest, addr = addr, mAddr = mAddr, data = Seq(data))
      }.flatten

      accessInfo.foreach{af =>
        agent.regWrite(af.dest, af.addr, af.data.head)
      }

      accessInfo.foreach { af =>
        agent.regRead(af.dest, af.addr, mAddr = af.mAddr)
      }

      agent.waitFree()
      agent.assertMainMem(accessInfo)
    }
  }

  test("data wr test") {
    complied.doSim { dut =>
      val (n, m) = (6, 30)
      val agent = initDut(dut)
      val mAddrBase0 = 0x0L
      val accessInfo = Seq.tabulate(n, m) { (dest, i) =>
        val len = Random.nextInt(256)
        val addr = i * 8 * 256 + dest * m * 8 * 256
        val data = (0 to len).map(j =>(BigInt(dest) << 60) + j)
        val mAddr = mAddrBase0 + addr + dest * m * 8 * 256
        AccessInfo(dest = dest, addr = addr, mAddr = mAddr, data = data)
      }.flatten

      agent.writeData(accessInfo)
      accessInfo.foreach { af =>
        agent.dataAccess(af.dest, af.addr, af.data.length, write = true, mAddr = af.mAddr)
      }

      val accessInfoMoved = accessInfo.map(a => a.copy(mAddr = a.mAddr + 0x20000000L))
      accessInfoMoved.foreach { af =>
        agent.dataAccess(af.dest, af.addr, af.data.length, write = false, mAddr = af.mAddr)
      }

      agent.waitFree()
      agent.assertMainMem(accessInfoMoved)
    }
  }

  test("random test") {
    complied.doSim { dut =>
      val (n, m) = (6, 14)
      val agent = initDut(dut)
      val rAddrBase = 0x0L
      val dAddrBase = 0x1000L
      val rmAddrBase = 0x0L
      val dmAddrBase0 = 0x10000000L
      val dmAddrBase1 = 0xA0000000L

      val regAccessInfo = Seq.tabulate(n, m) { (dest, i) =>
        val addr = i * 8 + rAddrBase
        val data = (BigInt(dest) << 28) | addr
        val mAddr = rmAddrBase + addr + dest * m * 8
        AccessInfo(dest = dest, addr = addr, mAddr = mAddr, data = Seq(data))
      }.flatten

      val dataAccessInfo = Seq.tabulate(n, m) { (dest, i) =>
        val len = Random.nextInt(128) + 1 // at least 2 to distinguish with regAccessInfo
        val addr = i * 8 * 256 + dest * m * 8 * 256 + dAddrBase
        val data = (0 to len).map(j => (BigInt(dest) << 60) + j)
        val mAddr = dmAddrBase0 + addr + dest * m * 8 * 256
        AccessInfo(dest = dest, addr = addr, mAddr = mAddr, data = data)
      }.flatten
      agent.writeData(dataAccessInfo)

      val accessInfo0 = Random.shuffle(regAccessInfo ++ dataAccessInfo)
      accessInfo0.foreach { af =>
        if(af.data.length==1){
          agent.regWrite(af.dest, af.addr, af.data.head)
        }else{
          agent.dataAccess(af.dest, af.addr, af.data.length, write = true, mAddr = af.mAddr)
        }
      }

      val dataAccessInfoMoved = dataAccessInfo.map(a => a.copy(mAddr = a.mAddr - dmAddrBase0 + dmAddrBase1))
      val accessInfo1 = Random.shuffle(regAccessInfo ++ dataAccessInfoMoved)
      accessInfo1.foreach { af =>
        if (af.data.length == 1) {
          agent.regRead(af.dest, af.addr, mAddr = af.mAddr)
        } else {
          agent.dataAccess(af.dest, af.addr, af.data.length, write = false, mAddr = af.mAddr)
        }
      }

      agent.waitFree()
      agent.assertMainMem(accessInfo1)
    }
  }
}
