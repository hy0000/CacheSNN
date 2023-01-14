package CacheSNN.sim

import CacheSNN._
import Util.sim.NumberTool._
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._

import javax.swing.event.HyperlinkEvent.EventType
import scala.collection.mutable

case class AerPacketManager(aerIn: AerPacket, aerOut: AerPacket, clockDomain: ClockDomain, dim:Int, len:Int) {
  val memSpikeData = Array.tabulate(dim, len) {
    (nid, offset) => BigInt(nid) * 100000000 + offset * 10000
  }

  val aerDriver = AerDriver(aerIn, clockDomain)

  case class AerHeadSim(nid: Int, eventType: AER.TYPE.E)

  val aerHeadQueue = mutable.Queue[AerHeadSim]()
  val aerBodyQueue = mutable.Queue[BigInt]()

  StreamMonitor(aerOut.head, clockDomain) { head =>
    val headSim = AerHeadSim(head.nid.toInt, head.eventType.toEnum)
    aerHeadQueue.enqueue(headSim)
  }
  StreamMonitor(aerOut.body, clockDomain) { body =>
    aerBodyQueue.enqueue(body.fragment.toBigInt)
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
          memSpikeData(head.nid)(i) = aerBodyQueue.dequeue()
        }
      } else { // W_FETCH
        val p = AerPacketSim(0, 0, 0, AER.TYPE.W_WRITE, head.nid, memSpikeData(head.nid))
        aerDriver.sendPacket(p)
      }
    }
  }

  def sendSpike(maskSpike: Array[Int], nidBase: Int, eventType: AER.TYPE.E): Unit = {
    val raw = vToRawV(maskSpike, 1, 64)
    val head = AerHeadSim(nidBase, eventType)
    aerHeadQueue.enqueue(head)
    aerBodyQueue.enqueue(raw: _*)
  }
}