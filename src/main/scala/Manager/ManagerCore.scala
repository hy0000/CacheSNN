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
    val axi = master(Axi4(CacheSNN.axiMasterConfig))
    val axiLite = slave(AxiLite4(CacheSNN.axiLiteSlaveConfig))
  }

  val regArea = new Area {
    val busIf = AxiLite4BusInterface(io.axiLite, SizeMapping(0, 128 Byte), "")
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

    val nocSent = NocField3.field(Bool(), RW, "noc ready")
    val nocValid = NocField3.field(Bool(), RW, "noc valid")

    val NidField = busIf.newReg("nid field")
    val NidDestField = busIf.newReg("nid dest field")
    val AddrField0 = busIf.newReg("addr field 0")
    val AddrField1 = busIf.newReg("addr field 1")
    val AddrField2 = busIf.newReg("addr field 2")
    val AddrField3 = busIf.newReg("addr field 3")

    val addrField = Array(AddrField0, AddrField1, AddrField2, AddrField3)
    val nidMap = Vec(NidMap(), 4)
    for (i <- 0 until 4) {
      val valid     = NidField.field(Bool(), WO, s"valid $i").setName(s"valid_$i")
      val nidBase   = NidField.field(UInt(6 bits), WO,s"nid base $i")
      val len       = addrField(i).field(UInt(8 bits), WO, s"len $i")
      val addrBase  = addrField(i).field(UInt(22 bits), WO, s"addr base $i")
      val dest      = NidDestField.field(UInt(4 bits), WO, s"nid_dest_$i")
      nidMap(i).valid    := valid
      nidMap(i).nidBase  := nidBase
      nidMap(i).len      := len
      nidMap(i).addrBase := addrBase
      nidMap(i).dest     := dest
    }

    val PostAddrField = busIf.newReg("postSpike field")
    val postField = (0 until 4).map{i =>
      busIf.newReg(s"post field $i").setName(s"POSTFIELD$i")
    }
    val postSpikeAddrBase = PostAddrField.field(UInt(32 bits), WO, s"post spike addr base")
    val nidEpochDone = Bits(4 bits)
    val postNidMap = Vec(PostNidMap(), 4)
    for (i <- 0 until 4) {
      val nidBase = postField(i).field(UInt(6 bits), WO, s"post nidBase").setName(s"post_nid_base_$i")
      val valid   = postField(i).field(Bool(), WO, s"post nid valid").setName(s"post_nid_valid_$i")
      val len     = postField(i).field(UInt(4 bits), WO, s"post spike len")
      val nidDone = postField(i).field(Bool(), RW, s"nid done")
      when(nidEpochDone(i)) {
        nidDone := True
      }
      postNidMap(i).nidBase := nidBase
      postNidMap(i).valid   := valid
      postNidMap(i).len     := len
    }

    val PreSpikeField = busIf.newReg("preSpike field")
    val preSpikeDone = PreSpikeField.field(Bool(), RW, s"preSpike done")
    val preSpikeValid = PreSpikeField.field(Bool(), RW, s"preSpike valid")
    val preSpikeNidBase = PreSpikeField.field(UInt(6 bits), RW, s"preSpike nid base")
    val preSpikeAddrBase = PreSpikeField.field(UInt(22 bits), RW, s"pre spike addr base")

    val DoneCntField = busIf.newReg("preSpike field")
    val nocDoneCnt = DoneCntField.field(UInt(16 bits), RW, "noc done cnt")
    busIf.accept(HtmlGenerator("ManagerCoreReg", "ManagerCore"))
  }

  val aerManager = new AerManager
  val bpManager = new BpManager
  aerManager.io.aer <> interface.aer
  aerManager.io.preSpikeCmd.valid := regArea.preSpikeValid && !regArea.preSpikeDone
  aerManager.io.preSpikeCmd.nidBase := regArea.preSpikeNidBase
  aerManager.io.preSpikeCmd.addrBase := regArea.preSpikeAddrBase
  aerManager.io.preSpikeCmd.dest := regArea.dest
  aerManager.io.nidMap := regArea.nidMap
  aerManager.io.postAddrBase := regArea.postSpikeAddrBase
  aerManager.io.postNidMap := regArea.postNidMap
  regArea.nidEpochDone := aerManager.io.nidEpochDone.asBits
  when(aerManager.io.preSpikeCmd.fire){
    regArea.preSpikeDone := True
  }

  bpManager.io.readRsp << interface.readRsp
  bpManager.io.writeRsp << interface.writeRsp
  bpManager.io.cmd.valid := regArea.nocValid && !regArea.nocSent
  bpManager.io.cmd.payload := regArea.bpCmd
  when(bpManager.io.cmd.fire){
    regArea.nocSent := True
  }
  when(bpManager.io.cmdDone){
    regArea.nocDoneCnt := regArea.nocDoneCnt + 1
  }

  val axiCross = Axi4CrossbarFactory()
  axiCross.addSlave(io.axi, SizeMapping(0, BigInt(1)<<32))
  axiCross.addConnection(aerManager.io.axi, Seq(io.axi))
  axiCross.addConnection(bpManager.io.axi, Seq(io.axi))
  axiCross.build()

  interface.localSend << StreamArbiterFactory.fragmentLock.roundRobin.on(
    Seq(aerManager.io.localSend, bpManager.io.localSend)
  )
}
