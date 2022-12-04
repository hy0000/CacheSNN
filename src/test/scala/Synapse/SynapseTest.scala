package Synapse

import CacheSNN.CacheSnnTest._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.util.Random

/*
tb generates pre-spike and post-spike from random:
* pre-pre-spike time
* ltp spike time
* ltd spike time
* is virtual spike
then input pre and post spike into dut, assert the output
 */
class SpikeTimeDiffTest extends AnyFunSuite {
  import SynapseCore.timeWindowWidth

  val nTestCase = 100
  val spikeMask = (0x1<<timeWindowWidth)-1
  val postSpikeMask = spikeMask - 1
  val deltaTUpBound = timeWindowWidth

  val complied = simConfig.compile(new SpikeTimeDiff)

  case class RandomEventSim() {
    // pre-pre-spike time
    private val ppsTime: Int = Random.nextInt(16) + 1
    private val ppsValid: Boolean = ppsTime < 16
    private val virtual: Boolean = Random.nextInt(10) <= 1

    val pps = 1 << ppsTime
    val preSpike = ((Random.nextInt() << ppsTime) | pps) & spikeMask | (if (virtual) 0 else 1)

    private val ltpTime: Seq[Int] = Seq.fill(4)(if(ppsValid) Random.nextInt(ppsTime) + 1 else Random.nextInt(15) + 1)
    private val ltdTime: Seq[Int] = ltpTime.map(t => Random.nextInt(t) + 1)

    val postSpikeFromPpsToPsExits = Seq.fill(4)(Random.nextBoolean())
    val ltdValid = postSpikeFromPpsToPsExits.map(_ && !virtual)
    val ltpValid = postSpikeFromPpsToPsExits.map(_ && ppsValid)

    val postSpike = (0 until 4).map{i =>
      if(postSpikeFromPpsToPsExits(i)){
        val ltpGapMask = ((1 << ppsTime) - (1 << ltpTime(i))) << 1
        val ltdGapMaks = (1 << ltdTime(i)) - 1
        val ltdSpike = 1 << ltdTime(i)
        val ltpSpike = 1 << ltpTime(i)
        val mask = ~(ltpGapMask | ltdGapMaks) & spikeMask
        (Random.nextInt() | ltdSpike | ltpSpike) & mask
      }else{
        val mask = ~((1<<ppsTime+1) - 1) & spikeMask
        Random.nextInt() & mask
      }
    }

    val ltpDeltaT = ltpTime.zip(ltpValid).map(z => if(z._2) ppsTime - z._1 else 0)
    val ltdDeltaT = ltdTime.zip(ltdValid).map(z => if(z._2) z._1 else 0)
  }

  def testBench(dut:SpikeTimeDiff, spikeSeq: Seq[RandomEventSim]): Unit ={
    dut.clockDomain.forkStimulus(2)
    dut.io.preSpike #= 0
    dut.io.postSpike.foreach(_ #= 0)
    dut.clockDomain.waitSampling()
    fork {
      for(spike <- spikeSeq){
        dut.io.preSpike #= spike.preSpike
        dut.clockDomain.waitSampling()
      }
    }
    fork {
      dut.clockDomain.waitSampling(dut.postSpikeInputDelay)
      for (spike <- spikeSeq){
        dut.io.postSpike.zip(spike.postSpike).foreach(z => z._1 #= z._2)
        dut.clockDomain.waitSampling()
      }
    }
    dut.clockDomain.waitSampling(dut.outputDelay)
    for(spike <- spikeSeq){
      dut.clockDomain.waitSampling()
      assert(dut.io.ltdValid.map(_.toBoolean)==spike.ltdValid)
      assert(dut.io.ltpValid.map(_.toBoolean)==spike.ltpValid)
      assert(dut.io.ltdDeltaT.map(_.toInt)==spike.ltdDeltaT)
      assert(dut.io.ltpDeltaT.map(_.toInt)==spike.ltpDeltaT)
    }
  }

  test("spike time diff test"){
    complied.doSim(555815695){ dut =>
      val spikeSeq = Seq.fill(nTestCase)(RandomEventSim())
      testBench(dut, spikeSeq)
    }
  }
}
