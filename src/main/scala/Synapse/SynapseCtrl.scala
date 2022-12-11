package Synapse

import CacheSNN.{CacheSNN, AER}
import RingNoC.NocInterfaceLocal
import Synapse.SynapseCore.pipeLineMemoryBusMasterConfig
import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple.PipelinedMemoryBus

class SynapseCtrl extends Component {
  val io = new Bundle {
    val noc = slave(NocInterfaceLocal(CacheSNN.nocBusWidth))
    val cacheBus = master(PipelinedMemoryBus(pipeLineMemoryBusMasterConfig))
    val bufferBus = master(PipelinedMemoryBus(pipeLineMemoryBusMasterConfig))
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
  val rsp = Flow(cloneOf(cmd.data))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }

  def toPipeLineMemoryBus: PipelinedMemoryBus ={
    val that = PipelinedMemoryBus(pipeLineMemoryBusMasterConfig)
    val addrIncr = Counter(8 bits, cmd.fire)
    when(addrIncr===cmd.len && addrIncr.willIncrement){
      addrIncr.clear()
    }

    that.cmd << cmd.translateWith{
      val ret = cloneOf(that.cmd)
      ret.write := cmd.write
      ret.address := cmd.address + (addrIncr @@ U"000")
      ret.data := cmd.data
      ret.mask := 0xFF
      ret
    }
    rsp << that.rsp.translateWith(that.rsp.data)
    that
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