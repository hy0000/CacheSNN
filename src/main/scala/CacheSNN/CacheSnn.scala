package CacheSNN

import Manager.Manager
import Neuron.NeuronCore
import RingNoC.Ring
import Synapse.SynapseCore
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config}
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}

object CacheSNN {
  val externalMemoryAxi4Config = Axi4Config(
    addressWidth = 32,
    dataWidth = 64,
    idWidth = 2
  )
  val apbConfig = Apb3Config(32, 64)

  val neuronCoreId = 3
  val managerId = 4
}

class CacheSNN extends Component {
  val io = new Bundle {
    val externalMemory = master(Axi4(CacheSNN.externalMemoryAxi4Config))
    val ctrl = slave(Apb3(CacheSNN.apbConfig))
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