package Synapse

import CacheSNN.{CacheSNN, AER}
import RingNoC.NocInterfaceLocal
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.simple.{PipelinedMemoryBusConfig, PipelinedMemoryBusInterconnect}

import scala.collection.immutable.ListMap

object SynapseCore {
  val busDataWidth = 64
  val busByteCount = busDataWidth / 8
  val timeWindowWidth = 16

  object AddrMapping {
    val cache      = SizeMapping(0, CacheConfig.size)
    val current    = SizeMapping(cache.base + cache.size, 8 KiB)
    val preSpike   = SizeMapping(current.base + current.size, 4 KiB)
    val postSpike  = SizeMapping(preSpike.base + preSpike.size, 4 KiB)
    val ltpLut     = SizeMapping(postSpike.base + postSpike.size, 32 Byte)
    val ltdLut     = SizeMapping(ltpLut.base + ltpLut.size, 32 Byte)

    def apply(): ListMap[String, SizeMapping] ={
      ListMap(
        "cache" -> cache,
        "current" -> current,
        "preSpike" -> preSpike,
        "postSpike" -> postSpike,
        "ltpLut" -> ltpLut,
        "ltdLut" -> ltdLut
      )
    }

    def pipelineMemoryBusConfig: ListMap[String, PipelinedMemoryBusConfig] = {
      this().map{case (k, v) =>
        k -> PipelinedMemoryBusConfig(log2Up(v.size), busDataWidth)
      }
    }

    def printAddressMapping(): Unit = {
      this().foreach{case (name, mapping) =>
        val size = if (mapping.size < (1 KiB)) {
          f"${mapping.size}%3d  B"
        } else {
          f"${mapping.size / 1024}%3d KB"
        }
        println(f"$name%-9s 0x${mapping.base.hexString(20)} " + size)
      }
    }
  }

  val pipeLineMemoryBusMasterConfig = PipelinedMemoryBusConfig(
    addressWidth = log2Up(AddrMapping.cache.size) + 1,
    dataWidth = busDataWidth
  )
}

object CacheConfig {
  val size = 128 KiB
  val lines = 128
  val ways = 8
  val wordAddrWidth = log2Up(size / 8)
}

case class MemWriteCmd(dataWidth:Int, addrWidth:Int) extends Bundle {
  val address = UInt(addrWidth bits)
  val data = Bits(dataWidth bits)
}

case class MemReadWrite(dataWidth:Int, addrWidth:Int) extends Bundle with IMasterSlave {
  val read = MemReadPort(Bits(dataWidth bits), addrWidth)
  val write = Flow(MemWriteCmd(dataWidth, addrWidth))

  override def asMaster(): Unit = {
    master(read, write)
  }
}

class Spike extends Bundle {
  val nid = UInt(AER.nidWidth bits)
  val learning = Bool()
}

class SpikeEvent extends Spike {
  val cacheAddr = UInt(CacheConfig.wordAddrWidth bits)
  // TODO: if it's needed to add virtual spike, need change PreSpikeFetch logic
  // val virtual = Bool()

  def cacheAllocateFailed: Bool = cacheAddr.andR
  def setCacheAllocateFail(): Unit = cacheAddr.setAll()
}

class ExpLutQuery extends Bundle with IMasterSlave {
  val x = Vec(Flow(UInt(log2Up(SynapseCore.timeWindowWidth) bits)), 4)
  val y = Vec(SInt(16 bits), 4)
  override def asMaster(): Unit = {
    out(x)
    in(y)
  }
}

class SynapseCore extends Component {
  import SynapseCore.AddrMapping

  val io = new Bundle {
    val noc = slave(NocInterfaceLocal(CacheSNN.nocBusWidth))
  }

  val synapseCtrl = new SynapseCtrl
  val synapse = new Synapse
  val preSpikeFetch = new PreSpikeFetch

  val slaveBusConfig = AddrMapping.pipelineMemoryBusConfig

  val cache = new Cache(slaveBusConfig("cache"))
  val currentRam = new Buffer(slaveBusConfig("current"), AddrMapping.current.size)
  val preSpikeRam = new Buffer(slaveBusConfig("preSpike"), AddrMapping.preSpike.size)
  val postSpikeRam = new Buffer(slaveBusConfig("postSpike"), AddrMapping.postSpike.size)
  val ltpLut = new ExpLut(slaveBusConfig("ltpLut"))
  val ltdLut = new ExpLut(slaveBusConfig("ltdLut"))

  val interconnect = PipelinedMemoryBusInterconnect()

  interconnect.addSlave(cache.io.bus, AddrMapping.cache)
  interconnect.addSlave(currentRam.io.bus, AddrMapping.current)
  interconnect.addSlave(preSpikeRam.io.bus, AddrMapping.preSpike)
  interconnect.addSlave(postSpikeRam.io.bus, AddrMapping.postSpike)
  interconnect.addSlave(ltpLut.io.bus, AddrMapping.ltpLut)
  interconnect.addSlave(ltdLut.io.bus, AddrMapping.ltdLut)

  interconnect.addMaster(
    synapseCtrl.io.bus,
    Seq(cache.io.bus, currentRam.io.bus, preSpikeRam.io.bus, postSpikeRam.io.bus, ltpLut.io.bus, ltdLut.io.bus)
  )

  synapseCtrl.io.noc <> io.noc
  synapseCtrl.io.spikeEventDone := synapse.io.synapseEventDone
  synapseCtrl.io.spikeEvent >> preSpikeFetch.io.spikeEvent
  preSpikeFetch.io.synapseEvent >> synapse.io.synapseEvent
  synapse.io.synapseData <> cache.io.synapseData
  synapse.io.current <> currentRam.io.mem
  synapse.io.ltdQuery <> ltdLut.io.query
  synapse.io.ltpQuery <> ltpLut.io.query
  postSpikeRam.io.mem.read <> synapse.io.postSpike
  postSpikeRam.io.mem.write.setIdle()
  preSpikeRam.io.mem <> preSpikeFetch.io.preSpike
}