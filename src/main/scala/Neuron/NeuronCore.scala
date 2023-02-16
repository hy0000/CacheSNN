package Neuron

import CacheSNN.{AER, NocCore, PacketType}
import Util.{MemReadWrite, MemWriteCmd, Misc, PipelinedMemoryBusRam}
import Synapse.{Buffer, SynapseCore}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif.AccessType.WO
import spinal.lib.bus.regif.{Apb3BusInterface, HtmlGenerator}
import spinal.lib.bus.simple.PipelinedMemoryBusConfig
import spinal.lib.fsm._
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline._

/**
 * receive and acc current packet
 * send spikes to synapse core and manager core
 */
class NeuronCore extends NocCore {
  override val supportAsMemMaster = false

  case class NidMap() extends Bundle {
    val valid = Bool()
    val nid = UInt(7 bits)
    val acc = UInt(2 bits)
    val addr = UInt(2 bits)
    val src = Bits(4 bits)
    val threshold = SInt(16 bits)
    val spikeLen = UInt(4 bits)
  }

  val regArea = new Area {
    val busIf = Apb3BusInterface(interface.regBus, SizeMapping(0, 256 Byte), 0, "")
    val NidField  = busIf.newReg("nid field")
    val MapField  = busIf.newReg("map field")
    val ThresholdField0 = busIf.newReg("threshold field0")
    val ThresholdField1 = busIf.newReg("threshold field0")
    val LenField = busIf.newReg("len field")

    val nidMap = Vec(NidMap(), 4)
    for(i <- 0 until 4){
      nidMap(i).valid := NidField.field(Bool(), WO, s"valid $i").setName(s"valid_$i")
      nidMap(i).nid := NidField.field(UInt(7 bits), WO, s"nid $i").setName(s"nid_$i")
      nidMap(i).src := MapField.field(Bits(4 bits), WO, s"src $i").setName(s"src_$i")
      nidMap(i).addr := MapField.field(UInt(2 bits), WO, s"addr $i").setName(s"addr_$i")
      nidMap(i).acc := MapField.field(UInt(2 bits), WO, s"acc $i").setName(s"acc_$i")
      nidMap(i).spikeLen := LenField.field(UInt(4 bits), WO, s"spike len $i").setName(s"spike_len_$i")
    }

    val threshold0 = ThresholdField0.field(SInt(16 bits), WO, s"threshold 0")
    val threshold1 = ThresholdField0.field(SInt(16 bits), WO, s"threshold 1")
    val threshold2 = ThresholdField1.field(SInt(16 bits), WO, s"threshold 2")
    val threshold3 = ThresholdField1.field(SInt(16 bits), WO, s"threshold 3")
    Seq(threshold0, threshold1, threshold2, threshold3).zip(nidMap).foreach(z => z._2.threshold := z._1)
    busIf.accept(HtmlGenerator("NeuronCoreReg", "NeuronCore"))
  }

  val destMap = Vec(UInt(4 bits), 5)
  destMap(0) := 0
  destMap(1) := 1
  destMap(2) := 4
  destMap(3) := 5
  destMap(4) := 3 // manager core

  val accTimes = Vec(Reg(UInt(2 bits)) init 0, 4)
  val sendCnt = Counter(5)

  val currentRamSize = 4 KiB
  val dataBus = interface.dataBus.toPipeLineMemoryBus
  val currentRam = new PipelinedMemoryBusRam(dataWidth = 64, currentRamSize)
  currentRam.io.bus.cmd.arbitrationFrom(dataBus.cmd)
  currentRam.io.bus.cmd.address := dataBus.cmd.address.resized
  currentRam.io.bus.cmd.assignUnassignedByName(dataBus.cmd)
  currentRam.io.bus.rsp >> dataBus.rsp

  val neuron = new NeuronCompute
  val spikeRam = new SpikeRam
  neuron.io.maskSpike >> spikeRam.io.write
  neuron.io.cRam <> currentRam.io.mem
  Misc.idleIo(neuron.io)
  Misc.idleIo(spikeRam.io)
  idleInterface()

