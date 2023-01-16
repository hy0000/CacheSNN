package CacheSNN.sim

import CacheSNN._
import Util.sim.NumberTool._
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._

import scala.collection.mutable

case class AerPacketManager(aerIn: AerPacket, aerOut: AerPacket, clockDomain: ClockDomain, dim:Int, len:Int) {
  val memSpikeData = Array.tabulate(dim, len) {
    (nid, offset) => BigInt(nid) * 100000000 + offset * 10000
  }
  val currentData = Array.fill(len)(BigInt(0))

  val aerDriver = AerDriver(aerIn, clockDomain)

  case class AerHeadSim(nid: Int, eventType: AER.TYPE.E)
  case class AerBodySim(data: BigInt, last: Boolean)

  val aerHeadQueue = mutable.Queue[AerHeadSim]()
  val aerBodyQueue = mutable.Queue[AerBodySim]()

  StreamMonitor(aerOut.head, clockDomain) { head =>
    val headSim = AerHeadSim(head.nid.toInt, head.eventType.toEnum)
    aerHeadQueue.enqueue(headSim)
  }
  StreamMonitor(aerOut.body, clockDomain) { body =>
    val bodySim = AerBodySim(body.fragment.toBigInt, body.last.toBoolean)
    aerBodyQueue.enqueue(bodySim)
  }
  StreamReadyRandomizer(aerOut.head, clockDomain)
  StreamReadyRandomizer(aerOut.body, clockDomain)

  // memory action for aerOut
  fork {
    while (true) {
      clockDomain.waitSamplingWhere(aerHeadQueue.nonEmpty)
      val head = aerHeadQueue.dequeue()
      if (head.eventType == AER.TYPE.W_WRITE) {
        clockDomain.waitSamplingWhere(aerBodyQueue.length >= len)
        for (i <- 0 until len) {
          memSpikeData(head.nid)(i) = aerBodyQueue.dequeue().data
        }
      } else if(head.eventType == AER.TYPE.W_FETCH) {
        val p = AerPacketSim(0, 0, 0, AER.TYPE.W_WRITE, head.nid, memSpikeData(head.nid))
        aerDriver.sendPacket(p)
      } else if(head.eventType == AER.TYPE.CURRENT) {
        clockDomain.waitSamplingWhere(aerBodyQueue.length >= len)
        for (i <- 0 until len) {
          currentData(i) += aerBodyQueue.dequeue().data
        }
      } else {
        clockDomain.waitSamplingWhere(aerBodyQueue.nonEmpty)
        var p =aerBodyQueue.dequeue()
        while (!p.last){
          clockDomain.waitSamplingWhere(aerBodyQueue.nonEmpty)
          p = aerBodyQueue.dequeue()
        }
      }
    }
  }

  def sendSpike(maskSpike: Array[Int], nidBase: Int, eventType: AER.TYPE.E): Unit = {
    val data = vToRawV(maskSpike, 1, 64)
    val p = AerPacketSim(0, 0, 0, eventType, nid = nidBase, data = data)
    aerDriver.sendPacket(p)
  }
}