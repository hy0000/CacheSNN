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
    PackageType.R_RSP.asBits.resized(3) ## B(0, 9 bits) ## id ## data
  }
}

abstract class NocCore extends Component {
  val noc = master(NocInterfaceLocal())

  val interface = new Bundle {
    val regBus = master(NocCore.regBus) // for reg ctrl
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32))) // as memory slave for accessing
    val aer = master(new AerPacket) // decoded aer data
    val localSend = slave(new BasePacket) // for user logic to send packet
    val localRsp = master(new BasePacket) // for user handle packet rsp
  }

  val nocUnPacker = new NocUnPacker
  val nocPacker = new NocPacker

  nocUnPacker.io.nocRec << noc.rec
  nocUnPacker.io.regBus <> interface.regBus
  nocUnPacker.io.dataBus <> interface.dataBus
  nocUnPacker.io.aer <> interface.aer
  nocUnPacker.io.localRsp <> interface.localRsp

  nocPacker.io.rspRecId << nocUnPacker.io.rspRecId
  nocPacker.io.localSend <> interface.localSend

  noc.send << StreamArbiterFactory.fragmentLock.lowerFirst.on(
    Seq(nocUnPacker.io.rspSend, nocPacker.io.send)
  )
}

class NocUnPacker extends Component {
  val io = new Bundle {
    val nocRec = slave(NocInterface())
    val regBus = master(NocCore.regBus)
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32)))
    val aer = master(new AerPacket)
    val rspRecId = master(Stream(UInt(4 bits)))
    val rspSend = master(NocInterface())
    val localRsp = master(new BasePacket)
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

    val write = basePacketHead.field0
    io.regBus.PWRITE := write
    io.regBus.PADDR := basePacketHead.field1
    io.regBus.PWDATA := basePacketHead.field2

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

  }

  val fsm = new StateMachine {
    val idle = makeInstantEntry()
    val cmdReg, cmdData, cmdAer = new State
    val rspRec = new State
    val undefineHead = new State

    idle
      .whenIsActive{
        io.nocRec.ready := True
        when(io.nocRec.valid){
          switch(basePacketHead){
            is(PackageType.R_CMD) { goto(cmdReg)  }
            is(PackageType.D_CMD) { goto(cmdData) }
            is(PackageType.R_RSP) { goto(rspRec)  }
            is(PackageType.D_RSP) { goto(rspRec) }
            is(PackageType.AER)   { goto(cmdAer)  }
            default { goto(undefineHead) }
          }
        }
      }
    cmdReg
      .whenIsActive{
        io.regBus := True
        io.regBus.we := basePacketHead.field0
        io.regBus.addr := basePacketHead.field1
        io.regBus.wrdata := basePacketHead.field2
        when(io.regBus.we.asBool){

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