  val fsm = new StateMachine {
    import regArea._

    val idle = makeInstantEntry()
    val mapNid = new State
    val compute = new State
    val waitComputeDone = new StateDelay(4)
    val sendSpikeHead = new State
    val sendSpikeBody = new State

    val mapInfo = NidMap() setAsReg()
    val destMapOh = B"1" ## mapInfo.src

    idle.whenIsActive {
      interface.aer.head.ready := True
      when(interface.aer.head.valid){
        mapInfo.nid := interface.aer.head.nid.takeHigh(7).asUInt
        goto(mapNid)
      }
    }

    mapNid.whenIsActive{
      val cnt = Counter(4)
      val matched = mapInfo.nid===nidMap(cnt).nid && nidMap(cnt).valid
      when(!matched){
        cnt.increment()
      }otherwise{
        mapInfo := nidMap(cnt)
        goto(compute)
      }
    }

    compute.whenIsActive {
      neuron.io.cRamAddrBase := mapInfo.addr
      neuron.io.fire := accTimes(mapInfo.addr) === mapInfo.acc
      neuron.io.current << interface.aer.body.toFlow
      neuron.io.threadHold := mapInfo.threshold
      when(neuron.io.current.lastFire){
        goto(waitComputeDone)
      }
    }

    waitComputeDone
      .whenIsActive{
        neuron.io.cRamAddrBase := mapInfo.addr
        neuron.io.fire := accTimes(mapInfo.addr) === mapInfo.acc
        neuron.io.threadHold := mapInfo.threshold
      }
      .whenCompleted{
        when(neuron.io.fire) {
          goto(sendSpikeHead)
          accTimes(mapInfo.addr) := U"00"
        }otherwise{
          accTimes(mapInfo.addr) := accTimes(mapInfo.addr) + 1
          goto(idle)
        }
      }

    sendSpikeHead.whenIsActive {
      spikeRam.io.len := mapInfo.spikeLen.resized
      interface.localSend.valid := destMapOh(sendCnt)
      interface.localSend.setHead(
        dest = destMap(sendCnt),
        src = 2,
        custom = PacketType.AER.asBits ## B(0, 13 bits) ## AER.TYPE.POST_SPIKE.asBits ## B(0, 13 bits) ## (mapInfo.nid<<9)
      )
      when(!interface.localSend.valid || interface.localSend.ready){
        sendCnt.increment()
      }
      when(interface.localSend.fire){
        spikeRam.io.readStart := True
        goto(sendSpikeBody)
      }elsewhen sendCnt.willOverflow {
        goto(idle)
      }
    }

    sendSpikeBody.whenIsActive {
      spikeRam.io.len := mapInfo.spikeLen.resized
      interface.localSend.arbitrationFrom(spikeRam.io.readRsp)
      interface.localSend.last := spikeRam.io.readRsp.last
      interface.localSend.flit := spikeRam.io.readRsp.fragment
      when(interface.localSend.lastFire){
        when(sendCnt===0){
          goto(idle)
        }otherwise{
          goto(sendSpikeHead)
        }
      }
    }
  }
}

class NeuronCompute extends Component {
  val io = new Bundle {
    val threadHold = in SInt(16 bits)
    val fire = in Bool()
    val current = slave(Flow(Fragment(Bits(64 bits))))
    val maskSpike = master(Flow(MemWriteCmd(64, log2Up(512 / 64))))
    val cRam = master(MemReadWrite(dataWidth = 64, addrWidth = 9))
    val cRamAddrBase = in UInt(2 bits)
  }

  val N = 512 / 4
  val addrCnt = Counter(N, io.current.valid)
  when(io.current.lastFire){
    addrCnt.clear()
  }
  val spikesReg = Reg(Bits(64 bits))

  def vAdd(a: Vec[SInt], b: Vec[SInt]): Bits = {
    a.zip(b).map(z => z._1 +| z._2)
      .map(_.asBits)
      .reduce((b0, b1) => b1 ## b0)
  }

  implicit def bToS(a: Bits): Vec[SInt] = {
    Vec(a.subdivideIn(16 bits).map(_.asSInt))
  }

  implicit val pip = new Pipeline
  val ADDR = Stageable(cloneOf(io.cRam.write.address))
  val CURRENT = Stageable(Bits(64 bits))
  val SPIKE_VALID = Stageable(Bool())

  val s0 = new Stage(){
    valid := io.current.valid
    ADDR := io.cRamAddrBase @@ addrCnt.value
    CURRENT := io.current.fragment
    io.cRam.read.cmd.valid := valid
    io.cRam.read.cmd.payload := ADDR
  }

  val s1 = new Stage(connection = M2S())

  val s2 = new Stage(connection = M2S()){
    val currentOld = io.cRam.read.rsp
    overloaded(CURRENT) := vAdd(currentOld, CURRENT.asBits)
  }

  val s3 = new Stage(connection = M2S()){
    val currentFired = B(0, 64 bits)
    val spike = B(0, 4 bits)
    for(i <- 0 until 4){
      spike(i) := CURRENT(i*16, 16 bits).asSInt >= io.threadHold
      when(spike(i) && io.fire){
        currentFired(i*16, 16 bits) := 0
      }otherwise{
        currentFired(i*16, 16 bits) := CURRENT(i*16, 16 bits)
      }
    }
    overloaded(CURRENT) := currentFired
    SPIKE_VALID := valid && io.fire && ADDR (3 downto 0).andR
    when(valid && io.fire){
      spikesReg := spike ## (spikesReg >> 4)
    }
  }

  val s4 = new Stage(connection = M2S()){
    io.cRam.write.valid := valid
    io.cRam.write.address := ADDR
    io.cRam.write.data := CURRENT

    io.maskSpike.valid := SPIKE_VALID
    io.maskSpike.data := spikesReg
    io.maskSpike.address := ADDR(6 downto 4)
  }
  pip.build()
}

class SpikeRam extends Component {
  val io = new Bundle {
    val len = in UInt(3 bits)
    val write = slave(Flow(MemWriteCmd(64, log2Up(512 / 64))))
    val readStart = in Bool()
    val readRsp = master(Stream(Fragment(Bits(64 bits))))
  }

  val ram = Mem(Bits(64 bits), 512 / 64)
  ram.write(io.write.address, io.write.data, io.write.valid)

  val cnt = Counter(8)
  val reading = RegInit(False)
  val readValid = reading | io.readStart
  val last = cnt.value===io.len
  io.readRsp.valid := RegNextWhen(readValid, io.readRsp.ready, False)
  io.readRsp.fragment := ram.readSync(cnt.value, readValid && io.readRsp.ready)
  io.readRsp.last := RegNextWhen(last, io.readRsp.ready)

  reading.setWhen(io.readStart)
  when(io.readRsp.ready && readValid){
    when(last){
      reading.clear()
      cnt.clear()
    }otherwise{
      cnt.increment()
    }
  }
}