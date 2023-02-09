package Util

import spinal.core._
import spinal.lib._

object StreamFifoDelay2{
  def apply[T <: Data](dataType: T, depth: Int) = new StreamFifoDelay2(dataType, depth)
}

class StreamFifoDelay2[T <: Data](dataType: T, depth: Int) extends Component{
  val io = new Bundle {
    val push = slave Stream dataType
    val pop = master Stream dataType
    val flush = in Bool() default False
    val occupancy = out UInt (log2Up(depth + 1) bits)
    val availability = out UInt (log2Up(depth + 1) bits)
  }

  val fifo = new StreamFifo(dataType, depth)
  fifo.io.push << io.push
  fifo.io.flush := io.flush
  io.occupancy := fifo.io.occupancy
  io.availability := fifo.io.availability

  val payloadDelay = RegNext(fifo.io.pop.payload)
  val fireDelay = RegNext(fifo.io.pop.fire, False)
  val validHold = RegInit(False)
  val hold = fireDelay & (!io.pop.ready)
  val payloadHold = RegNextWhen(payloadDelay, hold)
  when(io.pop.ready){
    validHold.clear()
  }elsewhen hold {
    validHold.set()
  }
  io.pop.valid := validHold || fireDelay
  io.pop.payload := Mux(validHold, payloadHold, payloadDelay)
  fifo.io.pop.ready := io.pop.ready
}
