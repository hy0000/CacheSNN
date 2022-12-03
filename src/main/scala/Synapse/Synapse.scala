package Synapse

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline._

class SynapseCSR extends Bundle {
  val len = UInt(9 bits)
  val learning = Bool()
}

class SpikeTimeDiff extends Component {
  import SynapseCore.timeWindowWidth
  val io = new Bundle {
    val preSpike = in Bits(timeWindowWidth bits)
    val postSpike = in Vec(Bits(timeWindowWidth bits), 4)
    val ltp, ltd = out Vec(UInt(log2Up(timeWindowWidth) bits), 4)
  }

  val ohPrePreSpike = OHMasking.first(io.preSpike.dropLow(1)) ## B"0"
  val ppsMask = (ohPrePreSpike.asUInt-1).asBits | ohPrePreSpike
  val postSpikeMasked = io.postSpike.map(_ & ppsMask)
  val ohLtpPostSpike = postSpikeMasked.map{ postSpike =>
    OHMasking.roundRobin(
      postSpike.reversed, (ohPrePreSpike << 1).reversed
    ).reversed
  }
  val ohLtdPostSpike = postSpikeMasked.map{ posSpike =>
    OHMasking.first(posSpike)
  }

  val ohLtdPostSpikeStage = RegNext(ohLtdPostSpike)
  val ohLtpPostSpikeStage = RegNext(ohLtpPostSpike)

  io.ltd := Vec(ohLtdPostSpikeStage.map(s => OHToUInt(s)))
  io.ltp := Vec(ohLtpPostSpikeStage.map(s => OHToUInt(s)))
}

class Synapse extends Component {
  import SynapseCore._
  val spikeBufferAddrWidth = log2Up(AddrMapping.postSpike.size / busByteCount)
  val currentBufferAddrWidth = log2Up(AddrMapping.current.size / busByteCount)

  val io = new Bundle {
    val csr = in(new SynapseCSR)
    val synapseEvent = slave(Stream(new SynapseEvent))
    val synapseData = master(MemReadWrite(64, CacheConfig.wordAddrWidth))
    val synapseEventDone = master(Event)
    val postSpike = master(MemReadPort(Bits(64 bits), spikeBufferAddrWidth))
    val ltpQuery = master(new ExpLutQuery)
    val ltdQuery = master(new ExpLutQuery)
    val current = master(MemReadWrite(64, currentBufferAddrWidth))
  }

  val pipeline = new Pipeline {
    val LEARNING = Stageable(Bool())
    val ADDR_INCR = Stageable(UInt(io.csr.len.getWidth bits))
    val CACHE_ADDR = Stageable(cloneOf(io.synapseEvent.cacheAddr))
    val PRE_SPIKE = Stageable(Bits(16 bits))

    val POST_SPIKE = Stageable(Vec(Bits(16 bits), 4))
    val LTD_T, LTP_T = Stageable(Vec(UInt(4 bits), 4))
    val WEIGHT = Stageable(Vec(SInt(16 bits)))

    val s0 = new Stage {
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
    val s1 = new Stage(connection = M2S())
    val s2 = new Stage(connection = M2S()) {
      // calculate delta T
      val postSpike = io.postSpike.rsp.subdivideIn(timeWindowWidth bits)
      val preSpike = PRE_SPIKE
      val LTP_T = Vec(postSpike.map(_ ^ preSpike))

    }
    val s3 = new Stage(connection = M2S()) {

    }
    val s4 = new Stage(connection = M2S()) {

    }
    val s5 = new Stage(connection = M2S()) {

    }
    val s6 = new Stage(connection = M2S()) {

    }
    val s7 = new Stage(connection = M2S()) {

    }
  }
}