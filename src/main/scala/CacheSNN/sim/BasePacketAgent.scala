package CacheSNN.sim

import RingNoC.sim._
import spinal.lib._
import CacheSNN.{AER, PacketType}
import RingNoC.NocInterfaceLocal
import Util.sim.NumberTool
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif.AccessType

import scala.collection.mutable
import scala.util.Random

class BasePacketAgent(noc:NocInterfaceLocal, clockDomain: ClockDomain) {
  val driver = new NocInterfaceDriver(noc.rec, clockDomain)
  var dest, src = 0

  val regReadRsp = mutable.Queue[Long]()
  val regWriteRsp = mutable.Queue[Int]() // id
  val dataReadRsp = mutable.Queue[Seq[BigInt]]()
  val dataWriteRsp = mutable.Queue[Int]() // id
  val aerPacketOut = mutable.Queue[AerPacketSim]()

  def sendAer(aerPacketSim: AerPacketSim): Unit = {
    driver.sendPacket(aerPacketSim)
  }

  def regRead(addr: Int): Long = {
    val bp = BasePacketSim.regRead(dest = dest, src = src, id = 0, addr = addr)
    driver.sendPacket(bp)
    clockDomain.waitSamplingWhere(regReadRsp.nonEmpty)
    regReadRsp.dequeue()
  }

  def regWrite(addr: Int, data: Long): Unit = {
    val bp = BasePacketSim.regWrite(dest = dest, src = src, id = 0, addr = addr, data = data)
    driver.sendPacket(bp)
    clockDomain.waitSamplingWhere(regWriteRsp.nonEmpty)
    regWriteRsp.dequeue()
  }

  def dataRead(addr: Long, len: Int): Seq[BigInt] = {
    val bp = BasePacketSim.dataRead(dest = dest, src = src, id = 0, addr = addr, len = len)
    driver.sendPacket(bp)
    clockDomain.waitSamplingWhere(dataReadRsp.nonEmpty)
    dataReadRsp.dequeue()
  }

  def dataWrite(addr: Long, data: Seq[BigInt]): Unit = {
    val bp = BasePacketSim.dataWrite(dest = dest, src = src, id = 0, addr = addr, data = data)
    driver.sendPacket(bp)
    clockDomain.waitSamplingWhere(dataWriteRsp.nonEmpty)
    dataWriteRsp.dequeue()
  }

  def sendSpike(maskSpike: Array[Int], nidBase: Int, eventType: AER.TYPE.E): Unit = {
    val data = NumberTool.vToRawV(maskSpike, 1, 64)
    val p = AerPacketSim(dest, src, 0, eventType, nid = nidBase, data = data)
    driver.sendPacket(p)
  }

  def onRegRSP(p: BasePacketSim): Unit = {
    if (p.write) {
      regWriteRsp.enqueue(p.id)
    } else {
      regReadRsp.enqueue(p.field2.toLong)
    }
  }

  def onDataRSP(p: BasePacketSim): Unit = {
    if (p.write) {
      dataWriteRsp.enqueue(p.id)
    } else {
      dataReadRsp.enqueue(p.data)
    }
  }

  def onRegCMD(p: BasePacketSim): Unit = {}
  def onDataCMD(p: BasePacketSim): Unit = {}
  def onAER(p: BasePacketSim): Unit = {}
  def onERROR(p: BasePacketSim): Unit = {}

  def addressMappingTest(sizeMapping: SizeMapping): Unit = {
    // write data
    //val data = Array.fill((sizeMapping.size / 8).toInt)(BigInt(64, Random))
    val data = (0 until (sizeMapping.size  / 8).toInt).map(i => BigInt(i)).toArray
    var i = 0
    // write data
    while (i < data.length) {
      var iNext = i + Random.nextInt(256) + 1
      if (iNext > data.length) {
        iNext = data.length
      }
      val dataSlice = data.slice(i, iNext)
      val address = sizeMapping.base.toLong + i * 8
      dataWrite(address, dataSlice)
      i = iNext
    }
    // read data
    i = 0
    while (i < data.length) {
      //var iNext = i + Random.nextInt(256) + 1
      var iNext = i + 256
      if (iNext > data.length) {
        iNext = data.length
      }
      val dataSlice = data.slice(i, iNext)
      val address = sizeMapping.base.toLong + i * 8
      val rspData = dataRead(address, len = iNext - i - 1)
      assert(dataSlice.toSeq == rspData)
      i = iNext
    }
  }

  class Monitor extends NocInterfaceMonitor(noc.send, clockDomain){
    override def onPacket(p: NocPacket): Unit = {
      val bp = BasePacketSim(p)
      bp.packetType match {
        case PacketType.R_CMD => onRegCMD(bp)
        case PacketType.R_RSP => onRegRSP(bp)
        case PacketType.D_CMD => onDataCMD(bp)
        case PacketType.D_RSP => onDataRSP(bp)
        case PacketType.AER => onAER(bp)
        case PacketType.ERROR => onERROR(bp)
      }
    }
  }

  val monitor = new Monitor
}