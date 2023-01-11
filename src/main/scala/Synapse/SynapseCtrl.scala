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
  }

  val spikeManager = new SpikeManager
  val spikeDecoder = new SpikeDecoder
  val spikeShifter = new SpikeShifter
  val spikeUpdater = new SpikeUpdater

  val timestamp = Counter(CacheConfig.tagTimestampWidth bits)
  val aerHeadReg = cloneOf(io.aerIn.head.payload) setAsReg()

  spikeManager.io.timestamp := timestamp
  spikeManager.io.csr := io.csr
  spikeManager.io.spike << spikeDecoder.io.spikeOut
  spikeManager.io.spikeEvent >> io.spikeEvent
  spikeManager.io.spikeEventDone << io.spikeEventDone
  spikeManager.io.aerOut <> io.aerOut

  Misc.clearIO(io)
  Misc.idleIo(spikeManager.io, spikeDecoder.io, spikeUpdater.io, spikeShifter.io)

  val currentFsm = new StateMachine {
    val readCmd = new State with EntryPoint
    val head, body = new State

    readCmd.whenIsActive{
      io.bus.cmd.valid := True
      io.bus.cmd.address := AddrMapping.postSpike.base
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
    val idle = makeInstantEntry()
    val flush = new State
    val bufferSpike = new State
    val compute = new State
    val current = new StateFsm(currentFsm)
    val preSpikeShift =new State
    val postSpikeUpdate = new State

    idle.whenIsActive {
      io.free := True
      io.aerIn.head.ready := True
      when(io.aerIn.head.valid){
        aerHeadReg := io.aerIn.head.payload
        goto(bufferSpike)
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
      when(io.csr.learning){
        goto(preSpikeShift)
      }otherwise{
        goto(idle)
      }
    }

    preSpikeShift.whenIsActive{
      spikeShifter.io.bus <> io.bus
      spikeShifter.io.run.valid := True
      when(spikeShifter.io.run.ready){
        goto(postSpikeUpdate)
      }
    }

    postSpikeUpdate.whenIsActive{
      spikeUpdater.io.bus <> io.bus
      when(spikeUpdater.io.done){
        when(io.csr.flush){
          goto(flush)
        }otherwise{
          goto(idle)
        }
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

class SpikeShifter extends Component {
  val io = new Bundle {
    val run = slave(Event) // valid start ready done
    val bus = master(MemAccessBus(memAccessBusConfig))
  }
  stub()
}

class SpikeUpdater extends Component {
  val io = new Bundle {
    val maskSpike = slave(Stream(Bits(busDataWidth bits)))
    val done = out Bool()
    val bus = master(MemAccessBus(memAccessBusConfig))
  }
  stub()
}