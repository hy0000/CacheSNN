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

  val isMemMasterCore:Boolean = false

  val interface = new Bundle {
    val regBus = master(NocCore.regBus) // for reg ctrl
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32))) // as memory slave for accessing
    val aer = master(new AerPacket) // decoded aer data
    val localSend = slave(new BasePacket) // for user logic to send packet
    val readRsp = ifGen(isMemMasterCore)(master(BaseReadRsp()))
    val writeRsp = ifGen(isMemMasterCore)(master(BaseWriteRsp()))
  }

  val nocUnPacker = new NocUnPacker(isMemMasterCore)
  val nocPacker = new NocPacker

  nocUnPacker.io.nocRec << noc.rec
  nocUnPacker.io.regBus <> interface.regBus
  nocUnPacker.io.dataBus <> interface.dataBus
  nocUnPacker.io.aer <> interface.aer
  if(isMemMasterCore){
    nocUnPacker.io.readRsp >> interface.readRsp
    nocUnPacker.io.writeRsp >> interface.writeRsp
  }

  nocPacker.io.rspRecId << nocUnPacker.io.rspRecId
  nocPacker.io.localSend <> interface.localSend

  noc.send << StreamArbiterFactory.fragmentLock.lowerFirst.on(
    Seq(nocUnPacker.io.rspSend, nocPacker.io.send)
  )
}

class NocUnPacker(isMemMasterCore:Boolean) extends Component {
  val io = new Bundle {
    val nocRec = slave(NocInterface())
    val regBus = master(NocCore.regBus)
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32)))
    val aer = master(new AerPacket)
    val rspRecId = master(Stream(UInt(4 bits)))
    val rspSend = master(NocInterface())
    val readRsp = ifGen(isMemMasterCore)(master(BaseReadRsp()))
    val writeRsp = ifGen(isMemMasterCore)(master(BaseWriteRsp()))
  }

  val head = RegNextWhen(io.nocRec.fragment, io.nocRec.firstFire)
  val basePacketHead = new BasePacketHead
  basePacketHead.assignFromNocCustomField(head.custom)

  io.flattenForeach{bt =>
    if(bt.isOutput){
      bt := bt.getZero
    }
  }

  val apb3RegAccess = new StateMachine {
    val setup = makeInstantEntry()
    val access = new State
    val rsp = new State

    this.isRunning{
      io.regBus.PWRITE := write
      io.regBus.PADDR := basePacketHead.field1.asUInt
      io.regBus.PWDATA := basePacketHead.field2
    }

    val write = basePacketHead.field0
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
        val custom = NocCore.regRspPack(basePacketHead.id, rData)
        io.rspSend.setHead(dest = head.src, custom)
        when(io.rspSend.ready){
          exitFsm()
        }
      }
  }

  val dataAccess = new StateMachine {
    val cmd = makeInstantEntry()
    val readRsp, writeRsp = new State

    val write = basePacketHead.field0

    cmd
      .whenIsActive{
        io.dataBus.cmd.valid := io.nocRec.ready || write
        io.dataBus.cmd.write := write
        io.dataBus.cmd.len := basePacketHead.field1.asUInt
        io.dataBus.cmd.address := basePacketHead.field2.asUInt.resized
        when(write) {
          io.dataBus.cmd.data := io.nocRec.flit
          io.nocRec.ready := io.dataBus.cmd.ready
          when(io.nocRec.lastFire){
            goto(writeRsp)
          }
        }otherwise{
          when(io.dataBus.cmd.ready){
            goto(readRsp)
          }
        }
      }
    writeRsp
      .whenIsActive{
        io.rspSend.valid := True
        val custom = NocCore.dataRspHeadPack(basePacketHead.id)
        println(custom.getWidth)
        io.rspSend.setHead(dest = head.src, custom)
        when(io.rspSend.ready) {
          exitFsm()
        }
      }
    readRsp
      .whenIsActive{

      }
  }

  val fsm = new StateMachine {
    val waitePacketHead = makeInstantEntry()
    val cmdReg, cmdData, cmdAer = new State
    val rspRec = new State
    val undefineHead = new State

    waitePacketHead
      .whenIsActive{
        io.nocRec.ready := True
        when(io.nocRec.valid){
          switch(basePacketHead.packetType.asBits){
            is(PacketType.R_CMD.asBits) { goto(cmdReg)  }
            is(PacketType.D_CMD.asBits) { goto(cmdData) }
            is(PacketType.R_RSP.asBits) { goto(rspRec)  }
            is(PacketType.D_RSP.asBits) { goto(rspRec) }
            is(PacketType.AER.asBits)   { goto(cmdAer)  }
            default { goto(undefineHead) }
          }
        }
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