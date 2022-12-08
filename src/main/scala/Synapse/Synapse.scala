package Synapse

import spinal.core._
import spinal.lib._
import spinal.lib.fsm.{State, StateDelay, StateMachine}
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline._

class SynapseCSR extends Bundle {
  val len = UInt(9 bits)
  val learning = Bool()
}

class SynapseEvent extends SpikeEvent {
  val preSpike = Bits(SynapseCore.timeWindowWidth bits)
}

class SpikeTimeDiff extends Component {
  import SynapseCore.timeWindowWidth
  def postSpikeInputDelay = 1
  def outputDelay = 2

  val io = new Bundle {
    val preSpike = in Bits(timeWindowWidth bits)
    val postSpike = in Vec(Bits(timeWindowWidth bits), 4)
    val ltpDeltaT, ltdDeltaT = out Vec(UInt(log2Up(timeWindowWidth) bits), 4)
    val ltpValid, ltdValid = out Vec(Bool(), 4)
  }

  // pps: pre pre spike
  case class Pps() extends Bundle {
    val time = UInt(log2Up(timeWindowWidth) bits)
    val exist = Bool()
    val mask = Bits(timeWindowWidth bits)
  }

  val pps = Pps()
  val ppsOh = OHMasking.first(io.preSpike.dropLow(1)) ## B"0"
  val ppsExist = io.preSpike.dropLow(1).orR
  pps.time := OHToUInt(ppsOh)
  pps.exist := ppsExist
  pps.mask := (ppsOh.asUInt - 1).asBits | ppsOh

  val ppsReg = RegNext(pps)
  val virtual = RegNext(!io.preSpike.lsb)

  val postSpikeMasked = io.postSpike.map(_ & ppsReg.mask)
  val ohLtpPostSpike = postSpikeMasked.map(s => OHMasking.last(s))
  val ohLtdPostSpike = postSpikeMasked.map(s => OHMasking.first(s))

  val ltpValid = Vec(ohLtpPostSpike.map(_.orR && ppsReg.exist))
  val ltdValid = Vec(ohLtdPostSpike.map(_.orR && !virtual))
  val ltpDeltaT = Vec(ohLtpPostSpike.map(s => ppsReg.time - OHToUInt(s)))
  val ltdDeltaT = Vec(ohLtdPostSpike.map(s => OHToUInt(s)))

  ltpValid.zip(ltpDeltaT).foreach(z => when(!z._1) {z._2 := 0})
  ltdValid.zip(ltdDeltaT).foreach(z => when(!z._1) {z._2 := 0})

  io.ltpValid := RegNext(ltpValid)
  io.ltdValid := RegNext(ltdValid)
  io.ltpDeltaT := RegNext(ltpDeltaT)
  io.ltdDeltaT := RegNext(ltdDeltaT)
}

class Synapse extends Component {
  import SynapseCore._
  val spikeBufferAddrWidth = log2Up(AddrMapping.postSpike.size / busByteCount)
  val currentBufferAddrWidth = log2Up(AddrMapping.current.size / busByteCount)
  def currentWriteBackDelay = 7
  def weightWriteBackDelay = 6

  val io = new Bundle {
    val csr = in(new SynapseCSR)
    val synapseEvent = slave(Stream(new SynapseEvent))
    val synapseData = master(MemReadWrite(64, CacheConfig.wordAddrWidth))
    val synapseEventDone = out Bool()
    val postSpike = master(MemReadPort(Bits(64 bits), spikeBufferAddrWidth))
    val ltpQuery = master(new ExpLutQuery)
    val ltdQuery = master(new ExpLutQuery)
    val current = master(MemReadWrite(64, currentBufferAddrWidth))
  }

  val spikeTimeDiff = new SpikeTimeDiff

