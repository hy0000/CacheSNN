package Synapse

import Synapse.SynapseCore.AddrMapping
import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

class BmbRam(p:BmbParameter, size:BigInt) extends Component {
  val addrWidth = log2Up(size / (SynapseCore.busDataWidth / 8))
  val io = new Bundle {
    val bmb = slave(Bmb(p))
    val mem = slave(MemReadWrite(SynapseCore.busDataWidth, addrWidth))
  }

  val ram = Mem(Bits(p.access.dataWidth bits), size / p.access.byteCount)

  val bmbAddress = (io.bmb.cmd.address >> p.access.wordRangeLength).resize(ram.addressWidth)
  val bmbCmd = io.bmb.cmd.haltWhen(io.bmb.rsp.isStall)
  val bmbDe = StreamDemux(bmbCmd, io.bmb.cmd.isRead.asUInt, 2)
  val bmbWriteCmd = bmbDe.head.translateWith{
    val ret = cloneOf(io.mem.write.payload)
    ret.address := bmbAddress
    ret.data := bmbCmd.data
    ret
  }
  val bmbReadCmd = bmbDe.last.translateWith(bmbAddress)

  val ramReadCmd = StreamFlowArbiter(bmbReadCmd, io.mem.read.cmd)
  val ramWriteCmd = StreamFlowArbiter(bmbWriteCmd, io.mem.write)

  ram.write(
    address = ramWriteCmd.address,
    data = ramWriteCmd.data,
    enable = ramWriteCmd.valid
  )

  val rsp = ram.flowReadSync(ramReadCmd)
  val dataDelay = RegNext(rsp.payload)
  val fireDelay = RegNext(rsp.valid, False)
  val stall = fireDelay && !io.bmb.rsp.ready
  val validHold = RegInit(False) clearWhen io.bmb.rsp.ready setWhen stall
  val dataHold = RegNextWhen(dataDelay, stall)

  io.bmb.rsp.valid := validHold || fireDelay
  io.bmb.rsp.data := Mux(validHold, dataHold, dataDelay)
  io.bmb.rsp.source := Delay(bmbCmd.source, 2, bmbCmd.ready)
  io.bmb.rsp.context := Delay(bmbCmd.context, 2, bmbCmd.ready)

  io.mem.read.rsp := dataDelay
}

class Cache(p: BmbParameter) extends Component {

  val io = new Bundle {
    val bmb = slave(Bmb(p))
    val synapseDataBus = slave(MemReadWrite(SynapseCore.busDataWidth, CacheConfig.addrWidth))
  }
  stub()
}