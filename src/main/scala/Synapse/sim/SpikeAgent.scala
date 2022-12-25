package Synapse.sim

import Synapse.{CacheConfig, MissSpike, Spike, SpikeEvent}
import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.sim._

import scala.collection.mutable

class SpikeSim(val nid: Int){
  def setIndex():Int = nid & ((1<<CacheConfig.setIndexRange.size) - 1)
  def tag():Int = nid>>CacheConfig.setIndexRange.size
}

class SpikeEventSim(nid: Int,
                    val cacheAddr: Int) extends SpikeSim(nid)

class MissSpikeSim(nid: Int,
                   cacheAddr: Int,
                   val replaceNid: Int,
                   val writeBackOnly: Boolean) extends SpikeEventSim(nid, cacheAddr)


class SpikeDriver[S<:Spike](spikeStream: Stream[S], clockDomain: ClockDomain) {
  val (driver, queue) = StreamDriver.queue(spikeStream, clockDomain)

  def sendSpike[SS<:SpikeSim](s:SS): Unit ={
    queue.enqueue{sPort =>
      sPort.nid #= s.nid
      if (sPort.isInstanceOf[SpikeEvent]) {
        val sp = sPort.asInstanceOf[SpikeEvent]
        sp.cacheLineAddr #= s.asInstanceOf[SpikeEventSim].cacheAddr
      }
      if (sPort.isInstanceOf[MissSpike]) {
        val sp = sPort.asInstanceOf[MissSpike]
        sp.replaceNid #= s.asInstanceOf[MissSpikeSim].replaceNid
        sp.writeBackOnly #= s.asInstanceOf[MissSpikeSim].writeBackOnly
      }
    }
  }

  def sendSpike[SS<:SpikeSim](ss:Seq[SS]): Unit ={
    for(s <- ss){
      sendSpike(s)
    }
  }
}

class SpikeMonitor[S<:Spike](spikeStream: Stream[S], clockDomain: ClockDomain) {
  val queue = mutable.Queue[SpikeSim]()

  StreamMonitor(spikeStream, clockDomain) { s =>
    val spike = queue.dequeue()
    assert(s.nid.toInt == spike.nid)
  }

  def addSpike(s: SpikeSim): Unit = {
    queue.enqueue(s)
  }

  def addSpike(ss: Seq[SpikeSim]): Unit = {
    for (s <- ss) {
      addSpike(s)
    }
  }

  def waitComplete(): Unit ={
    clockDomain.waitSamplingWhere(queue.isEmpty)
  }
}