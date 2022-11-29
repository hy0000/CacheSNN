package Synapse

import CacheSNN.CacheSNN
import RingNoC.NocInterfaceLocal
import Synapse.SynapseCore.AddrMapping
import spinal.core._
import spinal.core.fiber.Handle
import spinal.lib._
import spinal.lib.bus.bmb._
import spinal.lib.bus.misc.SizeMapping

object SynapseCore {
  val busDataWidth = 64
  val busByteCount = busDataWidth / 8

  object AddrMapping {
    val cache      = SizeMapping(0, CacheConfig.size)
    val current    = SizeMapping(cache.base + cache.size, 8 KiB)
    val preSpike   = SizeMapping(current.base + current.size, 4 KiB)
    val postSpike  = SizeMapping(preSpike.base + preSpike.size, 4 KiB)

    def apply(): Map[String, SizeMapping] ={
      Map(
        "cache" -> cache,
        "current" -> current,
        "preSpike" -> preSpike,
        "postSpike" -> postSpike
      )
    }

    def printAddressMapping(): Unit = {
      this().foreach{case (name, mapping) =>
        println(f"$name%-9s 0x${mapping.base.hexString(20)} ${mapping.size / 1024}%3d KB")
      }
    }
  }

  val bmbMasterParameter = BmbParameter(
    addressWidth = log2Up(AddrMapping.cache.size) + 1,
    dataWidth = busDataWidth,
    sourceWidth = 0,
    contextWidth = 0,
    lengthWidth = 8,
    alignment = BmbParameter.BurstAlignement.LENGTH,
    accessLatencyMin = 2
  )

  val postNeuronAddrWidth = AddrMapping.current.size / busByteCount
}

object CacheConfig {
  val size = 128 KiB
  val lines = 128
  val ways = 8
  val addrWidth = log2Up(size / SynapseCore.busByteCount)
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

class SpikeEvent extends Bundle {
  val cacheAddr = UInt(CacheConfig.addrWidth bits)
  val postNidOffset = UInt(SynapseCore.postNeuronAddrWidth bits)
}

class SynapseEvent extends SpikeEvent {
  val preSpike = Bits(16 bits)
}

class SynapseCore extends Component {
  import SynapseCore.AddrMapping
  import SynapseCore.bmbMasterParameter

  val io = new Bundle {
    val noc = slave(NocInterfaceLocal(CacheSNN.nocBusWidth))
  }

  val synapseCtrl = new SynapseCtrl
  val synapse = new Synapse

  val bmbInterconnect = BmbInterconnectGenerator()

  case class BmbSlave(mapping:SizeMapping){
    val bus = Handle[Bmb]
    val requirements = Handle[BmbAccessParameter]
    val accessCapabilities = BmbOnChipRam.busCapabilities(mapping.size, 64)
    val parameter = Handle(requirements.toBmbParameter())
  }

  case class BmbMaster(bus:Handle[Bmb], parameter:BmbParameter){
    val accessParameter = Handle(parameter.access)
  }

  val bmbSlave = AddrMapping().map{case (k, v) => k -> BmbSlave(v)}
  val bmbMaster = Map(
    "cache" -> BmbMaster(Handle(synapseCtrl.io.cacheBus), bmbMasterParameter),
    "buffer" -> BmbMaster(Handle(synapseCtrl.io.bufferBus), bmbMasterParameter),
  )

  for(s <- bmbSlave.values){
    bmbInterconnect.addSlave(
      accessCapabilities = s.accessCapabilities,
      accessRequirements = s.requirements,
      bus = s.bus,
      mapping = s.mapping
    )
  }

  for(m <- bmbMaster.values){
    bmbInterconnect.addMaster(
      accessRequirements = m.accessParameter,
      bus = m.bus
    )
  }

  implicit def getSlaveBmb(s: BmbSlave): Handle[Bmb] = s.bus
  implicit def getMasterBmb(m: BmbMaster): Handle[Bmb] = m.bus

  bmbInterconnect.addConnection(bmbMaster("cache"),  bmbSlave("cache"))
  bmbInterconnect.addConnection(bmbMaster("buffer"), bmbSlave("preSpike"))
  bmbInterconnect.addConnection(bmbMaster("buffer"), bmbSlave("postSpike"))
  bmbInterconnect.addConnection(bmbMaster("buffer"), bmbSlave("current"))

  val cache        = Handle(new Cache(bmbSlave("cache").parameter))
  val currentRam   = Handle(new BmbRam(bmbSlave("current").parameter, AddrMapping.current.size))
  val preSpikeRam  = Handle(new BmbRam(bmbSlave("preSpike").parameter, AddrMapping.preSpike.size))
  val postSpikeRam = Handle(new BmbRam(bmbSlave("postSpike").parameter, AddrMapping.postSpike.size))

  bmbSlave("cache").bus.load(cache.io.bmb)
  bmbSlave("current").bus.load(currentRam.io.bmb)
  bmbSlave("preSpike").bus.load(preSpikeRam.io.bmb)
  bmbSlave("postSpike").bus.load(postSpikeRam.io.bmb)

  synapseCtrl.io.noc <> io.noc
  synapseCtrl.io.synapseEvent <> synapse.io.synapseEvent
  synapse.io.synapseData <> cache.io.synapseData
  synapse.io.current <> currentRam.io.mem
  postSpikeRam.io.mem.read <> synapse.io.postSpike
  postSpikeRam.io.mem.write.setIdle()
}