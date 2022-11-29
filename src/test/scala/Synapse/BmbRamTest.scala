package Synapse

import CacheSNN.CacheSnnTest.simConfig
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.bmb.Bmb.Cmd.Opcode
import spinal.lib.bus.bmb.BmbParameter
import spinal.lib.sim.StreamReadyRandomizer

class BmbRamTest extends AnyFunSuite {
  val size = 8 KiB
  val p = BmbParameter(log2Up(size/8), 64, 0, 8, 3)
  val complied = simConfig.compile(new BmbRam(p, size))

  test("bmb test"){
    complied.doSim{dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.mem.read.cmd.valid #= false
      dut.io.mem.write.valid #= false
      dut.io.bmb.cmd.valid #= false
      StreamReadyRandomizer(dut.io.bmb.rsp, dut.clockDomain)
      fork{
        dut.io.bmb.cmd.opcode #= Opcode.WRITE
        for (i <- 0 until 64) {
          dut.io.bmb.cmd.valid #= true
          dut.io.bmb.cmd.context #= i
          dut.io.bmb.cmd.address #= i << p.access.wordRangeLength
          dut.io.bmb.cmd.data #= i
          dut.clockDomain.waitSamplingWhere(dut.io.bmb.cmd.ready.toBoolean)
        }
        dut.io.bmb.cmd.opcode #= Opcode.READ
        for (i <- 0 until 64) {
          dut.io.bmb.cmd.context #= i
          dut.io.bmb.cmd.valid #= true
          dut.io.bmb.cmd.address #= i << p.access.wordRangeLength
          dut.clockDomain.waitSamplingWhere(dut.io.bmb.cmd.ready.toBoolean)
        }
      }
      for(i <- 0 until 64) {
        dut.clockDomain.waitSamplingWhere(dut.io.bmb.rsp.valid.toBoolean && dut.io.bmb.rsp.ready.toBoolean)
        assert(dut.io.bmb.rsp.context.toInt==i)
      }
      for (i <- 0 until 64) {
        dut.clockDomain.waitSamplingWhere(dut.io.bmb.rsp.valid.toBoolean && dut.io.bmb.rsp.ready.toBoolean)
        assert(dut.io.bmb.rsp.context.toInt == i)
        assert(dut.io.bmb.rsp.data.toBigInt == i)
      }
    }
  }

  test("mem rw test"){
    complied.doSim{dut =>
      SimTimeout(100000)
      dut.clockDomain.forkStimulus(2)
      dut.io.mem.read.cmd.valid #= false
      dut.io.mem.write.valid #= false
      dut.io.bmb.cmd.valid #= false
      StreamReadyRandomizer(dut.io.bmb.rsp, dut.clockDomain)
      fork {
        dut.io.mem.write.valid #= true
        for (i <- 0 until 64) {
          dut.io.mem.write.address #= i
          dut.io.mem.write.data #= i
          dut.clockDomain.waitSampling()
        }
        dut.io.mem.write.valid #= false
      }
      fork {
        dut.clockDomain.waitSampling()
        dut.io.mem.read.cmd.valid #= true
        for (i <- 0 until 64) {
          dut.io.mem.read.cmd.payload #= i
          dut.clockDomain.waitSampling()
        }
        dut.io.mem.read.cmd.valid #= false
      }

      dut.clockDomain.waitSampling(dut.readDelay + 2)
      for (i <- 0 until 64) {
        assert(dut.io.mem.read.rsp.toBigInt == i)
        dut.clockDomain.waitSampling()
      }
    }
  }
}
