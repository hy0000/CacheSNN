package Synapse

import CacheSNN.CacheSnnTest._
import Util.sim.NumberTool._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.sim.{FlowDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.mutable
import scala.util.Random

class SpikeDecoderTest extends AnyFunSuite {
  val complied = simConfig.compile(new SpikeDecoder)

  test("decode test"){
    complied.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val nidBase = Random.nextInt(0xFF00)
      val spikeMask = Seq.fill(SynapseCore.maxPreSpike)(Random.nextInt(2))
      val spikeMaskRaw = spikeMask.grouped(SynapseCore.busDataWidth)
        .map(g => vToRaw(g, 1))
      val validNid = spikeMask.zipWithIndex.filter(z => z._1==1)
        .map(z => z._2 + nidBase)
      val validNidQueue = mutable.Queue(validNid:_*)

      dut.io.nidBase #= nidBase
      val (_, spikeInQueue) = FlowDriver.queue(dut.io.maskSpike, dut.clockDomain)
      spikeMaskRaw.foreach(s => spikeInQueue.enqueue(_ #= s))

      StreamReadyRandomizer(dut.io.spikeOut, dut.clockDomain)
      StreamMonitor(dut.io.spikeOut, dut.clockDomain){ s =>
        val nid = validNidQueue.dequeue()
        assert(s.nid.toInt==nid)
      }

      dut.clockDomain.waitSamplingWhere(dut.io.maskSpike.valid.toBoolean)
      dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)
    }
  }
}

class SynapseCtrlTest {

}