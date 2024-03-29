package Synapse

import CacheSNN.{AER, NocCore}
import Synapse.SynapseCore.{maxPreSpike, timeWindowWidth}
import Util.{MemAccessBusConfig, MemAccessBusToPipeLineMemoryBus}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif.AccessType._
import spinal.lib.bus.regif._
import spinal.lib.bus.simple.{PipelinedMemoryBusConfig, PipelinedMemoryBusInterconnect}

import scala.collection.immutable.ListMap

object SynapseCore {
  val busDataWidth = 64
  val busByteCount = busDataWidth / 8
  val timeWindowWidth = 16
  val maxPreSpike = 1024

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
  val memAccessBusConfig = MemAccessBusConfig(
    addrWidth = log2Up(AddrMapping.cache.size) + 1,
    dataWidth = busDataWidth
  )

  object RegAddr {
    val fieldR = 0x0
    val field0 = 0x4
    val field1 = 0x8
    val field2 = 0xC
    val field3 = 0x10
    val fieldCnt = 0x14
  }

  object RegConfig {
    object Field2 {
      val refractory = 1L
      val inferenceOnly = refractory << 2
      val learning = inferenceOnly | 0x2
      val inferenceFlush = inferenceOnly | 0x1
      val learningFlush = learning | 0x1
    }

    def field0(preLen: Int, postLen: Int, postNidBase: Int): Long = {
      ((postLen / 4 - 1) << 24) | ((preLen / 4 - 1) << 16) | postNidBase
    }

    val fieldRFree = 1L << 16
  }
}

object CacheConfig {
  val size = 128 KiB
  val lines = 128
  val ways = 8
  val wordAddrWidth = log2Up(size / 8)
  val wordOffsetWidth = log2Up(size / lines / 8)
  val tagTimestampWidth = 2
  val setSize = lines / ways
  val setIndexRange = log2Up(setSize) - 1 downto 0
  val tagWidth = AER.nidWidth - setIndexRange.size
  val wayCountPerStep = 2
  val steps = ways / wayCountPerStep
  val tagRamAddrWidth = log2Up(lines / wayCountPerStep)
}

case class SynapseCsr() extends Bundle {
  val len = UInt(CacheConfig.wordOffsetWidth bits)
  val preLen = UInt(log2Up(maxPreSpike / 4) bits)
  val learning = Bool()
  val refractory = UInt(CacheConfig.tagTimestampWidth bits)
  val flush = Bool()
  val postNidBase = UInt(AER.nidWidth bits)
  val preNidBase = UInt(AER.nidWidth bits)
}

class Spike extends Bundle {
  val nid = UInt(AER.nidWidth bits)

  def tag(): UInt = nid(nid.high downto nid.high - CacheConfig.tagWidth + 1)
  def setIndex(): UInt = nid(CacheConfig.setIndexRange)
}

class SpikeEvent extends Spike {
  val cacheLineAddr = UInt(log2Up(CacheConfig.lines) bits)
  // TODO: if it's needed to add virtual spike, need change PreSpikeFetch logic
  // val virtual = Bool()

  def cacheWayLow: UInt = {
    cacheLineAddr(log2Up(CacheConfig.wayCountPerStep)-1 downto 0)
  }
  def cacheTagAddress: UInt = {
    cacheLineAddr(cacheLineAddr.high downto log2Up(CacheConfig.wayCountPerStep))
  }
}

class ExpLutQuery extends Bundle with IMasterSlave {
  val x = Vec(Flow(UInt(log2Up(SynapseCore.timeWindowWidth) bits)), 4)
  val y = Vec(SInt(16 bits), 4)
  override def asMaster(): Unit = {
    out(x)
    in(y)
  }
}

class SynapseCore extends NocCore {
  import SynapseCore.AddrMapping
  override val supportAsMemMaster = false

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
  interconnect.perfConfig()

