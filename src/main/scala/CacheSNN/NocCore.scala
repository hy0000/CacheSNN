package CacheSNN

import RingNoC.{NocInterface, NocInterfaceLocal}
import Util.{MemAccessBus, MemAccessBusConfig}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.bram.{BRAM, BRAMConfig}

/**
 * All cores in this system extends from NocCore class
 *
 * NocCore provides packet decode logic which can:
 *  - access registers/memory automatically
 *  - transfer raw packet to AER event
 *  - handle sent packets order by id
 */
abstract class NocCore extends Component {
  val noc = master(NocInterfaceLocal())

  val interface = new Bundle {
    val regBus = master(BRAM(BRAMConfig(32, 8))) // for reg ctrl
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32))) // as memory slave for accessing
    val aer = master(new AerPackage) // decoded aer data
    val localSend = slave(new BasePackage) // for user logic to send raw packet
    val localRsp = master(new BasePackage) // for user handle access rsp
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
    val regBus = master(BRAM(BRAMConfig(32, 8)))
    val dataBus = master(MemAccessBus(MemAccessBusConfig(64, 32)))
    val aer = master(new AerPackage)
    val rspRecId = master(Stream(UInt(4 bits)))
    val rspSend = master(NocInterface())
    val localRsp = master(new BasePackage)
  }
  stub()
}

class NocPacker extends Component {
  val io = new Bundle {
    val send = master(NocInterface())
    val rspRecId = slave(Stream(UInt(4 bits)))
    val localSend = slave(new BasePackage)
  }
  val maxPending = 16
  stub()
}