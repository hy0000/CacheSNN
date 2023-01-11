package Util

import spinal.core._
import spinal.lib._

object Misc {
  def clearIO(io:Bundle): Unit ={
    io.flattenForeach { bt =>
      if (bt.isOutput && !bt.hasAssignement) {
        bt := bt.getZero
      }
    }
  }

  def idleStream[T<:Data](s: Stream[T]*): Unit = {
    s.foreach{a =>
      a.valid := False
      a.payload := a.payload.getZero
    }
  }
}
