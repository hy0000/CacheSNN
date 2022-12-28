package Synapse

import CacheSNN.AER
import Util.{MemAccessBus, Misc}
import spinal.core._
import spinal.core.sim.SimMemPimper
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

object MissAction extends SpinalEnum {
  val OVERRIDE = newElement("override")
  val REPLACE = newElement("replace")
  val WRITE_BACK = newElement("write_back")
}

case class MissSpike() extends SpikeEvent {
  val replaceNid = cloneOf(nid)
  val action: MissAction.C = MissAction()
}

class SpikeCacheManager extends Component {

  val io = new Bundle {
    val csr = in(SynapseCsr())
    val spikeIn = slave(Stream(new Spike))
    val missSpike = master(Stream(MissSpike()))
    val hitSpike = master(Stream(new SpikeEvent))
    val failSpike = master(Stream(new Spike))
    val synapseEventDone = slave(Stream(new SpikeEvent))
    val flush = slave(Event)
    val free = out Bool()
  }

  val spikeCnt = CounterUpDown(512)
  io.free := spikeCnt===0

  Misc.clearIO(io.spikeIn)
  Misc.clearIO(io.missSpike)
  Misc.clearIO(io.hitSpike)
  Misc.clearIO(io.synapseEventDone)
  Misc.clearIO(io.flush)
  Misc.clearIO(io.failSpike)

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
    val start = new State with EntryPoint
    val r, w = new State
    val wOnly = new State

    def tagClear(): Unit ={
      bus.valid := True
      bus.write := True
      bus.address := cnt.value.resized
      bus.wData := TagItem().getZero
    }

    start.whenIsActive {
      when(io.csr.learning) {
        goto(r)
      } otherwise {
        goto(wOnly)
      }
    }
    r.whenIsActive{
      bus.valid := True
      bus.write := False
      bus.address := cnt.value.resized
      goto(w)
    }
    w.whenIsActive{
      tagClear()
      when(bus.rData.valid){
        io.missSpike.valid := True
        io.missSpike.nid := 0
        io.missSpike.replaceNid := bus.rData.tag @@ setIndex
        io.missSpike.action := MissAction.WRITE_BACK
      }
      when(io.missSpike.ready) {
        cnt.increment()
        when(cnt.willOverflow){
          io.flush.ready := True
          exitFsm()
        }otherwise{
          goto(r)
        }
      }
    }
    wOnly.whenIsActive {
      tagClear()
      cnt.increment()
      when(cnt.willOverflow) {
        io.flush.ready := True
        exitFsm()
      }
    }
  }

  def tagLockUpdate(bus: TagAccessBus, address:UInt, lock: Bool, tag:UInt): Unit = {
    bus.valid := True
    bus.write := True
    bus.address := address
    bus.wData.tag := tag
    bus.wData.valid := True
    bus.wData.locked := lock
    bus.wData.timeStamp := io.csr.timestamp
  }

  case class Folder() extends Bundle {
    val hit = Bool()
    val replace = Bool()
    val available = Bool()
    val step = UInt(log2Up(CacheConfig.steps) bits)
    val wayLow = UInt(log2Up(CacheConfig.wayCountPerStep) bits)
    val tag = UInt(CacheConfig.tagWidth bits)

    def way: UInt = step @@ wayLow
    def priority: UInt = (hit ## available ## replace).asUInt
  }

  val spikeAllocateFsm = new StateMachine {
    val query0 = new State with EntryPoint
    val query1, query2 = new State
    val allocate = new State

    val step = Counter(CacheConfig.steps)
    val stepDelay = RegNext(step.value)
    val spikeIn = io.spikeIn
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

    val replaceLastWay = stepDelay===(CacheConfig.steps - 1) && !tags.last.locked

    val currentFolder = Folder()
    currentFolder.hit := False
    currentFolder.replace := False
    currentFolder.available := False
    currentFolder.wayLow := 0
    currentFolder.step := stepDelay
    currentFolder.tag := tags(currentFolder.wayLow).tag
    when(hit){
      currentFolder.wayLow := hitWay
      currentFolder.hit := True
    }elsewhen available {
      currentFolder.wayLow := availableWay
      currentFolder.available := True
    }elsewhen replace {
      currentFolder.wayLow := replaceWay
      currentFolder.replace := True
    }elsewhen replaceLastWay {
      currentFolder.wayLow := CacheConfig.wayCountPerStep - 1
      currentFolder.replace := True
    }

    val folder = Folder() setAsReg()
    val allocateFailed = folder.priority===0 && tags.last.locked

    def fsmExitOrContinue(doneCond:Bool): Unit ={
      when(doneCond){
        exitFsm()
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
        when(allocateFailed){
          // process fail spike
          io.failSpike << spikeIn
          fsmExitOrContinue(io.failSpike.ready)
        }elsewhen folder.hit {
          // process hit spike
          io.hitSpike << spikeIn.translateWith {
            val ret = new SpikeEvent
            ret.assignSomeByName(spikeIn.payload)
            ret.cacheLineAddr := spikeIn.setIndex() @@ folder.way
            ret
          }
          fsmExitOrContinue(io.hitSpike.ready)
        }otherwise{
          // process miss spike
          io.missSpike << spikeIn.translateWith {
            val ret = new MissSpike
            ret.assignSomeByName(spikeIn.payload)
            ret.cacheLineAddr := spikeIn.setIndex() @@ folder.way
            when(io.csr.learning && folder.replace){
              ret.action := MissAction.REPLACE
              ret.replaceNid := folder.tag @@ spikeIn.setIndex()
            } otherwise {
              ret.action := MissAction.OVERRIDE
              ret.replaceNid := 0
            }
            ret
          }
          fsmExitOrContinue(io.missSpike.ready)
        }
      }
      .onExit{
        when(!allocateFailed){
          spikeCnt.increment()
          tagLockUpdate(
            bus = tagBus(folder.wayLow),
            address = spikeIn.setIndex() @@ folder.step,
            lock = True,
            tag = spikeIn.tag()
          )
        }
      }
  }

  val fsm = new StateMachine{
    val idle = makeInstantEntry()
    val spikeAllocate = new StateFsm(spikeAllocateFsm)
    val spikeFree = new State
    val flush = new StateFsm(flushFsm)

    idle.whenIsActive {
      when(io.synapseEventDone.valid) {
        goto(spikeFree)
      } elsewhen io.spikeIn.valid {
        goto(spikeAllocate)
      } elsewhen io.flush.valid {
        goto(flush)
      }
    }

    Seq(spikeAllocate, flush).foreach(s => s.whenCompleted(goto(idle)))

    spikeFree
      .whenIsActive{
        io.synapseEventDone.ready := True
        tagLockUpdate(
          bus = tagBus(io.synapseEventDone.cacheWayLow),
          address = io.synapseEventDone.cacheTagAddress,
          lock = False,
          tag = io.synapseEventDone.tag()
        )
        spikeCnt.decrement()
        goto(idle)
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