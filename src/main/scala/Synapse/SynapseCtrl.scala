package Synapse

import CacheSNN.CacheSNN
import RingNoC.NocInterfaceLocal
import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb.Bmb

class SynapseCtrl extends Component {
  val io = new Bundle {
    val noc = slave(NocInterfaceLocal(CacheSNN.nocBusWidth))
    val cacheBus = master(Bmb(SynapseCore.bmbMasterParameter))
    val bufferBus = master(Bmb(SynapseCore.bmbMasterParameter))
    val synapseEvent = master(Stream(new SynapseEvent))
    val synapseEventDone = slave(Event)
  }
  stub()
}