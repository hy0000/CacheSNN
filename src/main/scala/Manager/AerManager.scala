package Manager

import CacheSNN._
import Util.Misc
import RingNoC.NocInterface
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm._

/*
  nid[15:10]: nidBase
  nid[9:0]: nidOffset
  addr = (addrBase + nidOffset) ## 10'B0
 */
case class NidMap() extends Bundle {
  val valid = Bool()
  val nidBase = UInt(6 bits)
  val len = UInt(8 bits)
  val addrBase = UInt(22 bits)
  val dest = UInt(4 bits)
}

case class PostNidMap() extends Bundle {
  val nidBase = UInt(6 bits)
  val valid = Bool()
  val len = UInt(4 bits)
}

case class PreSpikeCmd() extends Bundle {
  val dest = UInt(4 bits)
  val nidBase = UInt(6 bits)
  val addrBase = UInt(22 bits)
}

class AerManager extends Component {
  val io = new Bundle {
    val nidMap = in Vec(NidMap(), 4)
    val postNidMap = in Vec(PostNidMap(), 4)
    val postAddrBase = in UInt(32 bits)
    val nidEpochDone = out Vec(Bool(), 4)
    val preSpikeCmd = slave(Stream(PreSpikeCmd()))
    val aer = slave(new AerPacket)
    val axi = master(Axi4(CacheSNN.axiMasterConfig.copy(idWidth = 1)))
    val localSend = master(NocInterface())
  }

  Misc.setAxiMasterDefault(io.axi, id = 1)
  Misc.clearIO(io)

  case class WeightAck() extends Bundle {
    val nid = UInt(16 bits)
    val dest = UInt(4 bits)
  }

  val wFetchCmdFifo = StreamFifo(WeightAck(), 8)
  val wWriteCmdFifo = StreamFifo(Bits(1 bits), 8)
  wWriteCmdFifo.io.push.payload := 0
  Misc.idleIo(wFetchCmdFifo.io, wWriteCmdFifo.io)

  val preSpikeFsm = new StateMachine {
    val axiReadCmd = new State with EntryPoint
    val aerHead = new State
    val aerBody = new State

    axiReadCmd.whenIsActive{
      io.axi.readCmd.valid := True
      io.axi.readCmd.len := 15
      io.axi.readCmd.addr := io.preSpikeCmd.addrBase @@ U(0, 10 bits)
      when(io.axi.readCmd.ready){
        goto(aerHead)
      }
    }

    aerHead.whenIsActive{
      val aerHead = new AerPacketHead
      aerHead.eventType := AER.TYPE.PRE_SPIKE
      aerHead.nid := io.preSpikeCmd.nidBase @@ U(0, 10 bits)
      io.localSend.valid := True
      io.localSend.setHead(
        dest = io.preSpikeCmd.dest,
        src = 0,
        custom = aerHead.toAerCustomField
      )
      when(io.localSend.valid){
        goto(aerBody)
      }
    }

    aerBody.whenIsActive{
      io.localSend.arbitrationFrom(io.axi.readRsp)
      io.localSend.flit := io.axi.readRsp.data
      io.localSend.last := io.axi.readRsp.last
      when(io.localSend.lastFire){
        io.preSpikeCmd.ready := True
        exitFsm()
      }
    }
  }

  val weightFsm = new StateMachine {
    val idle = new State with EntryPoint
    val nidMatch = new State
    val bufferFetchCmd, bufferWriteCmd = new State
    val axiReadCmd, axiWriteCmd = new State

    val matchCnt = Counter(4)
    val matchedNidMap = Reg(NidMap())
    val isFetch = io.aer.head.eventType===AER.TYPE.W_FETCH
    val isWrite = io.aer.head.eventType===AER.TYPE.W_WRITE
    val nidOffset = io.aer.head.nid(9 downto 0)
    val axiAddr = (matchedNidMap.addrBase + nidOffset) @@ U(0, 10 bits)

    idle.whenIsActive{
      when(io.aer.head.valid){
        when((isFetch || isWrite) && !io.preSpikeCmd.valid){
          goto(nidMatch)
        }elsewhen(wFetchCmdFifo.io.occupancy===0 && wWriteCmdFifo.io.occupancy===0){
          exitFsm()
        }
      }
    }

    nidMatch.whenIsActive{
      matchCnt.increment()
      val nidBase = io.aer.head.nid(15 downto 10)
      val nidMap = io.nidMap(matchCnt)
      when(nidBase===nidMap.nidBase && nidMap.valid){
        matchedNidMap := nidMap
        when(isFetch){
          goto(bufferFetchCmd)
        }otherwise{
          goto(bufferWriteCmd)
        }
        matchCnt.clear()
      }
    }

    bufferFetchCmd.whenIsActive{
      wFetchCmdFifo.io.push.valid := True
      wFetchCmdFifo.io.push.nid := io.aer.head.nid
      wFetchCmdFifo.io.push.dest := matchedNidMap.dest
      when(wFetchCmdFifo.io.push.ready){
        goto(axiReadCmd)
      }
    }

    bufferWriteCmd.whenIsActive{
      wWriteCmdFifo.io.push.valid := True
      when(wWriteCmdFifo.io.push.ready) {
        goto(axiWriteCmd)
      }
    }

    axiReadCmd.whenIsActive {
      io.axi.readCmd.valid := True
      io.axi.readCmd.addr := axiAddr
      io.axi.readCmd.len := matchedNidMap.len
      when(io.axi.readCmd.ready){
        io.aer.head.ready := True
        goto(idle)
      }
    }

    axiWriteCmd.whenIsActive {
      io.axi.writeCmd.valid := True
      io.axi.writeCmd.addr := axiAddr
      io.axi.writeCmd.len := matchedNidMap.len
      when(io.axi.writeCmd.ready) {
        io.aer.head.ready := True
        goto(idle)
      }
    }
  }

