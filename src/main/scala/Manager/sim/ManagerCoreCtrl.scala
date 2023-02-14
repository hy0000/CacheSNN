package Manager.sim

import CacheSNN.PacketType
import CacheSNN.sim._
import Util.sim.NumberTool._
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinal.lib.bus.amba4.axilite.sim._

case class NidMapSim(nidBase: Int,
                     len: Int,
                     addrBase: Int,
                     dest: Int)

case class PostNidMapSim(nidBase: Int,
                         len: Int)

class ManagerCoreCtrl(axiLite: AxiLite4, clockDomain: ClockDomain) extends {

  object ManagerReg {
    val NocField0 = 0x00
    val NocField1 = 0x04
    val NocField2 = 0x08
    val NocField3 = 0x0C

    val NidField = 0x10
    val NidDestField = 0x14
    val AddrField = Seq(0x18, 0x1C, 0x20, 0x24)

    val PostAddrField = 0x28
    val PostDoneField = 0x2C
    val PostField0 = 0x30
    val PostField1 = 0x34

    val PreSpikeField = 0x38

    val DoneCntField = 0x3C

    val nocField3Valid = 0x2
    val nocField3Done = 0x3
  }
  import ManagerReg._

  val ctrl = AxiLite4Driver(axiLite, clockDomain)

  def sendBp(bp:BasePacketSim, mAddr:Long): Unit ={
    import PacketType._
    val reg0 = (bp.field1<<8) | (bp.dest<<4) | (booleanToInt(bp.write)<<1) | booleanToInt(bp.packetType==R_CMD)
    ctrl.write(NocField0, reg0)
    ctrl.write(NocField1, bp.field2)
    ctrl.write(NocField2, mAddr)
    ctrl.write(NocField3, nocField3Valid)
    while (ctrl.read(NocField3) != nocField3Done) {
      clockDomain.waitSampling()
    }
  }

  def sendPreSpike(dest: Int, nid: Int, mAddr: Long): Unit ={
    val reg0 = dest<<4
    ctrl.write(NocField0, reg0)
    var reg1 = (mAddr>>10) << 8
    reg1 |= (nid>>10) << 2
    reg1 |= 0x2
    ctrl.write(PreSpikeField, reg1)
    while ((ctrl.read(PreSpikeField) & 0x3) != nocField3Done) {
      clockDomain.waitSampling()
    }
  }

  def waitEpochDone(id:Int): Unit ={
    while (((ctrl.read(PostDoneField)>>id) & 0x1) != 1) {
      clockDomain.waitSampling(200)
    }
  }

  def setNidMap(nidMap: Seq[NidMapSim]): Unit = {
    var reg0, reg1 = 0L
    val addrReg = Array.fill(4)(0L)
    for((m, i) <- nidMap.zipWithIndex){
      reg0 |= ((m.nidBase<<1) | 1) << (i*7)
      reg1 |= m.dest << (i*4)
      addrReg(i) = (m.addrBase<<8) | m.len
    }
    ctrl.write(NidField, reg0)
    ctrl.write(NidDestField, reg1)
    for(i <- nidMap.indices){
      ctrl.write(AddrField(i), addrReg(i))
    }
  }

  def setPostNidMap(nidMap: Seq[PostNidMapSim], postAddr: Long): Unit = {
    var reg0, reg1 = 0L
    for((m, i) <- nidMap.zipWithIndex){
      reg0 |= m.len << (i*8)
      reg1 |= (m.nidBase | (1<<6)) << (i*7)
    }
    ctrl.write(PostField0, reg0)
    ctrl.write(PostField1, reg1)
    ctrl.write(PostAddrField, postAddr)
  }

  def waitNocCmdDone(cmdNum:Int): Unit ={
    while (ctrl.read(DoneCntField).toInt != cmdNum) {
      clockDomain.waitSampling(10)
    }
  }
}
