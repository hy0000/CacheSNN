package Neuron

import CacheSNN.{AER, NocCore}
import RingNoC.NocInterfaceLocal
import Util.{Misc, MemWriteCmd}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif.AccessType.WO
import spinal.lib.bus.regif.Apb3BusInterface
import spinal.lib.fsm._
import spinal.lib.pipeline._
import spinal.lib.pipeline.Connection.M2S

/**
 * receive and acc current packet
 * send spikes to synapse core and manager core
 */
class NeuronCore extends NocCore {
  val regArea = new Area {
    val busIf = Apb3BusInterface(interface.regBus, SizeMapping(0, 256 Byte), 0, "")
    val NidField  = busIf.newReg("nid field")
    val MapField  = busIf.newReg("map field")
    val ParamField = busIf.newReg("param field")

    case class NidMap() extends Bundle {
      val valid = Bool()
      val nid = UInt(8 bits)
      val src = UInt(4 bits)
      val addr = UInt(4 bits)
    }

    val nidMap = Vec(NidMap(), 4)
    for(i <- 0 until 4){
      nidMap(i).nid := NidField.field(UInt(7 bits), WO, s"nid $i")
      nidMap(i).valid := NidField.field(Bool(), WO, s"valid $i")
      nidMap(i).src := MapField.field(UInt(4 bits), WO, s"src $i")
      nidMap(i).addr := MapField.field(UInt(4 bits), WO, s"addr $i")
    }

    val threshold = ParamField.field(SInt(16 bits), WO, s"threshold")
  }

  val neuron = new NeuronCompute
  neuron.io.acc := True
  Misc.clearIO(neuron.io)

  val addr = UInt(4 bits) setAsReg()
  val accTimesCnt = Vec(Reg(UInt(2 bits)) init 0, 4)
  val nidBase = Reg(UInt(16 bits))
  val sendCnt = Counter(5)

  /*
  val spikeRamArea = new Area {
    val ram = Mem(Bits(64 bits), 16)
    val addr = Counter(16)
    val write = False
    val read = False
    val wData = Bits(64 bits)

    val readData = ram.readWriteSync(
      address = addr,
      write = write,
      enable = write | read,
      data = wData
    )
  }

  val fsm = new StateMachine {
    import regArea._

    val idle = makeInstantEntry()
    val compute = new State
    val sendSpikeHead = new State
    val sendSpikeBody = new State

    idle.whenIsActive {
      interface.aer.head.ready := True
      when(interface.aer.head.valid){
        nidBase := interface.aer.head.nid
        switch(src){
          is(0){ addrBase := core0Addr }
          is(1){ addrBase := core1Addr }
          is(4){ addrBase := core2Addr }
          is(5){ addrBase := core3Addr }
        }
        goto(compute)
      }
    }

    compute.whenIsActive {
      neuron.io.fire := addrState(addrBase).accCnt===addrAccCnt(addrBase)
      neuron.io.acc := addrState(addrBase).accCnt===0
      neuron.io.current << interface.aer.body
      when(neuron.io.current.lastFire){
        when(neuron.io.fire){
          addrAccCnt(addrBase) := 0
          goto(sendSpikeHead)
        }otherwise{
          addrAccCnt(addrBase) := addrAccCnt(addrBase) + 1
          goto(idle)
        }
      }
    }

    sendSpikeHead.whenIsActive {
      interface.aer.head.valid := sendDestOh(sendCnt)
      interface.aer.head.eventType := AER.TYPE.PRE_SPIKE
      interface.aer.head.nid := nidBase
      when(!interface.aer.head.valid || interface.aer.head.ready){
        sendCnt.increment()
      }
      when(!interface.aer.head.fire){
        goto(sendSpikeBody)
      }
    }

    sendSpikeBody.whenIsActive {

    }
  }
   */
  stub()
}

class NeuronCompute extends Component {
  val io = new Bundle {
    val threadHold = in SInt(16 bits)
    val acc, fire = in Bool()
    val current = slave(Flow(Fragment(Bits(64 bits))))
    val maskSpike = master(Flow(MemWriteCmd(64, log2Up(512 / 64))))
  }

  val N = 512 / 4
  val currentMem = Mem(Bits(64 bits), N) simPublic()
  val addrCnt = Counter(N, io.current.valid)
  when(io.current.lastFire){
    addrCnt.clear()
  }

  def vAdd(a: Vec[SInt], b: Vec[SInt]): Bits = {
    a.zip(b).map(z => z._1 +| z._2)
      .map(_.asBits)
      .reduce((b0, b1) => b1 ## b0)
  }

  implicit def bToS(a: Bits): Vec[SInt] = {
    Vec(a.subdivideIn(16 bits).map(_.asSInt))
  }

  implicit val pip = new Pipeline
  val s0 = new Stage()
  val s1 = new Stage(connection = M2S())
  val s2 = new Stage(connection = M2S())
  val s3 = new Stage(connection = M2S())

  s0.valid := io.current.valid
  val ADDR = s0.insert(addrCnt.value)
  val CURRENT = s0.insert(io.current.fragment)

  val currentOld = currentMem.readSync(s0(ADDR), enable = io.acc)
  val currentNew = vAdd(currentOld, s1(CURRENT))
  when(!io.acc){
    currentNew := s1(CURRENT)
  }
  s1.overloaded(CURRENT) := currentNew

  currentMem.write(s2(ADDR), s2(CURRENT), s2.valid)
  val spikes = s2(CURRENT).subdivideIn(16 bits).map{current =>
    current.asSInt >= io.threadHold
  }

  val spikesReg = Reg(Bits(64 bits))
  when(s2.valid && io.fire){
    spikesReg := spikes.asBits() ## (spikesReg >> 4)
  }
  val SPIKE_VALID = s2.insert(s2(ADDR)(3 downto 0).andR && io.fire)
  io.maskSpike.valid := s3.valid && s3(SPIKE_VALID)
  io.maskSpike.address := s3(ADDR)(6 downto 4)
  io.maskSpike.data := spikesReg
  pip.build()
}