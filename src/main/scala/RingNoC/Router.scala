package RingNoC

import Util.{Misc, StreamFifoDelay2}
import spinal.core._
import spinal.lib._

case class RouterConfig(coordinate:Int, n:Int)

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

  val leftN = if(routerConfig.n % 2 == 0) routerConfig.n / 2 - 1 else routerConfig.n / 2
  val rightN = routerConfig.n / 2
  val leftDest = (1 to leftN).map{ leftDis =>
    (routerConfig.coordinate - leftDis + routerConfig.n) % routerConfig.n
  }
  val rightDest = (1 to rightN).map{ rightDis =>
    (routerConfig.coordinate + rightDis) % routerConfig.n
  }
  val dirLeft = leftDest.map(_ === localWithSrc.dest).orR && localWithSrc.isFirst
  val dirRight = rightDest.map(_ === localWithSrc.dest).orR && localWithSrc.isFirst
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

class Router(routerConfig: RouterConfig) extends Component {
  val io = new Bundle {
    val local = slave(NocInterfaceLocal())
    val leftIn, rightIn = slave(NocInterface())
    val leftOut, rightOut = master(NocInterface())
  }

  val leftDeMux, rightDeMux = new RouterDeMux(routerConfig)
  val localDeMux = new RouterDeMuxLocal(routerConfig)

  leftDeMux.io.nocIn << io.leftIn
  rightDeMux.io.nocIn << io.rightIn
  localDeMux.io.localIn << io.local.send

  val leftOuts = Seq(localDeMux.io.nocLeftOut, rightDeMux.io.nocOut)
  val rightOuts = Seq(localDeMux.io.nocRightOut, leftDeMux.io.nocOut)
  val localOuts = Seq(leftDeMux.io.localOut, rightDeMux.io.localOut)

  Seq(leftOuts, rightOuts, localOuts)
    .zip(Seq(io.leftOut, io.rightOut, io.local.rec))
    .foreach{case (pIns, pOut) =>
      val outPorts = pIns.map { p =>
        val fifo = StreamFifoDelay2(p.payload, 512)
        p >> fifo.io.push
        fifo.io.pop
      }
      pOut << StreamArbiterFactory.fragmentLock.roundRobin.on(outPorts)
    }
}