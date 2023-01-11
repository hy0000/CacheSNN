package Synapse

import CacheSNN.{AER, CacheSNN}
import RingNoC.NocInterfaceLocal
import Synapse.SynapseCore._
import Util.MemAccessBus
import Util.Misc
import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig}
import spinal.lib.fsm._

class SynapseCtrl extends Component {
  val io = new Bundle {
    val noc = slave(NocInterfaceLocal())
    val bus = master(PipelinedMemoryBus(pipeLineMemoryBusMasterConfig))
    val spikeEvent = master(Stream(new SpikeEvent))
    val spikeEventDone = in Bool()
  }
  stub()
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
    val bus = master(MemAccessBus(SynapseCore.memAccessBusConfig))
  }
  stub()
}

class SpikeUpdater extends Component {
  val io = new Bundle {
    val spike = slave(Stream(new Spike))
    val bus = master(MemAccessBus(SynapseCore.memAccessBusConfig))
  }
  stub()
}