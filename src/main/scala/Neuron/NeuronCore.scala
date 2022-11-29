package Neuron

import CacheSNN.CacheSNN
import RingNoC.NocInterfaceLocal
import spinal.core._
import spinal.lib._

class NeuronCore extends Component {
  val io = new Bundle {
    val noc = slave(NocInterfaceLocal(CacheSNN.nocBusWidth))
  }
  stub()
}