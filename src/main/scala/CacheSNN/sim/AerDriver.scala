package CacheSNN.sim

import CacheSNN.AerPacket
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._

case class AerDriver(aer:AerPacket, clockDomain: ClockDomain) {
  val (headDriver, headQueue) = StreamDriver.queue(aer.head, clockDomain)
  val (bodyDriver, bodyQueue) = StreamDriver.queue(aer.body, clockDomain)

  headDriver.transactionDelay = () => 0
  bodyDriver.transactionDelay = () => 0

  def sendPacket(aerPacket: AerPacketSim): Unit ={
    headQueue.enqueue { head =>
      head.nid #= aerPacket.nid
      head.eventType #= aerPacket.eventType
    }
    for ((d, i) <- aerPacket.data.zipWithIndex) {
      bodyQueue.enqueue { body =>
        body.fragment #= d
        body.last #= (i == aerPacket.data.length - 1)
      }
    }
  }

  def sendPacket(aerPackets: Seq[AerPacketSim]): Unit = {
    for(p <- aerPackets){
      sendPacket(p)
    }
  }

  def waitDone(): Unit ={
    clockDomain.waitSamplingWhere(headQueue.isEmpty)
    clockDomain.waitSamplingWhere(bodyQueue.isEmpty)
  }
}