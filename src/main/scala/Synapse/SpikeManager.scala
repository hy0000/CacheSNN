package Synapse

import CacheSNN.{AER, AerPacket}
import Util.{MemAccessBus, Misc}
import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

class SpikeManager extends Component {
  val io = new Bundle {
    val csr = in(SynapseCsr())
    val timestamp = in UInt(CacheConfig.tagTimestampWidth bits)
    val spike = slave(Stream(new Spike))
    val spikeEvent = master(Stream(new SpikeEvent))
    val bus = master(MemAccessBus(SynapseCore.memAccessBusConfig))
    val aerIn = slave(new AerPacket)
    val aerOut = master(new AerPacket)
    val spikeEventDone = slave(Stream(new SpikeEvent))
    val flush = slave(Event)
    val free = out Bool()
  }

  val spikeCnt = CounterUpDown(1024, io.spike.fire, io.spikeEventDone.fire)

  val spikeCacheManager = new SpikeCacheManager
  val missManager = new MissSpikeManager
  val spikeQueue = StreamFifo(new Spike, 128)
  val hitQueue = StreamFifo(new SpikeEvent, 128)
  val missQueue = StreamFifo(MissSpike(), 128)
  val readyQueue = StreamFifo(new SpikeEvent, 4)
  val failSpikeStage = spikeCacheManager.io.failSpike.halfPipe()

  io.flush >> spikeCacheManager.io.flush
  io.free := missManager.io.free && spikeCnt===0 && !failSpikeStage.valid
  spikeCacheManager.io.csr := io.csr
  spikeCacheManager.io.timestamp := io.timestamp
  missManager.io.len := io.csr.len.resized

  spikeQueue.io.push << StreamArbiterFactory.lowerFirst.noLock
    .on(Seq(failSpikeStage, io.spike))
  spikeCacheManager.io.spikeIn << spikeQueue.io.pop
  spikeCacheManager.io.hitSpike >> hitQueue.io.push
  spikeCacheManager.io.missSpike >> missQueue.io.push
  missManager.io.missSpike << missQueue.io.pop
  missManager.io.readySpike >> readyQueue.io.push

  io.spikeEvent <-< StreamArbiterFactory.lowerFirst
    .on(Seq(readyQueue.io.pop, hitQueue.io.pop))

  missManager.io.bus <> io.bus
  missManager.io.aerIn <> io.aerIn
  missManager.io.aerOut <> io.aerOut
  io.spikeEventDone >> spikeCacheManager.io.synapseEventDone
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
    val timestamp = in UInt(CacheConfig.tagTimestampWidth bits)
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
    val setIndex = UInt(CacheConfig.setIndexRange.length bits)
    val step = UInt(log2Up(CacheConfig.steps) bits)
    val tagArraySel = UInt((log2Up(CacheConfig.wayCountPerStep) bits))
    (setIndex, step, tagArraySel) := cnt.value
    val bus = tagBus(tagArraySel)
    val start = new State with EntryPoint
    val r, w = new State
    val wOnly = new State

