package CacheSNN

import RingNoC.{NocInterface, NocInterfaceLocal}
import Util.Misc.clearIO
import Util.{MemAccessBus, MemAccessBusConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.fsm._

/**
 * All cores in CacheSnn NoC extends from NocCore class
 *
 * NocCore provides packet decode logic which can:
 *  - access registers/memory automatically
 *  - transfer raw packet to AER event
 *  - handle sent packets order by id
 */

object NocCore {
  def regBus = Apb3(Apb3Config(8, 32))

  def regRspPack(id:UInt, data:Bits, write:Bool): Bits = {
    PacketType.R_RSP.asBits.resize(3) ## write ## B(0, 8 bits) ## id ## data
  }

  def dataRspHeadPack(id:UInt, write:Bool): Bits = {
    PacketType.D_RSP.asBits.resize(3) ## write ## B(0, 8 bits) ## id.resize(4) ## B(0, 32 bits)
  }
}

abstract class NocCore extends Component {
  val noc = master(NocInterfaceLocal())

  val supportAsMemMaster:Boolean = false
  val supportAsMemSlave:Boolean = true

  val interface = new Bundle {
    val aer = new AerPacket // decoded aer data
    val localSend = NocInterface() // for user logic to send packet
    val regBus  = ifGen(supportAsMemSlave)(NocCore.regBus) // for reg ctrl
    val dataBus = ifGen(supportAsMemSlave)(MemAccessBus(MemAccessBusConfig(64, 32))) // as memory slave for accessing
    val readRsp  = ifGen(supportAsMemMaster)(BaseReadRsp())
    val writeRsp = ifGen(supportAsMemMaster)(BaseWriteRsp())
  }

  val nocUnPacker = new NocUnPacker(supportAsMemMaster, supportAsMemSlave)

  nocUnPacker.io.nocRec << noc.rec
  nocUnPacker.io.aer <> interface.aer

  if(supportAsMemSlave){
    nocUnPacker.io.regBus <> interface.regBus
    nocUnPacker.io.dataBus <> interface.dataBus
  }else{
    nocUnPacker.io.regBus.PREADY := False
    nocUnPacker.io.regBus.PRDATA := 0
    nocUnPacker.io.dataBus.cmd.setBlocked()
    nocUnPacker.io.dataBus.rsp.setIdle()
  }

  if(supportAsMemMaster){
    nocUnPacker.io.readRsp >> interface.readRsp
    nocUnPacker.io.writeRsp >> interface.writeRsp
  }else{
    nocUnPacker.io.readRsp.setBlocked()
    nocUnPacker.io.writeRsp.setBlocked()
  }

  noc.send << StreamArbiterFactory.fragmentLock.lowerFirst.on(
    Seq(nocUnPacker.io.rspSend.stage(), interface.localSend)
  )

  def idleInterface(): Unit = {
    interface.flattenForeach{bt =>
      if(!bt.hasDataAssignment){
        bt := bt.getZero
      }
    }
  }
}

class NocUnPacker(supportMemMaster:Boolean, supportMemSlave:Boolean) extends Component {
  val io = new Bundle {
    val nocRec = slave(NocInterface())
    val regBus = master(NocCore.regBus)
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32)))
    val aer = master(new AerPacket)
    val rspSend = master(NocInterface())
    val readRsp = master(BaseReadRsp())
    val writeRsp = master(BaseWriteRsp())
  }
  clearIO(io)

  val head = RegNextWhen(io.nocRec.fragment, io.nocRec.firstFire)
  // base packet head
  val bph, bphReg = new BasePacketHead
  bph.assignFromNocCustomField(io.nocRec.custom)
  bphReg.assignFromNocCustomField(head.custom)

  val apb3RegAccessFsm = new StateMachine {
    val setup = new State with EntryPoint
    val access = new State
    val rsp = new State

    when(isRunning){
      io.regBus.PWRITE := bphReg.write
      io.regBus.PADDR := bphReg.field1.asUInt
      io.regBus.PWDATA := bphReg.field2
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
        io.rspSend.last := True
        val data = bphReg.write ? rData.getZero | rData
        val custom = NocCore.regRspPack(bphReg.id, data, bphReg.write)
        io.rspSend.setHead(dest = head.src, src = head.dest, custom)
        when(io.rspSend.ready){
          exitFsm()
        }
      }
  }

  val dataAccessFsm = new StateMachine {
    val cmd = new State with EntryPoint
    val rspHead, readRspData= new State

    cmd
      .whenIsActive{
        io.dataBus.cmd.valid := True
        io.dataBus.cmd.write := bphReg.write
        io.dataBus.cmd.len := bphReg.field1.asUInt
        io.dataBus.cmd.address := bphReg.field2.asUInt.resized
        when(bphReg.write) {
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
        val custom = NocCore.dataRspHeadPack(bphReg.id, bphReg.write)
        io.rspSend.setHead(dest = head.src, src = head.dest, custom)
        io.rspSend.last := bphReg.write
        when(io.rspSend.ready) {
          when(bphReg.write){
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
    val sendHead = new State with EntryPoint
    val sendBody = new State

    sendHead
      .whenIsActive{
        io.aer.head.valid := True
        io.aer.head.assignFromAerCustomField(bphReg.field2)
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
        when(io.aer.body.lastFire){
          exitFsm()
        }
      }
  }

  val rspRecFsm = new StateMachine {
    val start = new State with EntryPoint
    val regReadRsp, dataReadRsp, writeRsp = new State

    start
      .whenIsActive{
        when(bphReg.write){
          goto(writeRsp)
        }elsewhen(bphReg.packetType===PacketType.R_RSP){
          goto(regReadRsp)
        }otherwise{
          goto(dataReadRsp)
        }
      }
    writeRsp
      .whenIsActive{
        io.writeRsp.valid := True
        io.writeRsp.id := bphReg.id
        when(io.writeRsp.ready){
          exitFsm()
        }
      }
    dataReadRsp
      .whenIsActive{
        io.readRsp.valid := True
        io.readRsp.id := bphReg.id
        io.readRsp.data := io.nocRec.flit
        io.readRsp.last := io.nocRec.last
        when(io.readRsp.lastFire) {
          exitFsm()
        }
      }
    regReadRsp
      .whenIsActive{
        io.readRsp.valid := True
        io.readRsp.id := bphReg.id
        io.readRsp.data := bphReg.field2.resized
        io.readRsp.last := True
        when(io.readRsp.ready) {
          exitFsm()
        }
      }
  }

  // send error packet head to manager
  val headErrorFsm = new StateMachine {
    val reportHeadToManager = new State with EntryPoint
    val consumeBody = new State

    reportHeadToManager
      .whenIsActive{
        io.rspSend.valid := True
        val custom = PacketType.ERROR.asBits.resize(3) ## bphReg.packetType.asBits.resize(45)
        io.rspSend.setHead(CacheSNN.managerId, src = head.dest, custom)
        io.rspSend.last := True
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

    Seq(cmdData, cmdReg, cmdAer, rspRec, headError).foreach { state =>
      state.whenCompleted(goto(waitAndBufferPacketHead))
    }

    waitAndBufferPacketHead
      .whenIsActive{
        io.nocRec.ready := True
        when(io.nocRec.valid){
          switch(bph.packetType){
            if(supportMemSlave){
              is(PacketType.R_CMD) { goto(cmdReg) }
              is(PacketType.D_CMD) { goto(cmdData) }
            }
            if (supportMemMaster) {
              is(PacketType.R_RSP) { goto(rspRec) }
              is(PacketType.D_RSP) { goto(rspRec) }
            }
            is(PacketType.AER) { goto(cmdAer) }
            default { goto(headError) }
          }
        }
      }
  }
}