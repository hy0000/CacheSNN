package Synapse

import CacheSNN.{AER, CacheSNN}
import RingNoC.NocInterfaceLocal
import Synapse.SynapseCore.pipeLineMemoryBusMasterConfig
import Util.MemAccessBus
import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig}

class SynapseCtrl extends Component {
  val io = new Bundle {
    val noc = slave(NocInterfaceLocal())
    val bus = master(PipelinedMemoryBus(pipeLineMemoryBusMasterConfig))
    val spikeEvent = master(Stream(new SpikeEvent))
    val spikeEventDone = in Bool()
  }
  stub()
}

class SpikeShifter extends Component {
  val io = new Bundle {
    val run = slave(Event) // valid start ready done
    val bus = master(MemAccessBus(SynapseCore.memAccessBusConfig))
  }
  stub()
}

class SpikeUpdater extends Component {
  val io = new Bundle {
    val spike = slave(Stream(new Spike))
    val bus = master(MemAccessBus(SynapseCore.memAccessBusConfig))
  }
  stub()
}