  def vAdd(a:Vec[SInt], b:Vec[SInt]): Bits = {
    a.zip(b).map(z => z._1 +| z._2)
      .map(_.asBits)
      .reduce((b0, b1) => b1 ## b0)
  }

  implicit def bToS(a:Bits): Vec[SInt] = {
    Vec(a.subdivideIn(16 bits).map(_.asSInt))
  }

  val pipeline = new Pipeline {
    val LEARNING = Stageable(Bool())
    val ADDR_INCR = Stageable(UInt(io.csr.len.getWidth bits))
    val CACHE_ADDR = Stageable(cloneOf(io.synapseEvent.cacheAddr))
    val PRE_SPIKE = Stageable(Bits(16 bits))

    val DELTA_WEIGHT, WEIGHT, CURRENT = Stageable(Bits(64 bits))

    val s0 = new Stage {
      valid := io.synapseEvent.valid
      // address bursting and send post spike read cmd
      val addrIncr = Counter(io.csr.len.getWidth bits, io.synapseEvent.valid)
      io.synapseEvent.ready := False
      when(addrIncr.willIncrement && addrIncr.value===io.csr.len){
        addrIncr.clear()
        io.synapseEvent.ready := True
      }

      LEARNING := io.csr.learning
      ADDR_INCR := addrIncr.value
      CACHE_ADDR := io.synapseEvent.cacheAddr + addrIncr.value
      PRE_SPIKE := io.synapseEvent.preSpike

      io.postSpike.cmd.valid := io.csr.learning && io.synapseEvent.valid
      io.postSpike.cmd.payload := addrIncr.value
    }

    val s1 = new Stage(connection = M2S()) {
      spikeTimeDiff.io.preSpike := PRE_SPIKE

      io.synapseData.read.cmd.valid := valid
      io.synapseData.read.cmd.payload := CACHE_ADDR
    }

    val s2 = new Stage(connection = M2S()) {
      val postSpike = io.postSpike.rsp.subdivideIn(timeWindowWidth bits)
      spikeTimeDiff.io.postSpike := postSpike
    }

    val s3 = new Stage(connection = M2S()){
      io.ltpQuery.x.zip(spikeTimeDiff.io.ltpDeltaT).foreach(z => z._1.payload := z._2)
      io.ltdQuery.x.zip(spikeTimeDiff.io.ltdDeltaT).foreach(z => z._1.payload := z._2)
      io.ltpQuery.x.zip(spikeTimeDiff.io.ltpValid).foreach(z => z._1.valid := z._2)
      io.ltdQuery.x.zip(spikeTimeDiff.io.ltdValid).foreach(z => z._1.valid := z._2)
    }

    val s4 = new Stage(connection = M2S()) {
      DELTA_WEIGHT := vAdd(io.ltpQuery.y, io.ltdQuery.y)
      WEIGHT := io.synapseData.read.rsp

      io.current.read.cmd.valid := valid
      io.current.read.cmd.payload := ADDR_INCR.resized
    }

    val s5 = new Stage(connection = M2S()) {
      overloaded(WEIGHT) := vAdd(WEIGHT.asBits, DELTA_WEIGHT.asBits)
    }

    val s6 = new Stage(connection = M2S()) {
      io.synapseData.write.valid := valid
      io.synapseData.write.data := WEIGHT
      io.synapseData.write.address := CACHE_ADDR

      CURRENT := vAdd(io.current.read.rsp, WEIGHT.asBits)
    }

    val s7 = new Stage(connection = M2S()) {
      io.current.write.valid := valid
      io.current.write.address := ADDR_INCR.resized
      io.current.write.data := CURRENT
      io.synapseEventDone := valid
    }
  }
  pipeline.build()
}

class PreSpikeFetch extends Component {
  import SynapseCore._
  val spikeBufferAddrWidth = log2Up(AddrMapping.preSpike.size / busByteCount)
  val io = new Bundle {
    val learning = in Bool()
    val spikeEvent = slave(Stream(new SpikeEvent))
    val preSpike = master(MemReadWrite(busDataWidth, spikeBufferAddrWidth))
    val synapseEvent = master(Stream(new SynapseEvent))
  }

  val synapseEvent = cloneOf(io.synapseEvent)
  io.synapseEvent <-/< synapseEvent

  val fsm = new StateMachine{
    val spikeRead = makeInstantEntry()
    val spikeRsp = new StateDelay(2)
    val spikeSend = new State
    val spikeUpdate = new State

    io.spikeEvent.ready := False
    synapseEvent.valid := False
    io.preSpike.read.cmd.valid := False
    io.preSpike.write.valid := False

    val rspReg = cloneOf(io.preSpike.read.rsp) setAsReg()
    val preSpikes = rspReg.subdivideIn(timeWindowWidth bits)
    val nidLow = io.spikeEvent.nid(1 downto 0).asBits.asUInt
    val address = io.spikeEvent.nid(10 downto 2)
    val preSpikesUpdated = Vec(preSpikes.zipWithIndex.map { case (s, i) =>
      s(s.high downto 1) ## (s.lsb | i===nidLow)
    })
    synapseEvent.preSpike := preSpikesUpdated(nidLow)
    when(!io.learning){
      synapseEvent.preSpike := 0
    }
    synapseEvent.assignUnassignedByName(io.spikeEvent)
    io.preSpike.write.data := preSpikesUpdated.reduce((a, b) => b ## a)
    io.preSpike.write.address := address
    io.preSpike.read.cmd.payload := address

    spikeRead
      .whenIsActive{
        when(io.spikeEvent.valid){
          when(io.learning){
            io.preSpike.read.cmd.valid := True
            goto(spikeRsp)
          }otherwise{
            goto(spikeSend)
          }
        }
      }
    spikeRsp
      .onExit{
        rspReg := io.preSpike.read.rsp
      }
      .whenCompleted(goto(spikeUpdate))
    spikeUpdate
      .whenIsActive{
        io.preSpike.write.valid := True
        goto(spikeSend)
      }
    spikeSend
      .whenIsActive{
        synapseEvent.valid := True
        when(synapseEvent.ready){
          io.spikeEvent.ready := True
          goto(spikeRead)
        }
      }
  }
}