package Synapse

import Util.MemReadWrite
import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple._

class Buffer(p:PipelinedMemoryBusConfig, size:BigInt) extends Component {
  def readDelay = 2

  val io = new Bundle {
    val bus = slave(PipelinedMemoryBus(p))
    val mem = slave(MemReadWrite(SynapseCore.busDataWidth, p.addressWidth - log2Up(SynapseCore.busByteCount)))
  }

  val ram = Mem(Bits(p.dataWidth bits), size / SynapseCore.busByteCount)

  val busAddress = io.bus.cmd.address >> log2Up(SynapseCore.busByteCount)
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

class Cache(p:PipelinedMemoryBusConfig) extends Component {
  def busReadDelay = 2
  def synapseReadDelay = 3

  val io = new Bundle {
    val bus = slave(PipelinedMemoryBus(p))
    val synapseData = slave(MemReadWrite(SynapseCore.busDataWidth, CacheConfig.wordAddrWidth))
  }

  val rams = Array.fill(2)(
    Mem(Bits(p.dataWidth bits), CacheConfig.size / 2 / SynapseCore.busByteCount)
  )

  val busArea = new Area {
    io.bus.cmd.freeRun()
    val busAddress = io.bus.cmd.address >> log2Up(SynapseCore.busByteCount)
    val readValid = !io.bus.cmd.write && io.bus.cmd.valid
    val ramSel = busAddress.lsb.asUInt
    val ramSelDelay = Delay(ramSel, busReadDelay)
    val readValidDelay = Delay(readValid, busReadDelay, init = False)

    val data = Vec(rams.zipWithIndex.map { case (ram, i) =>
      val ret = ram.readWriteSync(
        address = (busAddress >> 1).resized,
        data = io.bus.cmd.data,
        enable = io.bus.cmd.fire && ramSel === i,
        write = io.bus.cmd.write,
      )
      Delay(ret, busReadDelay - 1)
    })(ramSelDelay)

    io.bus.rsp.data := data
    io.bus.rsp.valid := readValidDelay
  }

  val rwLogic = new Area {
    val data = Vec(rams.zipWithIndex.map { case (ram, i) =>
      val readValid = io.synapseData.read.cmd.valid && io.synapseData.read.cmd.payload.lsb.asUInt === i
      val writeValid = io.synapseData.write.valid && io.synapseData.write.address.lsb.asUInt === i
      when(readValid) {
        assert(
          assertion = !writeValid,
          message = L"rw conflict occur at Cache MemReadWrite: " ++
            L"both read 0x${io.synapseData.read.cmd.payload} " ++
            L"and write 0x${io.synapseData.write.address} at the same time",
          severity = FAILURE
        )
      }
      // read advance
      val address = Mux(readValid, io.synapseData.read.cmd.payload, io.synapseData.write.address)

      val ret = ram.readWriteSync(
        address = address >> 1,
        data = io.synapseData.write.data,
        enable = readValid || writeValid,
        write = writeValid
      )
      Delay(ret, synapseReadDelay - 1)
    })

    val rspSel = Delay(io.synapseData.read.cmd.payload.lsb.asUInt, synapseReadDelay)
    io.synapseData.read.rsp := data(rspSel)
  }
}

class ExpLut(p:PipelinedMemoryBusConfig) extends Component {
  def readDelay = 1

  val io = new Bundle {
    val bus = slave(PipelinedMemoryBus(p))
    val query = slave(new ExpLutQuery)
  }

  val ram = Mem(SInt(16 bits), SynapseCore.timeWindowWidth)

  val (cmd, cnt) = io.bus.cmd.repeat(p.dataWidth / 16)
  cmd.freeRun()
  io.bus.rsp.setIdle()
  val data = cmd.data.subdivideIn(16 bits)(cnt).asBits.asSInt
  ram.write(
    address = (cmd.address >> log2Up(SynapseCore.busByteCount)) @@ cnt,
    data = data,
    enable = cmd.valid && cmd.write
  )

  for((x, y) <- io.query.x.zip(io.query.y)){
    y := ram.readSync(x.payload, x.valid)
    val valid = RegNext(x.valid, init = False)
    when(!valid){
      y := 0
    }
  }
}