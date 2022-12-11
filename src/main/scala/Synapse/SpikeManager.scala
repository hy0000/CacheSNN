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
    val dataFill = master(Stream(Fragment(new SynapseData)))
    val dataWriteBack = master(Stream(Fragment(new SynapseData)))
    val synapseEventDone = slave(Stream(new Spike))
  }

  val spikeAllocator = new SpikeCacheAllocator
  val missManager = new MissSpikeManager
  val hitQueue = StreamFifo(cloneOf(io.spikeEvent.payload), 512)
  val readyQueue = StreamFifo(cloneOf(io.spikeEvent.payload), 4)

  io.spike >> spikeAllocator.io.spikeIn
  spikeAllocator.io.hitSpike >> hitQueue.io.push
  spikeAllocator.io.missSpike >> missManager.io.missSpike
  missManager.io.readySpike >> readyQueue.io.push

  io.spikeEvent <-< StreamArbiterFactory.lowerFirst
    .on(Seq(readyQueue.io.pop, hitQueue.io.pop))

  missManager.io.bus <> io.bus
  missManager.io.dataIn << io.dataFill
  missManager.io.dataOut >> io.dataWriteBack
  io.synapseEventDone >> spikeAllocator.io.synapseEventDone
}

class SpikeCacheAllocator extends Component {
  val io = new Bundle {
    val spikeIn = slave(Stream(new Spike))
    val missSpike = master(Stream(new SpikeEvent))
    val hitSpike = master(Stream(new SpikeEvent))
    val synapseEventDone = slave(Stream(new Spike))
  }
  stub()
}

class MissSpikeManager extends Component {
  val io = new Bundle {
    val dataIn = slave(Stream(Fragment(new SynapseData)))
    val dataOut = master(Stream(Fragment(new SynapseData)))
    val bus = master(MemAccessBus())
    val missSpike = slave(Stream(new SpikeEvent))
    val readySpike = master(Stream(new SpikeEvent))
    val inferenceOnly = in Bool()
  }
  stub()
}