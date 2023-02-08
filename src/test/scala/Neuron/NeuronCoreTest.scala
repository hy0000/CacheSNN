package Neuron

import CacheSNN.CacheSnnTest._
import Util.sim.NumberTool._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.sim.StreamReadyRandomizer

import scala.util.Random

class NeuronCoreTest {

}

class NeuronComputeTest extends AnyFunSuite {
  val complied = simConfig.compile(new NeuronCompute)

  test("compute test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(10000)
      val len = 512
      val accTimes = Random.nextInt(4)
      val threadHold = 1<<16 / 8
      val current = Array.tabulate(accTimes+1, len){
        (_, _) => randomInt16 / 8
      }
      dut.io.threadHold #= threadHold
      dut.io.current.valid #= false

      val currentSum = current.transpose.map(_.sum)
      val spikes = currentSum.map(_ >= threadHold).map(booleanToInt)

      // thread for assert spike
      val spikeMonitor = fork {
        val spikeRaw = vToRawV(spikes, width = 1, 64)
        for(i <- 0 until len / 64){
          dut.clockDomain.waitSamplingWhere(dut.io.maskSpike.valid.toBoolean)
          assert(dut.io.maskSpike.address.toInt==i)
          assert(dut.io.maskSpike.data.toBigInt==spikeRaw(i))
        }
      }

      // input
      for(t <- 0 to accTimes){
        dut.io.acc #= t!=0
        dut.io.fire #= t==accTimes
        dut.io.current.valid #= true
        for(i <- 0 until len / 4){
          dut.io.current.last #= i==(len / 4 -1)
          dut.io.current.fragment #= vToRaw(current(t).slice(i*4, (i+1)*4), 16)
          dut.clockDomain.waitSampling()
        }
        dut.io.current.valid #= false
        dut.clockDomain.waitSampling(Random.nextInt(10)+3)
      }

      // assert current sum
      dut.clockDomain.waitSampling(4)
      for(i <- 0 until len / 4){
        val currentRead = getBigInt(dut.currentMem, i)
        val currentV = rawToV(currentRead, 16, 4)
        for(j <- 0 until 4){
          assert(currentV(j)==currentSum(i*4 + j))
        }
      }
      spikeMonitor.join()
    }
  }
}

class SpikeRamTest extends AnyFunSuite {
  val complied = simConfig.compile(new SpikeRam)

  test("read test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(1000)
      StreamReadyRandomizer(dut.io.readRsp, dut.clockDomain)
      dut.io.readStart #= false

      val spikes = Array.fill(8)(BigInt(64, Random))
      // write data
      for(i <- 0 until 8){
        dut.io.write.valid #= true
        dut.io.write.data #= spikes(i)
        dut.io.write.address #= i
        dut.clockDomain.waitSampling()
      }
      dut.io.write.valid #= false

      // read cmd
      fork{
        for (_ <- 0 until 4) {
          dut.io.readStart #= true
          dut.clockDomain.waitSampling()
          dut.io.readStart #= false
          dut.clockDomain.waitSamplingWhere(
            dut.io.readRsp.last.toBoolean &&
              dut.io.readRsp.valid.toBoolean &&
              dut.io.readRsp.ready.toBoolean
          )
        }
      }

      for (_ <- 0 until 4) {
        for(i <- 0 until 8){
          dut.clockDomain.waitSamplingWhere(dut.io.readRsp.valid.toBoolean && dut.io.readRsp.ready.toBoolean)
          assert(dut.io.readRsp.fragment.toBigInt==spikes(i))
        }
      }
    }
  }
}