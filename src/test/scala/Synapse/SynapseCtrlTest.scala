package Synapse

import CacheSNN.CacheSnnTest._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.simple.PipelinedMemoryBus
import spinal.lib.sim.StreamReadyRandomizer

import scala.util.Random

class MemAccessBusTest extends AnyFunSuite {

  val complied = simConfig.compile(
    new Module {
      import SynapseCore.pipeLineMemoryBusMasterConfig

      val b0 = slave(MemAccessBus())
      val b1 = master(PipelinedMemoryBus(pipeLineMemoryBusMasterConfig))
      b1 <> b0.toPipeLineMemoryBus
    }.setName("MemAccessBus")
  )

  test("MemAccessBus to PipeLineMemoryBus test"){
    complied.doSim{dut =>
      dut.clockDomain.forkStimulus(2)
      StreamReadyRandomizer(dut.b1.cmd, dut.clockDomain)
      val write = Random.nextBoolean()
      val addr = Random.nextInt(256) << 3
      val len = Random.nextInt(256)
      dut.b0.cmd.address #= addr
      dut.b0.cmd.len #= len
      dut.b0.cmd.write #= write
      dut.b0.cmd.valid #= true

      for(i <- 0 to len){
        dut.clockDomain.waitSamplingWhere(dut.b1.cmd.valid.toBoolean && dut.b1.cmd.ready.toBoolean)
        assert(dut.b1.cmd.address.toLong==addr + (i<<3))
        assert(dut.b1.cmd.write.toBoolean==write)
      }
      dut.clockDomain.waitSampling(10)
    }
  }
}

class SynapseCtrlTest {

}