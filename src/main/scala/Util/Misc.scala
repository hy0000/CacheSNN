package Util

import spinal.core._

object Misc {
  def clearIO(io:Bundle): Unit ={
    io.flattenForeach { bt =>
      if (bt.isOutput) {
        bt := bt.getZero
      }
    }
  }
}
