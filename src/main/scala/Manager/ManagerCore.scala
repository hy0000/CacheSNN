package Manager

import CacheSNN._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif.AccessType._
import spinal.lib.bus.regif.{AxiLite4BusInterface, HtmlGenerator}

class ManagerCore extends NocCore {
  override val supportAsMemSlave = false

  val io = new Bundle {
    val externalMemory = master(Axi4(CacheSNN.axiMasterConfig))
    val ctrl = slave(AxiLite4(CacheSNN.axiLiteSlaveConfig))
  }

  val regArea = new Area {
    val busIf = AxiLite4BusInterface(io.ctrl, SizeMapping(0, 256 Byte), "")
    val NocField0 = busIf.newReg("noc field 0")
    val NocField1 = busIf.newReg("noc field 1")
    val NocField2 = busIf.newReg("noc field 2")
    val NocField3 = busIf.newReg("noc field 3")

    val useRegCmd = NocField0.field(Bool(), WO, "use reg cmd")
    val write = NocField0.field(Bool(), WO, "write")
    NocField0.reserved(2 bits)
    val dest = NocField0.field(UInt(4 bits), WO, "dest")
    val field1 = NocField0.field(UInt(8 bits), WO, "field1")
    val field2 = NocField1.field(Bits(32 bits), WO, "field2")
    val mAddr = NocField2.field(UInt(32 bits), WO, "mAddr")

    val bpCmd = BpCmd()
    bpCmd.useRegCmd := useRegCmd
    bpCmd.write     := write
    bpCmd.dest      := dest
    bpCmd.field1    := field1
    bpCmd.field2    := field2
    bpCmd.mAddr     := mAddr

    val nocDone = NocField3.field(Bool(), RW, "noc ready")
    val nocValid = NocField3.field(Bool(), WO, "noc valid")

    /*
      nid[15:10]: nidBase
      nid[9:0]: nidOffset
      addr = (addrBase + nidOffset) ## 10'B0
     */
    case class NidMap() extends Bundle {
      val free = Bool()
      val valid = Bool()
      val nidBase = UInt(6 bits)
      val len = UInt(8 bits)
      val addrBase = UInt(22 bits)
    }
    val NidField = busIf.newReg("nid field")
    val AddrField0 = busIf.newReg("addr field 0")
    val AddrField1 = busIf.newReg("addr field 1")
    val AddrField2 = busIf.newReg("addr field 2")
    val AddrField3 = busIf.newReg("addr field 3")

    val addrField = Array(AddrField0, AddrField1, AddrField2, AddrField3)
    val nidMap = Vec(NidMap(), 4)
    for (i <- 0 until 4) {
      nidMap(i).free := NidField.field(Bool(), RW, s"free $i").setName(s"free_$i")
      nidMap(i).valid := NidField.field(Bool(), WO, s"valid $i").setName(s"valid_$i")
      nidMap(i).nidBase := NidField.field(UInt(6 bits), WO,s"nid base $i").setName(s"nid_base_$i")
      nidMap(i).len := addrField(i).field(UInt(8 bits), WO, s"len $i").setName(s"len_$i")
      nidMap(i).addrBase := addrField(i).field(UInt(22 bits), WO, s"addr base $i").setName(s"addr_base_$i")
    }

    val PreSpikeField = busIf.newReg("preSpike field")
    val preSpikeDone = PreSpikeField.field(Bool(), RW, s"preSpike done")
    val preSpikeValid = PreSpikeField.field(Bool(), WO, s"preSpike valid")
    val preSpikeNidBase = PreSpikeField.field(UInt(6 bits), WO, s"preSpike nid base")
    val preSpikeAddrBase = PreSpikeField.field(UInt(22 bits), WO, s"pre spike addr base")

    busIf.accept(HtmlGenerator("ManagerCoreReg", "ManagerCore"))
  }

  val aerManager = new AerManager
  val bpManager = new BpManager
  aerManager.io.aer <> interface.aer
  aerManager.io.cmd.valid := regArea.preSpikeValid && !regArea.preSpikeDone
  aerManager.io.cmd.nidBase := regArea.preSpikeNidBase
  aerManager.io.cmd.addrBase := regArea.preSpikeAddrBase
  when(aerManager.io.cmd.fire){
    regArea.preSpikeDone := True
  }

  bpManager.io.readRsp << interface.readRsp
  bpManager.io.writeRsp << interface.writeRsp
  bpManager.io.cmd.valid := regArea.nocValid && !regArea.nocDone
  bpManager.io.cmd.payload := regArea.bpCmd
  when(bpManager.io.cmd.fire){
    regArea.nocDone := True
  }

  val axiCross = Axi4CrossbarFactory()
  axiCross.addSlave(io.externalMemory, SizeMapping(0, BigInt(1)<<32))
  axiCross.addConnection(aerManager.io.axi, Seq(io.externalMemory))
  axiCross.addConnection(bpManager.io.axi, Seq(io.externalMemory))
  axiCross.build()

  interface.localSend << StreamArbiterFactory.fragmentLock.roundRobin.on(
    Seq(aerManager.io.localSend, bpManager.io.localSend)
  )
}
