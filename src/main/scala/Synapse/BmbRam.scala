package Synapse

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._

class BmbRam(p:BmbParameter, size:BigInt) extends Component {
  def readDelay = 2
  val addrWidth = log2Up(size / (SynapseCore.busDataWidth / 8))
  val io = new Bundle {
    val bmb = slave(Bmb(p))
    val mem = slave(MemReadWrite(SynapseCore.busDataWidth, addrWidth))
  }

  val ram = Mem(Bits(p.access.dataWidth bits), size / p.access.byteCount)

  val bmbAddress = (io.bmb.cmd.address >> p.access.wordRangeLength).resize(ram.addressWidth)
  val bmbNotStall = !io.bmb.rsp.isStall
  val bmbCmd = io.bmb.cmd.continueWhen(bmbNotStall)
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

  val data = RegNext(ram.readSync(ramReadCmd.payload, ramReadCmd.valid))
  val selHold = RegInit(False) setWhen io.bmb.rsp.isStall clearWhen io.bmb.rsp.ready
  val dataHold = RegNextWhen(data, !selHold && !io.bmb.rsp.ready)
  io.bmb.rsp.data := Mux(selHold, dataHold, data)
  io.bmb.rsp.valid := Delay(bmbCmd.valid, 2, bmbNotStall, False)
  io.bmb.rsp.source := Delay(bmbCmd.source, 2, bmbNotStall)
  io.bmb.rsp.context := Delay(bmbCmd.context, 2, bmbNotStall)
  io.bmb.rsp.setSuccess()
  io.bmb.rsp.last := True

  io.mem.read.rsp := data
}

class Cache(p: BmbParameter) extends Component {
  def readDelay = 2

  val io = new Bundle {
    val bmb = slave(Bmb(p))
    val synapseData = slave(MemReadWrite(SynapseCore.busDataWidth, CacheConfig.addrWidth))
  }

  val rams = Array.fill(2)(
    Mem(Bits(p.access.dataWidth bits), CacheConfig.size / 2 / p.access.byteCount)
  )

  val bmbLogic = new Area{
    val bmbAddress = io.bmb.cmd.address >> p.access.wordRangeLength
    val bmbNotStall = !io.bmb.rsp.isStall
    io.bmb.cmd.ready := bmbNotStall

    val ramSel = bmbAddress.lsb.asUInt
    val ramSelDelay = Delay(ramSel, 2, bmbNotStall)

    val data = Vec(rams.zipWithIndex.map{case (ram, i) =>
      val ret = ram.readWriteSync(
        address = (bmbAddress>>1).resized,
        data = io.bmb.cmd.data,
        enable = io.bmb.cmd.fire && ramSel===i,
        write = io.bmb.cmd.isWrite,
      )
      RegNext(ret)
    })(ramSelDelay)

    val selHold = RegInit(False) setWhen io.bmb.rsp.isStall clearWhen io.bmb.rsp.ready
    val dataHold = RegNextWhen(data, !selHold && !io.bmb.rsp.ready)
    io.bmb.rsp.data := Mux(selHold, dataHold, data)
    io.bmb.rsp.valid := Delay(io.bmb.cmd.valid, 2, bmbNotStall, False)
    io.bmb.rsp.source := Delay(io.bmb.cmd.source, 2, bmbNotStall)
    io.bmb.rsp.context := Delay(io.bmb.cmd.context, 2, bmbNotStall)
    io.bmb.rsp.setSuccess()
    io.bmb.rsp.last := True
  }

  val rwLogic = new Area {
    val data = Vec(rams.zipWithIndex.map{case (ram, i) =>
      val readValid = io.synapseData.read.cmd.valid && io.synapseData.read.cmd.payload.lsb.asUInt===i
      val writeValid = io.synapseData.write.valid && io.synapseData.write.address.lsb.asUInt===i
      when(readValid){
        assert(
          assertion = !writeValid,
          message = L"rw conflict occur at Cache MemReadWrite: " ++
              L"both read 0x${io.synapseData.read.cmd.payload} " ++
              L"and write 0x${io.synapseData.write.address} at the same time",
          severity =  FAILURE
        )
      }
      // read advance
      val address = Mux(readValid, io.synapseData.read.cmd.payload, io.synapseData.write.address)

      val ret = ram.readWriteSync(
        address = address>>1,
        data = io.synapseData.write.data,
        enable = readValid || writeValid,
        write = writeValid
      )
      RegNext(ret)
    })

    val rspSel = Delay(io.synapseData.read.cmd.payload.lsb.asUInt, readDelay)
    io.synapseData.read.rsp := data(rspSel)
  }
}