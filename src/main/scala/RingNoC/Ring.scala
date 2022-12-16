package RingNoC

import spinal.core._
import spinal.lib._

import scala.collection.mutable

class RingRouter(routerConfig: RouterConfig) extends Component {
  val io = new Bundle {
    val local = slave(NocInterfaceLocal())
    val leftIn, rightIn = slave(NocInterface())
    val leftOut, rightOut = master(NocInterface())
  }
  stub()
}

case class Ring(){

  val nodes = mutable.LinkedHashMap[Int, NocInterfaceLocal]()

  def addNode(interface:NocInterfaceLocal, coordinate:Int): Unit ={
    nodes(coordinate) = interface
  }

  def addNodes(orders: (NocInterfaceLocal, Int)*): Unit ={
    orders.foreach(order => addNode(order._1,order._2))
  }

  def build(): Unit ={
    val routers = (0 until nodes.size).map{coordinate =>
      val config = RouterConfig(coordinate)
      val router = new RingRouter(config).setDefinitionName(s"ringRouter_$coordinate")
      router.io.local <> nodes(coordinate)
      router
    }

    for((l, r) <- routers.zip(routers.tail ++ Seq(routers.head))){
      l.io.rightOut >> r.io.leftIn
      l.io.rightIn << r.io.leftOut
    }
  }

  Component.current.addPrePopTask(build)
}