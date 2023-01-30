package Synapse

import CacheSNN.{AER, AerPacket}
import Synapse.SynapseCore._
import Util.{MemAccessBus, Misc}
import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

class SynapseCtrl extends Component {
  import AER.TYPE._

  val io = new Bundle {
    val csr = in(SynapseCsr())
    val aerIn = slave(new AerPacket)
    val aerOut = master(new AerPacket)
    val bus = master(MemAccessBus(SynapseCore.memAccessBusConfig))
    val spikeEvent = master(Stream(new SpikeEvent))
    val spikeEventDone = slave(Stream(new SpikeEvent))
    val free = out(Bool())
    val flushed = out(Bool())
  }

  val spikeManager = new SpikeManager
  val spikeDecoder = new SpikeDecoder
  val spikeUpdater = new SpikeUpdater

  val timestamp = Counter(CacheConfig.tagTimestampWidth bits)
  val clearCnt = Counter(io.csr.len.getWidth bits)
  val flushed = RegInit(False)

  io.flushed := flushed

  spikeManager.io.timestamp := timestamp
  spikeManager.io.csr := io.csr
  spikeManager.io.spike << spikeDecoder.io.spikeOut
  spikeManager.io.spikeEvent >> io.spikeEvent
  spikeManager.io.spikeEventDone << io.spikeEventDone
  spikeManager.io.aerOut <> io.aerOut

  spikeUpdater.io.preLen := io.csr.preLen
  spikeUpdater.io.postLen := io.csr.len

  Misc.clearIO(io)
  Misc.idleIo(spikeManager.io, spikeDecoder.io, spikeUpdater.io)

  val currentFsm = new StateMachine {
    val readCmd = new State with EntryPoint
    val head, body = new State

    readCmd.whenIsActive{
      io.bus.cmd.valid := True
      io.bus.cmd.address := AddrMapping.current.base
      io.bus.cmd.write := False
      io.bus.cmd.len := io.csr.len.resized
      when(io.bus.cmd.ready){
        goto(head)
      }
    }

    head.whenIsActive{
      io.aerOut.head.valid := True
      io.aerOut.head.nid := io.csr.postNidBase
      io.aerOut.head.eventType := CURRENT
      when(io.aerOut.head.ready){
        goto(body)
      }
    }

    body.whenIsActive{
      io.aerOut.body << io.bus.rsp
      when(io.aerOut.body.lastFire){
        exitFsm()
      }
    }
  }

  val fsm = new StateMachine {
    val currentClearInit = makeInstantEntry()
    val idle = new State
    val flush0, flush1 = new State
    val bufferSpike = new State
    val compute = new State
    val current = new StateFsm(currentFsm)
    val currentClear = new State
    val spikeUpdate = new State

    currentClearInit.whenIsActive {
      io.bus.cmd.valid := True
      io.bus.cmd.write := True
      io.bus.cmd.data := 0
      io.bus.cmd.len := io.csr.len.maxValue
      io.bus.cmd.address := AddrMapping.current.base
      when(io.bus.cmd.fire) {
        clearCnt.increment()
        when(clearCnt === io.csr.len) {
          clearCnt.clear()
          goto(idle)
        }
      }
    }

    idle.whenIsActive {
      io.free := True
      io.aerIn.head.ready := True
      when(io.aerIn.head.valid){
        flushed := False
        switch(io.aerIn.head.eventType){
          is(AER.TYPE.PRE_SPIKE){ goto(bufferSpike) }
          is(AER.TYPE.POST_SPIKE){ goto(spikeUpdate) }
        }
      }elsewhen(io.csr.flush && !flushed){
        goto(flush0)
      }
    }

    bufferSpike.whenIsActive{
      io.aerIn.body.ready := True
      spikeDecoder.io.maskSpike.valid := io.aerIn.body.valid
      spikeDecoder.io.maskSpike.payload := io.aerIn.body.fragment
      when(io.aerIn.body.lastFire){
        goto(compute)
      }
    }

    compute.whenIsActive{
      spikeManager.io.spike << spikeDecoder.io.spikeOut
      spikeManager.io.bus <> io.bus
      spikeManager.io.aerIn <> io.aerIn
      when(spikeManager.io.free && spikeDecoder.io.free){
        goto(current)
      }
    }

    current.whenCompleted{
      goto(currentClear)
    }

    currentClear.whenIsActive {
      io.bus.cmd.valid := True
      io.bus.cmd.write := True
      io.bus.cmd.data := 0
      io.bus.cmd.len := io.csr.len.resized
      io.bus.cmd.address := AddrMapping.current.base
      when(io.bus.cmd.fire) {
        clearCnt.increment()
        when(clearCnt === io.csr.len) {
          clearCnt.clear()
          goto(idle)
        }
      }
    }

    spikeUpdate.whenIsActive{
      spikeUpdater.io.run.valid := True
      spikeUpdater.io.maskSpike << io.aerIn.body.translateWith(io.aerIn.body.fragment)
      spikeUpdater.io.bus <> io.bus
      when(spikeUpdater.io.run.ready){
        goto(idle)
      }
    }

    flush0.whenIsActive{
      spikeManager.io.flush.valid := True
      spikeManager.io.bus <> io.bus
      when(spikeManager.io.flush.ready){
        goto(flush1)
      }
    }

    flush1.whenIsActive{
      spikeManager.io.bus <> io.bus
      when(spikeManager.io.free){
        flushed := True
        goto(idle)
      }
    }
  }
}

