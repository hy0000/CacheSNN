package RingNoC.sim

import RingNoC.NocInterface
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamReadyRandomizer}

import scala.collection.mutable

case class NocPacket(dest:Int,
                     src:Int,
                     custom:BigInt,
                     data:Seq[BigInt] = Seq()){
  def head: BigInt = (dest.toBigInt << 60) | (src.toBigInt << 52) | custom

  def headOnly: Boolean = data.isEmpty
}

class NocInterfaceDriver(noc:Stream[Fragment[NocInterface]], clockDomain: ClockDomain){

  val (driver, queue) = StreamDriver.queue(noc, clockDomain)
  driver.transactionDelay = () => 0

  def sendPacket(p:NocPacket): Unit ={
    queue.enqueue{n =>
      n.flit #= p.head
      n.last #= p.headOnly
    }
    if(!p.headOnly){
      for((d, i) <- p.data.zipWithIndex){
        queue.enqueue{n =>
          n.flit #= d
          n.last #= i==p.data.length
        }
      }
    }
  }
}

class NocInterfaceMonitor(noc:Stream[Fragment[NocInterface]], clockDomain: ClockDomain){
  val monitorQueue = mutable.Queue[NocPacket]()

  def addPacket(p:NocPacket): Unit ={
    monitorQueue.enqueue(p)
  }

  def usingReadyRandomizer(): Unit = {
    StreamReadyRandomizer(noc, clockDomain)
  }

  noc.ready #= true
  fork{
    while (true) {
      clockDomain.waitSamplingWhere(noc.valid.toBoolean && noc.ready.toBoolean)
      val packet = monitorQueue.dequeue()
      // assert head
      assert(noc.flit.toBigInt==packet.head)
      if(packet.headOnly){
        assert(noc.last.toBoolean)
      }else{
        assert(!noc.last.toBoolean)
        // assert body
        for ((data, i) <- packet.data.zipWithIndex) {
          clockDomain.waitSamplingWhere(noc.valid.toBoolean && noc.ready.toBoolean)
          assert(noc.flit.toBigInt==data)
          assert(noc.last.toBoolean==(i==packet.data.length-1))
        }
      }
    }
  }
}