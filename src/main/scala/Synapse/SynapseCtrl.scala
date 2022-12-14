package Synapse

import CacheSNN.{AER, CacheSNN}
import RingNoC.NocInterfaceLocal
import Synapse.SynapseCore.pipeLineMemoryBusMasterConfig
import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig}

class SynapseCtrl extends Component {
  val io = new Bundle {
    val noc = slave(NocInterfaceLocal(CacheSNN.nocConfig))
    val bus = master(PipelinedMemoryBus(pipeLineMemoryBusMasterConfig))
    val spikeEvent = master(Stream(new SpikeEvent))
    val spikeEventDone = in Bool()
  }
  stub()
}

class SynapseData extends Bundle {
  val data = Fragment(Bits(SynapseCore.busDataWidth bits))

  def lenFieldRange = 7 downto 0
  def nidFieldRange = lenFieldRange.high + AER.nidWidth downto lenFieldRange.high + 1
  def getLen = data(lenFieldRange).asUInt
  def getNid = data(nidFieldRange).asUInt
  def setHeadField(nid:UInt, len: UInt): Unit ={
    data(lenFieldRange) := len.asBits
    data(nidFieldRange) := nid.asBits
  }
}

class MemAccessCmd extends Bundle {
  val address = UInt(pipeLineMemoryBusMasterConfig.addressWidth bits)
  val len = UInt(8 bits)
  val write = Bool()
  val data = Bits(SynapseCore.busDataWidth bits)
}

case class MemAccessBus() extends Bundle with IMasterSlave {
  val cmd = Stream(new MemAccessCmd)
  val rsp = Flow(Fragment(cloneOf(cmd.data)))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }

  def toPipeLineMemoryBus: PipelinedMemoryBus ={
    val trans = new MemAccessBusToPipeLineMemoryBus
    trans.io.input.cmd << cmd
    trans.io.input.rsp >> rsp
    trans.io.output
  }
}

class MemAccessBusToPipeLineMemoryBus extends Component {
  val io = new Bundle {
    val input = slave(MemAccessBus())
    val output = master(PipelinedMemoryBus(pipeLineMemoryBusMasterConfig))
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
  cmdS2m.ready := !inReadBurst && that.cmd.ready
  that.cmd.valid := cmdS2m.valid
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
  }
}

class SpikeShifter extends Component {
  val io = new Bundle {
    val run = slave(Event) // valid start ready done
    val bus = master(MemAccessBus())
  }
  stub()
}

class SpikeUpdater extends Component {
  val io = new Bundle {
    val spike = slave(Stream(new Spike))
    val bus = master(MemAccessBus())
  }
  stub()
}