class SpikeDecoder extends Component {
  val io = new Bundle {
    val free = out Bool()
    val nidBase = in UInt(AER.nidWidth bits)
    val maskSpike = slave(Flow(Bits(busDataWidth bits)))
    val spikeOut = master(Stream(new Spike))
  }

  val maskSpikeBuffer = Mem(cloneOf(io.maskSpike.payload), maxPreSpike / busDataWidth)
  val bufferAddr = Counter(maskSpikeBuffer.wordCount)
  val bufferReadEnable = False

  val bufferReadRsp = maskSpikeBuffer.readWriteSync(
    address = bufferAddr,
    data = io.maskSpike.payload,
    write = !bufferReadEnable,
    enable = io.maskSpike.valid | bufferReadEnable
  )

  val shiftRegister = Bits(busDataWidth bits) setAsReg()
  val shiftCnt = Counter(busDataWidth)

  Misc.clearIO(io)

  val fsm = new StateMachine {
    val buffer = makeInstantEntry()
    val read, regLoad, decode = new State

    buffer.whenIsActive{
      io.free := bufferAddr===0
      when(io.maskSpike.valid){
        bufferAddr.increment()
      }
      when(bufferAddr.willOverflow){
        goto(read)
      }
    }

    read.whenIsActive{
      bufferReadEnable := True
      goto(regLoad)
    }

    regLoad.whenIsActive{
      shiftRegister := bufferReadRsp
      goto(decode)
    }

    decode.whenIsActive{
      io.spikeOut.valid := shiftRegister.lsb
      io.spikeOut.nid := (bufferAddr @@ shiftCnt) + io.nidBase
      when(!io.spikeOut.isStall){
        shiftRegister := B"0" ## shiftRegister>>1
        shiftCnt.increment()
      }
      when(shiftCnt.willOverflow){
        bufferAddr.increment()
        when(bufferAddr.willOverflow){
          goto(buffer)
        }otherwise{
          goto(read)
        }
      }
    }
  }
}

class SpikeUpdater extends Component {
  val io = new Bundle {
    val preLen = in UInt(log2Up(maxPreSpike / 4) bits)
    val postLen = in UInt(CacheConfig.wordOffsetWidth bits)
    val maskSpike = slave(Stream(Bits(busDataWidth bits)))
    val run = slave(Event) // valid start ready done
    val bus = master(MemAccessBus(memAccessBusConfig))
  }

  val lenWidth = scala.math.max(io.preLen.getWidth, io.postLen.getWidth)

