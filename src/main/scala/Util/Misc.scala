package Util

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.amba4.axilite.AxiLite4

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

  def xAxiRename(axi: Axi4, prefix:String): Unit ={
    axi.flattenForeach{bt =>
      val names = bt.getName().split("_")
      val channelName = names(2)
      val signalName = names.last
      val newName = (channelName ++ signalName).toUpperCase
      bt.setName(prefix ++ newName)
    }
  }

  def xAxiLiteRename(axi: AxiLite4, prefix: String): Unit = {
    axi.flattenForeach { bt =>
      val names = bt.getName().split("_")
      val channelName = names(2)
      val signalName = names.last
      val newName = (channelName ++ signalName).toUpperCase
      bt.setName(prefix ++ newName)
    }
  }
}
