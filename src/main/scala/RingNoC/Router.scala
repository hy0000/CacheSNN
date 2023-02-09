package RingNoC

import Util.Misc
import spinal.core._
import spinal.lib._

case class RouterConfig(coordinate:Int)

class RouterDeMux(routerConfig: RouterConfig) extends Component {
  val io = new Bundle {
    val nocIn = slave(NocInterface())
    val nocOut = master(NocInterface())
    val localOut = master(NocInterface())
  }

  val headMatch = io.nocIn.isFirst && io.nocIn.dest===routerConfig.coordinate
  val selLocalReg = RegInit(False)
    .setWhen(headMatch)
    .clearWhen(io.nocIn.lastFire)
  val selLocal = selLocalReg || headMatch

  val de = StreamDemux(io.nocIn, selLocal.asUInt, 2)
  io.nocOut << de.head
  io.localOut << de.last
}

class RouterDeMuxLocal(routerConfig: RouterConfig) extends Component {
  val io = new Bundle {
    val nocLeftOut = master(NocInterface())
    val nocRightOut = master(NocInterface())
    val localIn = slave(NocInterface())
  }

  val localWithSrc = io.localIn.translateWith{
    val ret = cloneOf(io.localIn.payload)
    ret := io.localIn.payload
    when(io.localIn.isFirst){
      ret.flit(55 downto 52) := routerConfig.coordinate
    }
    ret
  }
  localWithSrc.setBlocked()

  val dirLeft = localWithSrc.dest < routerConfig.coordinate && localWithSrc.isFirst
  val dirRight = localWithSrc.dest > routerConfig.coordinate && localWithSrc.isFirst
  val selLeftReg, selRightReg = RegInit(False)
  selLeftReg setWhen dirLeft clearWhen localWithSrc.lastFire
  selRightReg setWhen dirRight clearWhen localWithSrc.lastFire
  val selLeft = dirLeft || selLeftReg
  val selRight = dirRight || selRightReg

  Misc.idleStream(io.nocLeftOut, io.nocRightOut)
  when(selLeft){
    io.nocLeftOut << localWithSrc
  }elsewhen selRight {
    io.nocRightOut << localWithSrc
  }otherwise{
    assert(!localWithSrc.valid, s"noc packet dest should different with src", FAILURE)
  }
}

class Router extends Component {

}