  val buffer = Mem(Bits(64 bits), 1<<lenWidth)
  val bufferAddr = Counter(lenWidth bits)
  val bufferWriteEnable, bufferReadEnable = False
  val bufferDataW = B(0, 64 bits)
  io.bus.cmd.data := buffer.readWriteSync(
    address = bufferAddr.value,
    write = bufferWriteEnable,
    data = bufferDataW,
    enable = bufferWriteEnable | bufferReadEnable
  )

  val maskSpikeCnt = Counter(64 / 4)
  val spikeMask = io.maskSpike.payload.subdivideIn(4 bits)(maskSpikeCnt).asBits
  io.maskSpike.ready := maskSpikeCnt.willOverflow

  Misc.clearIO(io)

  val spikeWbFsm = new StateMachine {
    val wb0 = new State with EntryPoint
    val wb1, wb2 = new State

    wb0.whenIsActive{
      when(io.bus.cmd.isFree){
        bufferReadEnable := True
        when(bufferAddr===io.bus.cmd.len){
          goto(wb2)
        }otherwise{
          bufferAddr.increment()
          goto(wb1)
        }
      }
    }
    wb1.whenIsActive {
      io.bus.cmd.valid := True
      when(io.bus.cmd.ready){
        bufferAddr.increment()
        bufferReadEnable := True
        when(bufferAddr===io.bus.cmd.len){
          bufferAddr.clear()
          goto(wb2)
        }
      }
    }
    wb2.whenIsActive {
      io.bus.cmd.valid := True
      when(io.bus.cmd.ready){
        exitFsm()
      }
    }
  }

  val fsm = new StateMachine {
    val idle = makeInstantEntry()
    val preSpikeRead = new State
    val preSpikeShift = new State
    val preWb = new StateFsm(spikeWbFsm)
    val waitPostSpike = new State
    val postSpikeRead = new State
    val postSpikeUpdate = new State
    val postWb= new StateFsm(spikeWbFsm)
    val done = new State

    def spikeRead(len: UInt, addrBase: BigInt, next: State): Unit = {
      io.bus.cmd.valid := True
      io.bus.cmd.len := len.resized
      io.bus.cmd.address := addrBase
      when(io.bus.cmd.ready) {
        goto(next)
      }
    }

    def spikeUpdate(next: State)(action: (Bits, Int) => Bits): Unit ={
      io.bus.rsp.ready := True
      when(io.bus.rsp.valid) {
        bufferWriteEnable := True
        bufferAddr.increment()
        when(io.bus.rsp.last) {
          bufferAddr.clear()
          goto(next)
        }
      }

      for(i <- 0 until 4){
        val s = io.bus.rsp.payload(i*16, 16 bits)
        bufferDataW(i*16, 16 bits) := action(s, i)
      }
    }

    idle
      .whenIsActive{
        when(io.run.valid){
          goto(preSpikeRead)
        }
      }

    preSpikeRead
      .whenIsActive{
        spikeRead(io.preLen, AddrMapping.preSpike.base, preSpikeShift)
      }

    postSpikeRead
      .whenIsActive {
        spikeRead(io.postLen, AddrMapping.postSpike.base, postSpikeUpdate)
      }

    preSpikeShift
      .whenIsActive{
        spikeUpdate(preWb)((s, _) => (s<<1).resize(16))
      }

    postSpikeUpdate
      .whenIsActive{
        spikeUpdate(postWb)((s, i) => s.dropHigh(1) ## spikeMask(i))
        when(io.bus.rsp.fire) {
          maskSpikeCnt.increment()
        }
      }

    preWb
      .whenIsActive{
        io.bus.cmd.len := io.preLen.resized
        io.bus.cmd.write := True
        io.bus.cmd.address := AddrMapping.preSpike.base
      }
      .whenCompleted{
        goto(waitPostSpike)
      }

    postWb
      .whenIsActive{
        io.bus.cmd.len := io.postLen.resized
        io.bus.cmd.write := True
        io.bus.cmd.address := AddrMapping.postSpike.base
      }
      .whenCompleted {
        goto(done)
      }

    waitPostSpike
      .whenIsActive{
        when(io.maskSpike.valid){
          goto(postSpikeRead)
        }
      }

    done
      .whenIsActive{
        io.run.ready := True
        goto(idle)
      }
  }
}