  interconnect.addSlave(cache.io.bus, AddrMapping.cache)
  interconnect.addSlave(currentRam.io.bus, AddrMapping.current)
  interconnect.addSlave(preSpikeRam.io.bus, AddrMapping.preSpike)
  interconnect.addSlave(postSpikeRam.io.bus, AddrMapping.postSpike)
  interconnect.addSlave(ltpLut.io.bus, AddrMapping.ltpLut)
  interconnect.addSlave(ltdLut.io.bus, AddrMapping.ltdLut)

  val busConverter = Seq(synapseCtrl.io.bus, interface.dataBus).map{bus =>
    val trans = new MemAccessBusToPipeLineMemoryBus(bus.c)
    trans.io.input.cmd << bus.cmd
    trans.io.input.rsp >> bus.rsp
    trans
  }

  interconnect.addMaster(
    busConverter.head.io.output,
    Seq(cache.io.bus, currentRam.io.bus, preSpikeRam.io.bus, postSpikeRam.io.bus)
  )
  interconnect.addMaster(
    busConverter.last.io.output,
    Seq(preSpikeRam.io.bus, postSpikeRam.io.bus, ltpLut.io.bus, ltdLut.io.bus)
  )

  val regArea = new Area {
    val csr = SynapseCsr()
    val busIf = Apb3BusInterface(interface.regBus, SizeMapping(0, 256 Byte), 0, "")
    val FIELD_R = busIf.newReg("field R")
    val FIELD0  = busIf.newReg("field 0")
    val FIELD1  = busIf.newReg("field 1")
    val FIELD2  = busIf.newReg("field 2")
    val FIELD3  = busIf.newReg("field 3")
    val FIELD_CNT = busIf.newReg("spike cnt")

    FIELD_R.reserved(16 bits)
    val free = FIELD_R.field(Bool, RO, "free")
    free := synapseCtrl.io.free
    val postNidBase = FIELD0.field(UInt(16 bits), WO, "postNidBase")
    val preLen      = FIELD0.field(UInt(8 bits), WO, "preLen")
    val postLen     = FIELD0.field(UInt(7 bits), WO, "postLen")
    val neuronCoreId = FIELD1.field(UInt(4 bits), WO, "neuronCoreId")
    val managerCoreId = FIELD1.field(UInt(4 bits), WO, "managerCoreId")
    val flush      = FIELD2.field(Bool(), WO, "flush")
    val learning   = FIELD2.field(Bool, WO, "learning")
    val refractory = FIELD2.field(UInt(CacheConfig.tagTimestampWidth bits), WO, "refractory")
    val preNidBase = FIELD3.field(UInt(16 bits), WO, "preNidBase")
    val hit_cnt = FIELD_CNT.field(UInt(16 bits), RC, "hit cnt")
    val miss_cnt = FIELD_CNT.field(UInt(16 bits), RC, "miss cnt")

    csr.postNidBase := postNidBase
    csr.preLen := preLen
    csr.len := postLen
    csr.flush := flush
    csr.learning := learning
    csr.refractory := refractory
    csr.preNidBase := preNidBase

    when(synapseCtrl.spikeManager.spikeCacheManager.io.hitSpike.pull().fire){
      hit_cnt := hit_cnt + 1
    }
    when(synapseCtrl.spikeManager.missManager.io.missSpike.pull().fire){
      miss_cnt := miss_cnt + 1
    }

    busIf.accept(HtmlGenerator("synapseCore", "synapseCore"))
  }

  Seq(synapseCtrl.io.csr, synapse.io.csr).foreach( _ := regArea.csr)
  preSpikeFetch.io.learning := regArea.csr.learning

  synapseCtrl.io.aerIn <> interface.aer
  val aerOutDest = (synapseCtrl.io.aerOut.head.eventType===AER.TYPE.CURRENT)? regArea.neuronCoreId | regArea.managerCoreId
  synapseCtrl.io.aerOut.toNocInterface(aerOutDest) >> interface.localSend
  synapseCtrl.io.spikeEventDone << synapse.io.synapseEventDone
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