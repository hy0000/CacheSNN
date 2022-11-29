package Synapse

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb.{Bmb, BmbParameter}

class Synapse extends Component {
  import SynapseCore._
  val spikeBufferAddrWidth = log2Up(AddrMapping.postSpike.size / (SynapseCore.busDataWidth / 8))
  val currentBufferAddrWidth = log2Up(AddrMapping.current.size / (SynapseCore.busDataWidth / 8))

  val io = new Bundle {
    val eventBus = slave(SynapseEventBus())
    val synapseDataBus = master(MemReadWrite(64, CacheConfig.addrWidth))
    val postSpike = master(MemReadPort(Bits(64 bits), spikeBufferAddrWidth))
    val current = master(MemReadWrite(64, currentBufferAddrWidth))
  }
  stub()
}