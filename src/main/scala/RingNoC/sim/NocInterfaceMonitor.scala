package RingNoC.sim

import RingNoC.NocInterface
import spinal.core.sim._
import spinal.core._
import spinal.lib.sim.StreamReadyRandomizer
import spinal.lib._

import scala.collection.mutable

abstract class NocInterfaceMonitor(noc: Stream[Fragment[NocInterface]], clockDomain: ClockDomain){
  noc.ready #= true

  def usingReadyRandomizer(): Unit = {
    StreamReadyRandomizer(noc, clockDomain)
  }

  val packetQueue = mutable.Queue[NocPacket]()

  fork {
    while (true) {
      clockDomain.waitSamplingWhere(noc.valid.toBoolean && noc.ready.toBoolean)
      val head = noc.flit.toBigInt
      val body = mutable.Queue[BigInt]()
      while (!noc.last.toBoolean) {
        clockDomain.waitSamplingWhere(noc.valid.toBoolean && noc.ready.toBoolean)
        body.enqueue(noc.flit.toBigInt)
      }
      val p = NocPacket(head, body)
      packetQueue.enqueue(p)
    }
  }

  fork {
    while (true) {
      if(packetQueue.nonEmpty){
        onPacket(packetQueue.dequeue())
      }else{
        clockDomain.waitSampling()
      }
    }
  }

  def onPacket(p:NocPacket): Unit
}
