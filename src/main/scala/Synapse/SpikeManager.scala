package Synapse

import CacheSNN.AER
import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

class SpikeManager extends Component {
  val io = new Bundle {
    val csr = in(SynapseCsr())
    val spike = slave(Stream(new Spike))
    val spikeEvent = master(Stream(new SpikeEvent))
    val bus = master(MemAccessBus())
    val dataFill = master(Stream(new SynapseData))
    val dataWriteBack = master(Stream(new SynapseData))
    val synapseEventDone = slave(Stream(new Spike))
    val flush = slave(Event)
    val free = out Bool()
  }

  val spikeCacheManager = new SpikeCacheManager
  val missManager = new MissSpikeManager
  val hitQueue = StreamFifo(new SpikeEvent, 256)
  val missQueue = StreamFifo(MissSpike(), 256)
  val readyQueue = StreamFifo(new SpikeEvent, 4)

  io.flush >> spikeCacheManager.io.flush
  io.free := spikeCacheManager.io.free && missManager.io.free
  io.spike >> spikeCacheManager.io.spikeIn
  spikeCacheManager.io.hitSpike >> hitQueue.io.push
  spikeCacheManager.io.missSpike >> missQueue.io.push
  missManager.io.missSpike << missQueue.io.pop
  missManager.io.readySpike >> readyQueue.io.push

  io.spikeEvent <-< StreamArbiterFactory.lowerFirst
    .on(Seq(readyQueue.io.pop, hitQueue.io.pop))

  missManager.io.bus <> io.bus
  missManager.io.dataIn << io.dataFill
  missManager.io.dataOut >> io.dataWriteBack
  io.synapseEventDone >> spikeCacheManager.io.synapseEventDone
}

case class MissSpike() extends SpikeEvent {
  val replaceNid = cloneOf(nid)
  val writeBackOnly = Bool()
}

class SpikeCacheManager extends Component {
  val setIndexRange = log2Up(CacheConfig.lines / CacheConfig.ways) - 1 downto 0
  val tagWidth = AER.nidWidth - setIndexRange.size
  val wayCountPerStep = 2

  val io = new Bundle {
    val csr = in(SynapseCsr())
    val spikeIn = slave(Stream(new Spike))
    val missSpike = master(Stream(MissSpike()))
    val hitSpike = master(Stream(new SpikeEvent))
    val synapseEventDone = slave(Stream(new Spike))
    val flush = slave(Event)
    val free = out Bool()
  }

  val spikeFifo = StreamFifo(new Spike, 512)
  val failSpike = Stream(new Spike)
  spikeFifo.io.push << StreamArbiterFactory.lowerFirst
    .on(Seq(failSpike, io.spikeIn))

  case class TagItem() extends Bundle {
    val valid = Bool()
    val locked = Bool()
    val tag = UInt(tagWidth bits)
    val timeStamp = UInt(CacheConfig.tagTimestampWidth bits)
  }

  // last way of per cache set is use to allocate miss spike if there are not available item to replace
  val tagArray = Seq.fill(wayCountPerStep)(Mem(TagItem(), CacheConfig.lines / wayCountPerStep))
  val tagReadCmd = Vec(Stream(UInt(tagArray.head.addressWidth bits)), tagArray.length)
  val tagReadRsp = Vec(tagArray.zip(tagReadCmd).map(z => z._1.streamReadSync(z._2, z._2.payload)))
  val tagWriteCmd = Vec(tagArray.map(_.writePort()))
  tagReadCmd.foreach(_.setIdle())
  tagReadRsp.foreach(_.setBlocked())
  tagWriteCmd.foreach(_.setIdle())

  val flushFsm = new StateMachine {
    val running = new State with EntryPoint {
      val cnt = Counter(CacheConfig.lines)
      val tagArraySel = cnt(cnt.high downto cnt.high-log2Up(wayCountPerStep))
      val readCmd = tagReadCmd(tagArraySel)
      val readRsp = tagReadRsp(tagArraySel)
      readCmd.valid := True
      readCmd.payload := cnt.resized

      io.missSpike << readRsp.translateWith{
        val ret = cloneOf(io.missSpike.payload)
        ret
      }
    }
  }

  val fsm = new StateMachine{
    val idle = makeInstantEntry()
    val spikeCacheAllocate = new State
    val spikeCacheFree = new State
    val flush = new State
  }
}

class MissSpikeManager extends Component {
  val io = new Bundle {
    val dataIn = slave(Stream(new SynapseData))
    val dataOut = master(Stream(new SynapseData)) // data out will keep ready until a full trans done
    val bus = master(MemAccessBus())
    val missSpike = slave(Stream(MissSpike()))
    val readySpike = master(Stream(new SpikeEvent))
    val free = out Bool()
  }

  val outstanding = 2
  val spikeBuffer = Vec(Flow(new SpikeEvent), outstanding) setAsReg()
  spikeBuffer.foreach(_.valid init False)
  val spikeBufferOccupancy = CountOne(spikeBuffer.map(_.valid))

  io.dataIn.ready := False
  io.dataOut.valid := False
  io.bus.cmd.valid := False
  io.missSpike.ready := False
  io.readySpike.valid := False

  val fsm = new StateMachine {
    val idle = makeInstantEntry()
    val writeBack = new State
    val fill = new State

    idle
      .whenIsActive{

      }
      .whenIsActive{
        goto(fill)
      }
    fill
      .whenIsActive{

      }
  }
}