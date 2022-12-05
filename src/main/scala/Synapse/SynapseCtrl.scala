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
    val synapseEvent = master(Stream(new SynapseEvent))
    val synapseEventDone = in Bool()
  }
  stub()
}

class SynapseData extends Bundle {
  val data = Bits(SynapseCore.busDataWidth bits)

  def lenFieldRange = 7 downto 0
  def nidFieldRange = lenFieldRange.high + AER.nidWidth downto lenFieldRange.high + 1
  def getLen = data(lenFieldRange).asUInt
  def getNid = data(nidFieldRange).asUInt
  def setHeadField(nid:UInt, len: UInt): Unit ={
    data(lenFieldRange) := len.asBits
    data(nidFieldRange) := nid.asBits
  }
}

class DataAccessCmd extends Bundle {
  val address = UInt()
  val len = UInt()
  val write = Bool()
}
class WeightAccessCmd extends DataAccessCmd {
  val nid = UInt(SynapseCore.busDataWidth bits)
}

class MissSpikeManager extends Component {
  val io = new Bundle {
    val dataIn = slave(Stream(Fragment(new SynapseData)))
    val dataOut = master(Stream(Fragment(new SynapseData)))
    val cache = master(PipelinedMemoryBus(SynapseCore.pipeLineMemoryBusMasterConfig))
    val missSpike = slave(Stream(new SpikeEvent))
    val inferenceOnly = in Bool()
  }
  stub()
}