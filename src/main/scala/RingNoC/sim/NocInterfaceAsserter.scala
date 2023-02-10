package RingNoC.sim

import RingNoC.NocInterface
import spinal.core.sim._
import spinal.core._
import spinal.lib._

import scala.collection.mutable

class NocInterfaceAsserter(noc: Stream[Fragment[NocInterface]], clockDomain: ClockDomain) {
  val monitorQueue = Array.fill(16)(mutable.Queue[NocPacket]())

  def addPacket(ps: NocPacket*): Unit = {
    for (p <- ps) {
      monitorQueue(p.src).enqueue(p)
    }
  }

  def addPacket(ps: Iterable[NocPacket]): Unit = {
    for (p <- ps) {
      addPacket(p)
    }
  }

  def waitComplete(): Unit = {
    for (pq <- monitorQueue) {
      clockDomain.waitSamplingWhere(pq.isEmpty)
    }
  }

  noc.ready #= true
  fork {
    while (true) {
      clockDomain.waitSamplingWhere(noc.valid.toBoolean && noc.ready.toBoolean)
      val src = ((noc.flit.toBigInt>>52) & 0xF).toInt
      val packet = monitorQueue(src).head
      // assert head
      assert(noc.flit.toBigInt == packet.head, s"${noc.flit.toBigInt.toString(16)} ${packet.head.toString(16)}")
      if (packet.headOnly) {
        assert(noc.last.toBoolean)
      } else {
        assert(!noc.last.toBoolean)
        // assert body
        for ((data, i) <- packet.data.zipWithIndex) {
          clockDomain.waitSamplingWhere(noc.valid.toBoolean && noc.ready.toBoolean)
          assert(noc.flit.toBigInt == data, s"${noc.flit.toBigInt.toString(16)} ${data.toString(16)}")
          assert(noc.last.toBoolean == (i == packet.data.length - 1))
        }
      }
      monitorQueue(src).dequeue()
    }
  }
}