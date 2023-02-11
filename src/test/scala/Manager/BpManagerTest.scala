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
      val (addrBase, len, data, width) = if (bp.packetType == PacketType.R_CMD) {
        (bp.field1.toLong, 0, Seq(bp.field2), 4)
      } else {
        (bp.field2.toLong, bp.data.length - 1, bp.data, 8)
      }

      val mem = nocMem(bp.dest)
      val rsp = if (bp.write) {
        for (i <- 0 to len) {
          mem.writeBigInt(addrBase + i * 4, data(i), width)
        }
        NocRsp(Seq(), bp.id, bp.write)
      } else {
        val data = (0 to len).map{i =>
          mem.readBigInt(addrBase + i * 4, length = width)
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

  def assertMainMem(addrBase:Long, data:Seq[BigInt]): Unit ={
    for((dTruth, i) <- data.zipWithIndex){
      val addr = addrBase + i*8
      val d = mainMem.memory.readBigInt(addr, length = 8)
      assert(d==dTruth, s"${d.toString(16)} ${dTruth.toString(16)} at ${addr.toHexString}")
    }
  }
}

class BpManagerTest extends AnyFunSuite {
  val complied = simConfig.compile(new BpManager)

  def initDut(dut:BpManager): BpManagerAgent = {
    dut.clockDomain.forkStimulus(2)
    SimTimeout(100000)
    val agent = new BpManagerAgent(dut)
    agent
  }

  test("reg wr test") {
    complied.doSim{ dut =>
      val (n, m) = (6, 20)
      val agent = initDut(dut)
      val mAddrBase = 0xFF0000L
      val dataQueue = mutable.Queue[BigInt]()
      for(dest <- 0 until n){
        for(i <- 0 until m){
          val addr = i * 4
          val data = (BigInt(dest)<<28) | addr
          dataQueue.enqueue(data)
          agent.regWrite(dest, addr, data)
        }
      }
      for (dest <- 0 until n) {
        for (i <- 0 until m) {
          val addr = i * 4
          val mAddr = mAddrBase + addr*2 + dest * m * 8
          agent.regRead(dest, addr, mAddr)
        }
      }
      agent.waitFree()
      agent.assertMainMem(mAddrBase, dataQueue)
    }
  }

  test("data wr test") {

  }

  test("random test") {

  }
}