  val postSpikeFsm = new StateMachine {
    val nidMatch = new State with EntryPoint
    val axiWriteCmd, axiWriteBody, axiWriteAck = new State

    val matchCnt = Counter(4)
    val postNidMap = io.postNidMap(matchCnt)
    val axiAddr = io.postAddrBase + (matchCnt.value<<(log2Up(512 / 8)))

    nidMatch.whenIsActive {
      val nidBase = io.aer.head.nid(15 downto 10)
      when(nidBase === postNidMap.nidBase && postNidMap.valid) {
        io.aer.head.ready := True
        goto(axiWriteCmd)
      }otherwise{
        matchCnt.increment()
      }
    }

    axiWriteCmd.whenIsActive {
      io.axi.writeCmd.valid := True
      io.axi.writeCmd.len := postNidMap.len.resized
      io.axi.writeCmd.addr := axiAddr
      when(io.axi.writeCmd.ready){
        goto(axiWriteBody)
      }
    }

    axiWriteBody.whenIsActive{
      io.axi.w.arbitrationFrom(io.aer.body)
      io.axi.w.data := io.aer.body.fragment
      io.axi.w.last := io.aer.body.last
      when(io.aer.body.lastFire){
        goto(axiWriteAck)
      }
    }

    axiWriteAck.whenIsActive {
      io.axi.b.ready := True
      when(io.axi.b.valid){
        io.nidEpochDone(matchCnt) := True
        exitFsm()
      }
    }
  }

  val fsm = new StateMachine {
    val idle = makeInstantEntry()
    val weight = new StateFsm(weightFsm)
    val preSpike = new StateFsm(preSpikeFsm)
    val postSpike = new StateFsm(postSpikeFsm)

    idle.whenIsActive{
      when(io.preSpikeCmd.valid){
        goto(preSpike)
      }elsewhen io.aer.head.valid {
        when(io.aer.head.eventType===AER.TYPE.POST_SPIKE){
          io.aer.head.ready := False
          goto(postSpike)
        }otherwise{
          goto(weight)
        }
      }
    }

    weight.whenIsActive {
      io.axi.w.arbitrationFrom(io.aer.body)
      io.axi.w.data := io.aer.body.fragment
      io.axi.w.last := io.aer.body.last

      io.axi.b.ready := wWriteCmdFifo.io.pop.valid
      wWriteCmdFifo.io.pop.ready := io.axi.b.valid

      val nocHead = RegInit(True) clearWhen wFetchCmdFifo.io.pop.fire setWhen io.localSend.lastFire
      when(nocHead){
        val aerHead = new AerPacketHead
        aerHead.eventType := AER.TYPE.W_WRITE
        aerHead.nid := wFetchCmdFifo.io.pop.nid
        io.localSend.arbitrationFrom(wFetchCmdFifo.io.pop)
        io.localSend.setHead(
          dest = wFetchCmdFifo.io.pop.dest,
          src = 0,
          custom = aerHead.toAerCustomField
        )
      }otherwise{
        io.localSend.arbitrationFrom(io.axi.readRsp)
        io.localSend.flit := io.axi.readRsp.data
        io.localSend.last := io.axi.readRsp.last
      }
    }

    Seq(preSpike, weight, postSpike).foreach{ s =>
      s.whenCompleted(goto(idle))
    }
  }
}
