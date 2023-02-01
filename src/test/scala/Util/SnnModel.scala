package Util

import scala.util.Random
import sim.NumberTool._

class SnnModel(preLen:Int, postLen:Int) {
  val weight = Array.tabulate(preLen, postLen){
    (_, _) => 1
  }
  val current = Array.fill(postLen)(0)
  val ltpLut = (1 to 16).toArray
  val ltdLut = (1 to 16).map(_*(-10)).toArray
  //val ltpLut = Array.fill(16)(0)//(1 to 16).toArray
  //val ltdLut = Array.fill(16)(0)//(1 to 16).map(-_).toArray

  def weightRandomize(): Unit ={
    for(i <- 0 until preLen){
      for(j <- 0 until postLen){
        weight(i)(j) = Random.nextInt(256)
      }
    }
  }

  def currentClear(): Unit ={
    for (j <- 0 until postLen) {
      current(j) = 0
    }
  }

  def spikeForward(preSpike:Array[Int]): Unit ={
    for(nid <- 0 until preLen){
      if(preSpike(nid)==1){
        for (j <- 0 until postLen) {
          current(j) += weight(nid)(j)
        }
      }
    }
  }

  def spikeUpdate(preSpike:Array[Array[Int]], postSpike:Array[Array[Int]]): Unit = {
    val preSpikeLastFireTime = Array.fill(preLen)(-222)
    val postSpikeLastFireTime = Array.fill(postLen)(-333)
    val epoch = preSpike.length
    for(t <- 0 until epoch) {
      // update weight
      for(i <- 0 until preLen) {
        if (preSpike(t)(i) == 1) {
          val ppsTime = preSpikeLastFireTime(i)
          for (j <- 0 until postLen) {
            // ltp
            if(ppsTime >= 0){
              var ltpT = ppsTime
              while (ltpT < t && postSpike(ltpT)(j) == 0) {
                ltpT += 1
              }
              if(ltpT!=t){
                val ltpDeltaT = ltpT - ppsTime
                weight(i)(j) += ltpLut(ltpDeltaT)
              }
            }
            // acc current
            current(j) += weight(i)(j)
            // ltd
            if(postSpikeLastFireTime(j)>=ppsTime){
              val ltdDeltaT = t - postSpikeLastFireTime(j)
              weight(i)(j) += ltdLut(ltdDeltaT)
            }
          }
          preSpikeLastFireTime(i) = t
        }
      }
      for(j <- 0 until postLen){
        if(postSpike(t)(j)==1){
          postSpikeLastFireTime(j) = t
        }
      }
    }
  }
}
