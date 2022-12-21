package CacheSNN

import RingNoC.{NocInterface, NocInterfaceLocal}
import Util.{MemAccessBus, MemAccessBusConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.fsm._

/**
 * All cores in this system extends from NocCore class
 *
 * NocCore provides packet decode logic which can:
 *  - access registers/memory automatically
 *  - transfer raw packet to AER event
 *  - handle sent packets order by id
 */

object NocCore {
  def regBus = Apb3(Apb3Config(8, 32))

  def regRspPack(id:UInt, data:Bits): Bits = {
    PacketType.R_RSP.asBits.resize(3) ## B(0, 9 bits) ## id ## data
  }

  def dataRspHeadPack(id:UInt): Bits = {
    PacketType.D_RSP.asBits.resize(3) ## B(0, 9 bits) ## id.resize(4) ## B(0, 32 bits)
  }
}

abstract class NocCore extends Component {
  val noc = master(NocInterfaceLocal())

  val supportAsMemMaster:Boolean = false
  val supportAsMemSlave:Boolean = false

  val interface = new Bundle {
    val aer = master(new AerPacket) // decoded aer data
    val localSend = slave(new BasePacket) // for user logic to send packet
    val regBus  = ifGen(supportAsMemSlave)(master(NocCore.regBus)) // for reg ctrl
    val dataBus = ifGen(supportAsMemSlave)(master(MemAccessBus(MemAccessBusConfig(64, 32)))) // as memory slave for accessing
    val readRsp  = ifGen(supportAsMemMaster)(master(BaseReadRsp()))
    val writeRsp = ifGen(supportAsMemMaster)(master(BaseWriteRsp()))
  }

  val nocUnPacker = new NocUnPacker(supportAsMemMaster, supportAsMemSlave)
  val nocPacker = new NocPacker

  nocUnPacker.io.nocRec << noc.rec

  nocUnPacker.io.aer <> interface.aer
  if(supportAsMemSlave){
    nocUnPacker.io.regBus <> interface.regBus
    nocUnPacker.io.dataBus <> interface.dataBus
  }else{
    for(bt <- nocUnPacker.io.regBus.flatten ++ nocUnPacker.io.dataBus.flatten){
      if(bt.isInput){
        bt := bt.getZero
      }else{
        bt.allowPruning()
      }
    }
  }
  if(supportAsMemMaster){
    nocUnPacker.io.readRsp >> interface.readRsp
    nocUnPacker.io.writeRsp >> interface.writeRsp
  }else{
    nocUnPacker.io.readRsp.setBlocked()
    nocUnPacker.io.writeRsp.setBlocked()
  }

  nocPacker.io.rspRecId << nocUnPacker.io.rspRecId
  nocPacker.io.localSend <> interface.localSend

  noc.send << StreamArbiterFactory.fragmentLock.lowerFirst.on(
    Seq(nocUnPacker.io.rspSend, nocPacker.io.send)
  )
}

class NocUnPacker(supportMemMaster:Boolean, supportMemSlave:Boolean) extends Component {
  val io = new Bundle {
    val nocRec = slave(NocInterface())
    val regBus = master(NocCore.regBus)
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32)))
    val aer = master(new AerPacket)
    val rspRecId = master(Stream(UInt(4 bits)))
    val rspSend = master(NocInterface())
    val readRsp = master(BaseReadRsp())
    val writeRsp = master(BaseWriteRsp())
  }

  val head = RegNextWhen(io.nocRec.fragment, io.nocRec.firstFire)
  // base packet head
  val bph = new BasePacketHead
  bph.assignFromNocCustomField(head.custom)

  io.flattenForeach{bt =>
    if(bt.isOutput){
      bt := bt.getZero
    }
  }

  val apb3RegAccessFsm = new StateMachine {
    val setup = makeInstantEntry()
    val access = new State
    val rsp = new State

    this.isRunning{
      io.regBus.PWRITE := bph.write
      io.regBus.PADDR := bph.field1.asUInt
      io.regBus.PWDATA := bph.field2
    }

    val rData = RegNextWhen(io.regBus.PRDATA, io.regBus.PREADY)

    setup
      .whenIsActive{
        io.regBus.PSEL := 1
        goto(access)
      }
    access
      .whenIsActive{
        io.regBus.PSEL := 1
        io.regBus.PENABLE := True
        when(io.regBus.PREADY){
          goto(rsp)
        }
      }
    rsp
      .whenIsActive{
        io.rspSend.valid := True
        val custom = NocCore.regRspPack(bph.id, rData)
        io.rspSend.setHead(dest = head.src, custom)
        when(io.rspSend.ready){
          exitFsm()
        }
      }
  }

  val dataAccessFsm = new StateMachine {
    val cmd = makeInstantEntry()
    val rspHead, readRspData= new State

    cmd
      .whenIsActive{
        io.dataBus.cmd.valid := io.nocRec.ready || bph.write
        io.dataBus.cmd.write := bph.write
        io.dataBus.cmd.len := bph.field1.asUInt
        io.dataBus.cmd.address := bph.field2.asUInt.resized
        when(bph.write) {
          io.dataBus.cmd.data := io.nocRec.flit
          io.nocRec.ready := io.dataBus.cmd.ready
          when(io.nocRec.lastFire){
            goto(rspHead)
          }
        }otherwise{
          when(io.dataBus.cmd.ready){
            goto(rspHead)
          }
        }
      }
    rspHead
      .whenIsActive{
        io.rspSend.valid := True
        val custom = NocCore.dataRspHeadPack(bph.id)
        io.rspSend.setHead(dest = head.src, custom)
        io.rspSend.last := bph.write
        when(io.rspSend.ready) {
          when(bph.write){
            exitFsm()
          }otherwise{
            goto(readRspData)
          }
        }
      }
    readRspData
      .whenIsActive{
        io.rspSend.valid := io.dataBus.rsp.valid
        io.rspSend.flit := io.dataBus.rsp.fragment
        io.rspSend.last := io.dataBus.rsp.last
        io.dataBus.rsp.ready := io.rspSend.ready
        when(io.rspSend.lastFire){
          exitFsm()
        }
      }
  }

  val aerFsm = new StateMachine {
    val sendHead = makeInstantEntry()
    val sendBody = new State

    sendHead
      .whenIsActive{
        io.aer.head.valid := True
        io.aer.head.assignFromAerCustomField(bph.field2)
        when(io.aer.head.ready){
          goto(sendBody)
        }
      }
    sendBody
      .whenIsActive{
        io.aer.body.valid := io.nocRec.valid
        io.aer.body.fragment := io.nocRec.flit
        io.aer.body.last := io.nocRec.last
        io.nocRec.ready := io.aer.body.ready
      }
  }

  val rspRecFsm = new StateMachine {
    val sendId = makeInstantEntry()
    val readRsp, writeRsp = new State
    sendId
      .whenIsActive{
        io.rspRecId.valid := True
        io.rspRecId.payload := bph.id
        when(io.rspRecId.ready){
          when(bph.write){
            goto(writeRsp)
          }otherwise{
            goto(readRsp)
          }
        }
      }
    writeRsp
      .whenIsActive{
        io.writeRsp.valid := True
        io.writeRsp.id := bph.id
        when(io.writeRsp.ready){
          exitFsm()
        }
      }
    readRsp
      .whenIsActive{
        io.readRsp.valid := True
        io.readRsp.id := bph.id
        io.readRsp.data := io.nocRec.flit
        io.readRsp.last := io.nocRec.last
        when(io.readRsp.ready) {
          exitFsm()
        }
      }
  }

  val headErrorFsm = new StateMachine {
    val reportHeadToManager = makeInstantEntry()
    val consumeBody = new State

    reportHeadToManager
      .whenIsActive{
        io.rspSend.valid := True
        val custom = PacketType.ERROR.asBits.resize(3) ## bph.packetType.asBits.resize(45)
        io.rspSend.setHead(CacheSNN.managerId, custom)
        when(io.rspSend.ready){
          when(io.nocRec.isFirst){
            exitFsm()
          }otherwise{
            goto(consumeBody)
          }
        }
      }
    consumeBody
      .whenIsActive{
        io.nocRec.ready := True
        when(io.nocRec.last && io.nocRec.valid){
          exitFsm()
        }
      }
  }

  val fsm = new StateMachine {
    val waitAndBufferPacketHead = makeInstantEntry()
    val cmdReg = new StateFsm(apb3RegAccessFsm)
    val cmdData = new StateFsm(dataAccessFsm)
    val cmdAer = new StateFsm(aerFsm)
    val headError = new StateFsm(headErrorFsm)
    val rspRec = new StateFsm(rspRecFsm)

    waitAndBufferPacketHead
      .whenIsActive{
        io.nocRec.ready := True
        when(io.nocRec.valid){
          switch(bph.packetType.asBits){
            if(supportMemSlave){
              is(PacketType.R_CMD.asBits) { goto(cmdReg) }
              is(PacketType.D_CMD.asBits) { goto(cmdData) }
            }
            if (supportMemMaster) {
              is(PacketType.R_RSP.asBits) { goto(rspRec) }
              is(PacketType.D_RSP.asBits) { goto(rspRec) }
            }
            is(PacketType.AER.asBits) { goto(cmdAer) }
            default { goto(headError) }
          }
        }
      }
    Seq(cmdData, cmdReg, cmdAer, rspRec, headError).foreach{ state =>
      state.whenCompleted(goto(waitAndBufferPacketHead))
    }
  }
}

class NocPacker extends Component {
  val io = new Bundle {
    val send = master(NocInterface())
    val rspRecId = slave(Stream(UInt(4 bits)))
    val localSend = slave(new BasePacket)
  }
  val maxPending = 16
  stub()
}