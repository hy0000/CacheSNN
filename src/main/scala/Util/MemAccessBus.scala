package Util

import spinal.core._
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig}
import spinal.lib._

case class MemAccessBusConfig(dataWidth:Int, addrWidth:Int)

class MemAccessCmd(c:MemAccessBusConfig) extends Bundle {
  val address = UInt(c.addrWidth bits)
  val len = UInt(8 bits)
  val write = Bool()
  val data = Bits(c.dataWidth bits)
}

case class MemAccessBus(c:MemAccessBusConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(new MemAccessCmd(c))
  val rsp = Stream(Fragment(cloneOf(cmd.data)))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }

  def toPipeLineMemoryBus: PipelinedMemoryBus = {
    val trans = new MemAccessBusToPipeLineMemoryBus(c)
    trans.io.input.cmd << cmd
    trans.io.input.rsp >> rsp
    trans.io.output
  }
}

class MemAccessBusToPipeLineMemoryBus(c:MemAccessBusConfig) extends Component {
  val pipelinedMemoryBusConfig = PipelinedMemoryBusConfig(c.addrWidth, c.dataWidth)
  val io = new Bundle {
    val input = slave(MemAccessBus(c))
    val output = master(PipelinedMemoryBus(pipelinedMemoryBusConfig))
  }
  val (cmdForReadRsp, cmd) = StreamFork2(io.input.cmd)

  val cmdS2m = cmd.s2mPipe()
  val that = cloneOf(io.output)
  val cmdFire = that.cmd.fire
  val addrIncr = Counter(cmd.len.getWidth bits, cmdFire)
  val last = addrIncr === cmd.len
  val lastFire = last && cmdFire
  when(lastFire) {
    addrIncr.clear()
  }
  val inReadBurst = cmdS2m.valid && !cmdS2m.write && !last
  cmdS2m.ready := !inReadBurst && cmdFire
  that.cmd.valid := cmdS2m.valid && io.input.rsp.ready
  that.cmd.write := cmdS2m.write
  that.cmd.address := cmdS2m.address  + (addrIncr @@ U"000")
  that.cmd.data := cmdS2m.data
  that.cmd.mask := 0xFF

  io.output.cmd <-< that.cmd
  io.output.rsp >> that.rsp

  val readCmdLen = cmdForReadRsp.takeWhen(!cmdForReadRsp.write)
    .translateWith(cmdForReadRsp.len).queue(4)
  val rspCnt = Counter(cmd.len.getWidth bits, that.rsp.valid)
  val lastRsp = readCmdLen.payload===rspCnt
  val lastRspFire = lastRsp && that.rsp.valid
  when(lastRspFire){
    rspCnt.clear()
  }
  readCmdLen.haltWhen(!lastRspFire).freeRun()
  io.input.rsp <-< that.rsp.translateWith {
    val ret = cloneOf(io.input.rsp.payload)
    ret.fragment := that.rsp.data
    ret.last := lastRsp
    ret
  }.toStream.queueLowLatency(4)
}