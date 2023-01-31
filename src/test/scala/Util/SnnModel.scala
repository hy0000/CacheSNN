package Util

import scala.util.Random
import sim.NumberTool._

class SnnModel(preLen:Int, postLen:Int) {
  val weight = Array.tabulate(preLen, postLen){
    (_, _) => 1
  }
  val current = Array.fill(postLen)(0)
  //val ltpLut = (1 to 16).toArray
  //val ltdLut = (1 to 16).map(-_).toArray
  val ltpLut = Array.fill(16)(1)//(1 to 16).toArray
  val ltdLut = Array.fill(16)(0)//(1 to 16).map(-_).toArray

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
    val preSpikeLastFireTime = Array.fill(preLen)(-16)
    val postSpikeLastFireTime = Array.fill(postLen)(-16)
    for(t <- preSpike.indices){
      spikeForward(preSpike(t))
      // update ltd weight
      for(i <- 0 until preLen) {
        if (preSpike(t)(i) == 1) {
          preSpikeLastFireTime(i) = t
          for (j <- 0 until postLen) {
            val deltaT = t - postSpikeLastFireTime(j)
            if (deltaT < 16) {
              weight(i)(j) += ltdLut(deltaT)
            }
          }
        }
      }
      // update ltp weight
      for(j <- 0 until postLen) {
        if(postSpike(t)(j)==1) {
          postSpikeLastFireTime(j) = t + 1
          for(i <- 0 until preLen) {
            val deltaT = t - preSpikeLastFireTime(i)
            if (deltaT < 16) {
              weight(i)(j) += ltpLut(deltaT)
            }
          }
        }
      }
    }
  }
}
