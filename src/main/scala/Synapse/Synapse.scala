package Synapse

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb.{Bmb, BmbParameter}

class Synapse extends Component {
  import SynapseCore._
  val spikeBufferAddrWidth = log2Up(AddrMapping.postSpike.size / busByteCount)
  val currentBufferAddrWidth = log2Up(AddrMapping.current.size / busByteCount)

  val io = new Bundle {
    val synapseEvent = slave(Stream(new SynapseEvent))
    val synapseData = master(MemReadWrite(64, CacheConfig.addrWidth))
    val synapseEventDone = master(Event)
    val postSpike = master(MemReadPort(Bits(64 bits), spikeBufferAddrWidth))
    val ltpLutBus = master(new ExpLutBus)
    val ltdLutBus = master(new ExpLutBus)
    val current = master(MemReadWrite(64, currentBufferAddrWidth))
  }
  stub()
}