package RingNoC.sim

import RingNoC.NocInterface
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.StreamDriver

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

  def sendPacket(ps:NocPacket*): Unit ={
    for(p <- ps){
      queue.enqueue { n =>
        n.flit #= p.head
        n.last #= p.headOnly
      }
      if (!p.headOnly) {
        for ((d, i) <- p.data.zipWithIndex) {
          queue.enqueue { n =>
            n.flit #= d
            n.last #= i == (p.data.length-1)
          }
        }
      }
    }
  }
}