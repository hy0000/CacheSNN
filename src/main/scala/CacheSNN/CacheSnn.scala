package CacheSNN

import Manager.Manager
import Neuron.NeuronCore
import RingNoC.Ring
import Synapse.SynapseCore
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

object CacheSNN {
  val axiMasterConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 64,
    idWidth = 2
  )
  val axiLiteSlaveConfig = AxiLite4Config(32, 32)

  val neuronCoreId = 3
  val managerId = 4
}

class CacheSNN extends Component {
  val io = new Bundle {
    val externalMemory = master(Axi4(CacheSNN.axiMasterConfig))
    val ctrl = slave(AxiLite4(CacheSNN.axiLiteSlaveConfig))
  }

  val synapseCores = Seq.fill(4)(new SynapseCore)
  val neuronCore = new NeuronCore
  val manager = new Manager

  val ringBus = Ring()

  val synapseCoreMapping = synapseCores.map(_.noc).zip(Seq(0, 1, 4, 5))
  ringBus.addNodes(synapseCoreMapping:_*)
  ringBus.addNode(neuronCore.noc, 2)
  ringBus.addNode(manager.noc, 3)

  manager.io.ctrl <> io.ctrl
  manager.io.externalMemory <> io.externalMemory
}

object MySpinalConfig extends SpinalConfig(
  defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = LOW),
  targetDirectory = "fpga/xilinx/CacheSNN.srcs/sources_1/new",
)

object CacheSnnVerilog extends App{
  MySpinalConfig.generateVerilog(new CacheSNN)
  SynapseCore.AddrMapping.printAddressMapping()
}