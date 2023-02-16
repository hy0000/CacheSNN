package Util

import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple._

class PipelinedMemoryBusRam(dataWidth:Int, size:BigInt) extends Component {
  def readDelay = 2
  val bytePerWord = dataWidth / 8
  val wordCount = size / bytePerWord

  val io = new Bundle {
    val bus = slave(PipelinedMemoryBus(addressWidth = log2Up(size), dataWidth))
    val mem = slave(MemReadWrite(dataWidth, log2Up(wordCount)))
  }

  val ram = Mem(Bits(dataWidth bits), wordCount)

  val busAddress = io.bus.cmd.address >> log2Up(bytePerWord)
  val busDe = StreamDemux(io.bus.cmd, io.bus.cmd.write.asUInt, 2)
  val busReadCmd = busDe.head.translateWith(busAddress)
  val busWriteCmd = busDe.last.translateWith {
    val ret = cloneOf(io.mem.write.payload)
    ret.address := busAddress
    ret.data := io.bus.cmd.data
    ret
  }

  val ramReadCmd = StreamFlowArbiter(busReadCmd, io.mem.read.cmd)
  val ramWriteCmd = StreamFlowArbiter(busWriteCmd, io.mem.write)

  ram.write(
    address = ramWriteCmd.address,
    data = ramWriteCmd.data,
    enable = ramWriteCmd.valid
  )

  val data = RegNext(ram.readSync(ramReadCmd.payload, ramReadCmd.valid))
  io.mem.read.rsp := data
  io.bus.rsp.data := data
  io.bus.rsp.valid := Delay(busReadCmd.fire, readDelay, init = False)
}
