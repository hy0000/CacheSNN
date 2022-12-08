package Synapse

import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple.PipelinedMemoryBus

object SpikeManager {
  val setIndexRange = log2Up(CacheConfig.lines/CacheConfig.ways)-1 downto 0

  val tagStep = CacheConfig.ways / 2
  val tagRamAddressWidth = setIndexRange.size + log2Up(tagStep)
}

class SpikeManager extends Component {
  val io = new Bundle {
    val spike = slave(Stream(new Spike))
    val spikeEvent = master(Stream(new SpikeEvent))
    val bus = master(MemAccessBus())
    val dataFill = master(Stream(new SynapseData))
    val dataWriteBack = master(Stream(new SynapseData))
    val synapseEventDone = slave(Stream(new Spike))
  }
  stub()
}

class SpikeCacheAllocator extends Component {
  val io = new Bundle {
    val spikeIn = slave(Stream(new Spike))
    val missSpike = master(Stream(new SpikeEvent))
    val hitSpike = master(Stream(new SpikeEvent))
  }
  stub()
}

class MissSpikeManager extends Component {
  val io = new Bundle {
    val dataIn = slave(Stream(Fragment(new SynapseData)))
    val dataOut = master(Stream(Fragment(new SynapseData)))
    val bus = master(MemAccessBus())
    val missSpike = slave(Stream(new SpikeEvent))
    val inferenceOnly = in Bool()
  }
  stub()
}

class SpikeShifter extends Component {
  val io = new Bundle {
    val run = slave(Event) // valid start ready done
    val bus = master(MemAccessBus())
  }
  stub()
}