    def tagClear(): Unit ={
      bus.valid := True
      bus.write := True
      bus.address := setIndex @@ step
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
      bus.address := setIndex @@ step
      goto(w)
    }
    w.whenIsActive{
      when(bus.rData.valid){
        io.missSpike.valid := True
        io.missSpike.nid := 0
        io.missSpike.replaceNid := bus.rData.tag @@ setIndex
        io.missSpike.action := MissAction.WRITE_BACK
        io.missSpike.cacheLineAddr := setIndex @@ step @@ tagArraySel
      }
      when(io.missSpike.ready) {
        cnt.increment()
        tagClear()
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
    bus.wData.timeStamp := io.timestamp
  }

  case class Folder() extends Bundle {

    val hit = Bool()
    val replace = Bool()
    val available = Bool()
    val step = UInt(log2Up(CacheConfig.steps) bits)
    val wayLow = UInt(log2Up(CacheConfig.wayCountPerStep) bits)
    val tag = UInt(CacheConfig.tagWidth bits)
    val timeDiff = UInt(CacheConfig.tagTimestampWidth bits)

    def way: UInt = step @@ wayLow
    def priority: UInt = {
      val basePriority = hit ## available ## replace
      val priority = basePriority ## timeDiff.getZero
      when(basePriority=/=0){
        priority := basePriority ## timeDiff
      }
      priority.asUInt
    }
    def allocateFailed: Bool = !hit && !available && !replace
  }

  val spikeAllocateFsm = new StateMachine {
    val query0 = new State with EntryPoint
    val query1, query2 = new State
    val allocate = new State

    val step = Counter(CacheConfig.steps)
    val stepDelay = RegNext(step.value)
    val spikeIn = io.spikeIn
    val tags = Vec(tagBus.map(_.rData))

    val currentFolders = tags.zipWithIndex.map{ case( t, wayLow) =>
      val replaceLastWay = stepDelay===(CacheConfig.steps - 1) && !tags.last.locked && Bool(wayLow==(CacheConfig.wayCountPerStep-1))
      val ret = Folder()
      ret.timeDiff := io.timestamp - t.timeStamp
      ret.hit := t.valid && spikeIn.tag()===t.tag
      ret.replace := t.valid && (ret.timeDiff > io.csr.refractory || replaceLastWay)
      ret.available := !t.valid
      ret.step := stepDelay
      ret.tag := t.tag
      ret.wayLow := wayLow
      ret
    }

    val currentFolder = currentFolders.reduceBalancedTree{ (f0, f1) =>
      val ret = Folder()
      when(f0.priority >= f1.priority){
        ret := f0
      }otherwise{
        ret := f1
      }
      ret
    }

    val folder = Folder() setAsReg()

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
        when(folder.allocateFailed){
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
        when(!folder.allocateFailed){
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
    val aerIn = slave(new AerPacket)
    val aerOut = master(new AerPacket)
    val bus = master(MemAccessBus(SynapseCore.memAccessBusConfig))
    val missSpike = slave(Stream(MissSpike()))
    val readySpike = master(Stream(new SpikeEvent))
    val free = out Bool()
    val len = in UInt(8 bits)
  }

  val spikeQueue = StreamFifo(new SpikeEvent, 2)
  spikeQueue.io.push.valid := False
  spikeQueue.io.push.payload := io.missSpike.payload.asInstanceOf[SpikeEvent]
  spikeQueue.io.pop.ready := False

  io.bus.cmd.len := io.len
  Misc.clearIO(io)

  val cacheLineAddrOffset = log2Up(CacheConfig.size / CacheConfig.lines)

  val fsm = new StateMachine {
    val idle = makeInstantEntry()
    val decodeMissSpike = new State
    val pushSpike, popSpike = new State
    val cacheWrite = new State
    val dataHeadFetch, dataHeadWb, dataBody = new State

    idle
      .whenIsActive{
        when(spikeQueue.io.push.ready && io.missSpike.valid){
          goto(decodeMissSpike)
        }elsewhen io.aerIn.head.valid {
          io.aerIn.head.ready := True // only support inorder aer rsp
          goto(cacheWrite)
        }otherwise{
          io.free := spikeQueue.io.occupancy===0
        }
      }

    decodeMissSpike
      .whenIsActive{
        when(io.missSpike.action === MissAction.OVERRIDE) {
          goto(dataHeadFetch)
        }otherwise{
          val address = io.missSpike.cacheLineAddr << cacheLineAddrOffset
          io.bus.cmd.valid := True
          io.bus.cmd.write := False
          io.bus.cmd.address := address.resized
          when(io.bus.cmd.ready) {
            goto(dataHeadWb)
          }
        }
      }

    dataHeadFetch
      .whenIsActive{
        io.aerOut.head.valid := True
        io.aerOut.head.eventType := AER.TYPE.W_FETCH
        io.aerOut.head.nid := io.missSpike.nid
        when(io.aerOut.head.ready){
          goto(pushSpike)
        }
      }

    dataHeadWb
      .whenIsActive {
        io.aerOut.head.valid := True
        io.aerOut.head.eventType := AER.TYPE.W_WRITE
        io.aerOut.head.nid := io.missSpike.replaceNid
        when(io.aerOut.head.ready) {
          goto(dataBody)
        }
      }

    dataBody
      .whenIsActive{
        io.aerOut.body << io.bus.rsp
        when(io.bus.rsp.lastFire){
          when(io.missSpike.action === MissAction.WRITE_BACK) {
            io.missSpike.ready := True
            goto(idle)
          }otherwise{
            goto(dataHeadFetch)
          }
        }
      }

    pushSpike
      .whenIsActive {
        spikeQueue.io.push.valid := True
        io.missSpike.ready := True
        goto(idle)
      }

    cacheWrite
      .whenIsActive {
        val address = spikeQueue.io.pop.cacheLineAddr << cacheLineAddrOffset
        io.bus.cmd.arbitrationFrom(io.aerIn.body)
        io.bus.cmd.len := io.len
        io.bus.cmd.address := address.resized
        io.bus.cmd.write := True
        io.bus.cmd.data := io.aerIn.body.fragment
        when(io.aerIn.body.lastFire) {
          goto(popSpike)
        }
      }

    popSpike
      .whenIsActive {
        io.readySpike << spikeQueue.io.pop
        when(spikeQueue.io.pop.fire){
          goto(idle)
        }
      }
  }
}