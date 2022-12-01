package Synapse

import CacheSNN.{CacheSNN, AER}
import RingNoC.NocInterfaceLocal
import Synapse.SynapseCore.pipeLineMemoryBusMasterConfig
import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple.PipelinedMemoryBus

class Spike extends Bundle {
  val nid = UInt(AER.nidWidth bits)
}

class SynapseCtrl extends Component {
  val io = new Bundle {
    val noc = slave(NocInterfaceLocal(CacheSNN.nocBusWidth))
    val cacheBus = master(PipelinedMemoryBus(pipeLineMemoryBusMasterConfig))
    val bufferBus = master(PipelinedMemoryBus(pipeLineMemoryBusMasterConfig))
    val synapseEvent = master(Stream(new SynapseEvent))
    val synapseEventDone = slave(Event)
  }
  stub()
}