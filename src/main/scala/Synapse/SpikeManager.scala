package Synapse

import CacheSNN.AER
import Util.{MemAccessBus, Misc}
import spinal.core._
import spinal.lib._
import spinal.lib.bus.bram.{BRAM, BRAMConfig}
import spinal.lib.fsm._

class SpikeManager extends Component {
  val io = new Bundle {
    val csr = in(SynapseCsr())
    val spike = slave(Stream(new Spike))
    val spikeEvent = master(Stream(new SpikeEvent))
    val bus = master(MemAccessBus(SynapseCore.memAccessBusConfig))
    val dataFill = master(Stream(new SynapseData))
    val dataWriteBack = master(Stream(new SynapseData))
    val synapseEventDone = slave(Stream(new SpikeEvent))
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

  val io = new Bundle {
    val csr = in(SynapseCsr())
    val spikeIn = slave(Stream(new Spike))
    val missSpike = master(Stream(MissSpike()))
    val hitSpike = master(Stream(new SpikeEvent))
    val synapseEventDone = slave(Stream(new SpikeEvent))
    val flush = slave(Event)
    val free = out Bool()
  }

  val spikeFifo = StreamFifo(new Spike, 512)
  val failSpike = Stream(new Spike)
  spikeFifo.io.push << StreamArbiterFactory.lowerFirst
    .on(Seq(failSpike, io.spikeIn))

  val spikeCnt = CounterUpDown(512)
  io.free := spikeCnt===0

  io.missSpike.setIdle()
  io.hitSpike.setIdle()
  io.synapseEventDone.setBlocked()
  io.flush.setBlocked()
  failSpike.setIdle()
  spikeFifo.io.pop.setBlocked()

  case class TagItem() extends Bundle {
    val valid = Bool()
    val locked = Bool()
    val tag = UInt(CacheConfig.tagWidth bits) // nid high bits
    val timeStamp = UInt(CacheConfig.tagTimestampWidth bits)
  }

  case class TagAccessBus() extends Bundle with IMasterSlave {
    val valid = Bool()
    val address = UInt(CacheConfig.tagRamAddrWidth bits)
    val write = Bool()
    val wData = TagItem()
    val rData = TagItem()

    override def asMaster() = {
      out(valid, address, write, wData)
      in(rData)
    }
  }

  // last way of per cache set is use to allocate miss spike if there are not available item to replace
  // bus address: | setIndex | step |
  val tagArray = Seq.fill(CacheConfig.wayCountPerStep)(Mem(TagItem(), CacheConfig.lines / CacheConfig.wayCountPerStep))
  val tagBus = Vec(TagAccessBus(), tagArray.length)
  for((bus, ram) <- tagBus.zip(tagArray)){
    bus.rData := ram.readWriteSync(
      address = bus.address,
      data = bus.wData,
      enable = bus.valid,
      write = bus.write
    )
    bus.valid := False
    bus.wData := TagItem().getZero
    bus.write := False
    bus.address := 0
  }

  val flushFsm = new StateMachine {
    val cnt = Counter(CacheConfig.lines)
    val step = cnt(log2Up(CacheConfig.steps) - 1 downto 0)
    val setIndex = cnt(CacheConfig.setIndexRange.high + step.getWidth downto step.getWidth)
    val tagArraySel = cnt(cnt.high downto step.getWidth + setIndex.getWidth)
    val bus = tagBus(tagArraySel)
    val r = new State with EntryPoint
    val w = new State

    bus.address := cnt.value.resized
    r.whenIsActive{
      bus.valid := True
      bus.write := False
      goto(w)
    }
    w.whenIsActive{
      when(bus.rData.valid){
        bus.valid := io.missSpike.ready
        bus.write := True
        bus.wData := TagItem().getZero
        io.missSpike.valid := True
        io.missSpike.nid := 0
        io.missSpike.replaceNid := bus.rData.tag @@ setIndex
      }
      when(io.missSpike.ready) {
        when(cnt.willOverflow){
          exitFsm()
        }otherwise{
          goto(r)
        }
      }
    }
  }

  case class Folder() extends Bundle {
    val hit = Bool()
    val replace = Bool()
    val available = Bool()
    val wayLow = UInt(log2Up(CacheConfig.wayCountPerStep) bits)

    def priority: UInt = (hit ## available ## replace).asUInt
  }

  val spikeAllocateFsm = new StateMachine {
    val query0 = new State with EntryPoint
    val query1, query2 = new State
    val allocate = new State

    val step = Counter(CacheConfig.steps)
    val stepDelay = RegNext(step.value)
    val spikeIn = spikeFifo.io.pop
    val tags = Vec(tagBus.map(_.rData))

    val hitOh = tags.map(t => t.tag===spikeIn.tag() && t.valid)
    val hit = hitOh.orR
    val hitWay = OHToUInt(OHMasking.first(hitOh))

    val availableOh = tags.map(!_.valid)
    val available = availableOh.orR
    val availableWay =  OHToUInt(OHMasking.first(availableOh))

    val replacesOh = tags.map(t => t.valid && (io.csr.timestamp - t.timeStamp) > io.csr.refractory)
    val replace = replacesOh.orR
    val replaceWay = OHToUInt(OHMasking.first(replacesOh))

    val currentFolder = Folder()
    currentFolder.hit := False
    currentFolder.replace := False
    currentFolder.available := False
    currentFolder.wayLow := CacheConfig.wayCountPerStep-1
    when(hit){
      currentFolder.wayLow := hitWay
      currentFolder.hit := True
    }elsewhen available {
      currentFolder.wayLow := availableWay
      currentFolder.available := True
    }elsewhen replace {
      currentFolder.wayLow := replaceWay
      currentFolder.replace := True
    }

    val folder = Folder() setAsReg()

    def fsmExitOrContinue(doneCond:Bool): Unit ={
      when(doneCond){
        when(io.synapseEventDone.valid){
          exitFsm()
        }otherwise{
          goto(query1)
        }
      }
    }

    Seq(query0, query1).foreach { s =>
      s.whenIsActive {
        tagBus.foreach { bus =>
          bus.address := spikeIn.setIndex() @@ step
          bus.valid := True
        }
        step.increment()
      }
    }
    Seq(query1, query2).foreach {s =>
      s.whenIsActive{
        when(currentFolder.priority > folder.priority) {
          folder := currentFolder
        }
      }
    }
    query0
      .whenIsActive{
        folder := folder.getZero
        goto(query1)
      }
    query1
      .whenIsActive{
        when(step.willOverflow){
          goto(query2)
        }
      }
    query2
      .whenIsActive {
        goto(allocate)
      }
    allocate
      .whenIsActive{
        val allocateFailed = folder.priority===0 && tags.last.locked
        when(allocateFailed){
          failSpike << spikeIn
          fsmExitOrContinue(failSpike.ready)
        }elsewhen folder.hit {
          io.hitSpike << spikeIn.translateWith {
            val ret = new SpikeEvent
            ret.assignSomeByName(spikeIn.payload)
            ret.cacheLineAddr := spikeIn.setIndex() @@ stepDelay @@ folder.wayLow
            ret
          }
          fsmExitOrContinue(io.hitSpike.ready)
        }otherwise{
          io.missSpike << spikeIn.translateWith {
            val ret = new MissSpike
            ret.assignSomeByName(spikeIn.payload)
            ret.cacheLineAddr := spikeIn.setIndex() @@ stepDelay @@ folder.wayLow
            ret.replaceNid := tags(folder.wayLow).tag @@ spikeIn.setIndex()
            ret.writeBackOnly := False
            ret
          }
          val bus = tagBus(folder.wayLow)
          bus.valid := True
          bus.write := True
          bus.wData.tag := spikeIn.tag()
          bus.wData.valid := True
          bus.wData.locked := True
          bus.wData.timeStamp := io.csr.timestamp
          fsmExitOrContinue(io.missSpike.ready)
        }
      }
  }

  val spikeFreeFsm = new StateMachine {
    val read = new State with EntryPoint
    val free = new State
  }

  val fsm = new StateMachine{
    val idle = makeInstantEntry()
    val spikeAllocate = new StateFsm(spikeAllocateFsm)
    val spikeFree = new StateFsm(spikeFreeFsm)
    val flush = new StateFsm(flushFsm)

    idle
      .whenIsActive{
        when(io.synapseEventDone.valid) {
          goto(spikeFree)
        } elsewhen io.spikeIn.valid {
          goto(spikeAllocate)
        }elsewhen io.flush.valid {
          goto(flush)
        }
      }
    Seq(spikeAllocate, spikeFree, flush).foreach{s =>
      s.whenCompleted(goto(idle))
    }
  }
}

class MissSpikeManager extends Component {
  val io = new Bundle {
    val dataIn = slave(Stream(new SynapseData))
    val dataOut = master(Stream(new SynapseData)) // data out will keep ready until a full trans done
    val bus = master(MemAccessBus(SynapseCore.memAccessBusConfig))
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