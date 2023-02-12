package Util

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4

object Misc {
  def clearIO(io:Bundle): Unit ={
    io.flattenForeach { bt =>
      if (bt.isOutput && !bt.hasAssignement) {
        bt := bt.getZero
      }
    }
  }

  def idleIo(ios:Bundle *):Unit ={
    for(io <- ios){
      io.flattenForeach { bt =>
        if (bt.isInput && !bt.hasAssignement) {
          bt := bt.getZero
        }
      }
    }
  }

  def idleStream[T<:Data](s: Stream[T]*): Unit = {
    s.foreach{a =>
      a.valid := False
      a.payload := a.payload.getZero
    }
  }

  def setAxiMasterDefault(axi: Axi4, id: Int): Unit = {
    axi.aw.id := id
    axi.ar.id := id
    axi.aw.size.assignFromBits(Axi4.size.BYTE_8)
    axi.ar.size.assignFromBits(Axi4.size.BYTE_8)
    axi.aw.burst := Axi4.burst.INCR
    axi.ar.burst := Axi4.burst.INCR
    axi.aw.cache := 0
    axi.ar.cache := 0
    axi.aw.lock := 0
    axi.ar.lock := 0
    axi.aw.qos := 0
    axi.ar.qos := 0
    axi.aw.region := 0
    axi.ar.region := 0
    axi.aw.prot := 0
    axi.ar.prot := 0
    axi.w.strb := 0xFF
  }
}
