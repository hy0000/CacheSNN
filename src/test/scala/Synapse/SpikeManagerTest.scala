package Synapse

import CacheSNN.CacheSnnTest._
import Synapse.sim._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.sim.StreamReadyRandomizer


class SpikeManagerTest extends AnyFunSuite {

}

class SpikeCacheManagerTest extends AnyFunSuite {

  val complied = simConfig.compile(new SpikeCacheManager)

  case class SpikeCacheManagerAgent(spikeInDriver: SpikeDriver[Spike],
                                    spikeDoneDriver: SpikeDriver[SpikeEvent],
                                    missSpikeMonitor: SpikeMonitor[MissSpike],
                                    hitSpikeMonitor: SpikeMonitor[SpikeEvent])

  case class TagItemSim(valid:Boolean = false,
                        tag:Int = 0,
                        locked:Boolean = false,
                        timestamp:Int = 0)

  def initDut(dut:SpikeCacheManager): SpikeCacheManagerAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(10000)
    dut.io.csr.learning #= false
    dut.io.csr.refractory #= 1
    dut.io.csr.timestamp #= 1

    StreamReadyRandomizer(dut.io.missSpike, dut.clockDomain)
    StreamReadyRandomizer(dut.io.hitSpike, dut.clockDomain)
    dut.io.missSpike.ready #= true
    dut.io.hitSpike.ready #= true

    val spikeInDriver = new SpikeDriver(dut.io.spikeIn, dut.clockDomain)
    val spikeDoneDriver = new SpikeDriver(dut.io.synapseEventDone, dut.clockDomain)
    val missSpikeMonitor = new SpikeMonitor(dut.io.missSpike, dut.clockDomain)
    val hitSpikeMonitor = new SpikeMonitor(dut.io.hitSpike, dut.clockDomain)
    // flush
    dut.io.flush.valid #= true
    dut.clockDomain.waitSamplingWhere(dut.io.flush.ready.toBoolean)
    dut.io.flush.valid #= false
    dut.io.csr.learning #= true

    SpikeCacheManagerAgent(spikeInDriver, spikeDoneDriver, missSpikeMonitor, hitSpikeMonitor)
  }

  test("allocate test"){
    complied.doSim {dut =>
      val agent = initDut(dut)
      val spike = (0 until CacheConfig.lines).map(nid => new SpikeSim(nid))
      agent.spikeInDriver.sendSpike(spike)
      agent.missSpikeMonitor.addSpike(spike)
      agent.missSpikeMonitor.waitComplete()
    }
  }

  test("spike free then hit test"){
    complied.doSim {dut =>
      val agent = initDut(dut)
      val spike = (0 until CacheConfig.lines).map(nid => new SpikeSim(nid))
      agent.spikeInDriver.sendSpike(spike)
      agent.missSpikeMonitor.addSpike(spike)

      spike.foreach{s =>
        dut.clockDomain.waitSamplingWhere(dut.io.missSpike.valid.toBoolean && dut.io.missSpike.ready.toBoolean)
        val cacheAddr = dut.io.missSpike.cacheLineAddr.toInt
        val spikeEvent = new SpikeEventSim(nid = s.nid, cacheAddr = cacheAddr)
        agent.spikeDoneDriver.sendSpike(spikeEvent)
      }
      dut.clockDomain.waitSamplingWhere(dut.io.free.toBoolean)

      agent.spikeInDriver.sendSpike(spike)
      agent.hitSpikeMonitor.addSpike(spike)
      agent.hitSpikeMonitor.waitComplete()
    }
  }

  test("fail spike test") {
    complied.doSim { dut =>
      val agent = initDut(dut)
      val spike = (0 to CacheConfig.ways).map(w => new SpikeSim(w<<CacheConfig.setIndexRange.size))
      agent.spikeInDriver.sendSpike(spike)
      agent.missSpikeMonitor.addSpike(spike.take(CacheConfig.ways))
      agent.missSpikeMonitor.waitComplete()
      dut.clockDomain.waitSamplingWhere(dut.io.failSpike.valid.toBoolean)
      assert(dut.io.failSpike.nid.toInt==CacheConfig.ways<<CacheConfig.setIndexRange.size)
    }
  }
}