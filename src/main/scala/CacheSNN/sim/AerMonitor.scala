package CacheSNN.sim

import CacheSNN.AerPacket
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.StreamMonitor

import scala.collection.mutable

case class AerMonitor(aer:AerPacket, clockDomain: ClockDomain) {
  val packetQueue = mutable.Queue[AerPacketSim]()
  case class Body(d:BigInt, last:Boolean)
  val bodyQueue = mutable.Queue[Body]()

  StreamMonitor(aer.head, clockDomain){head =>
    val p = packetQueue.dequeue()
    assert(head.nid.toInt==p.nid)
    assert(head.eventType.toEnum==p.eventType)
  }

  StreamMonitor(aer.body, clockDomain){body =>
    val b = bodyQueue.dequeue()
    assert(body.fragment.toBigInt==b.d)
    assert(body.last.toBoolean==b.last)
  }

  def addPacket(ps: AerPacketSim*): Unit ={
    for(p <- ps){
      packetQueue.enqueue(p)
      val body = p.data.zipWithIndex.map{z =>
        Body(z._1, z._2==p.data.length-1)
      }
      bodyQueue.enqueue(body:_*)
    }
  }

  def waiteComplete(): Unit = {
    clockDomain.waitSamplingWhere(packetQueue.isEmpty && bodyQueue.isEmpty)
  }
}
