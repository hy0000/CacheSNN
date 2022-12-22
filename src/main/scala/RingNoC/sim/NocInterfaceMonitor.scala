package RingNoC.sim

import RingNoC.NocInterface
import spinal.core.sim._
import spinal.core._
import spinal.lib.sim.StreamReadyRandomizer
import spinal.lib._

import scala.collection.mutable

class NocInterfaceMonitor(noc: Stream[Fragment[NocInterface]], clockDomain: ClockDomain) {
  val monitorQueue = mutable.Queue[NocPacket]()

  def addPacket(ps: NocPacket*): Unit = {
    for (p <- ps) {
      monitorQueue.enqueue(p)
    }
  }

  def usingReadyRandomizer(): Unit = {
    StreamReadyRandomizer(noc, clockDomain)
  }

  def waiteComplete(): Unit = {
    clockDomain.waitSamplingWhere(monitorQueue.isEmpty)
  }

  noc.ready #= true
  fork {
    while (true) {
      clockDomain.waitSamplingWhere(noc.valid.toBoolean && noc.ready.toBoolean)
      val packet = monitorQueue.head
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
      monitorQueue.dequeue()
    }
  }
}
