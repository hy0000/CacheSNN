package Util

import scala.util.Random

class SnnModel(preLen:Int, postLen:Int) {
  val weight = Array.tabulate(preLen, postLen){
    (_, _) => 1
  }
  val current = Array.fill(postLen)(0)

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
      for(j <- 0 until postLen){
        current(j) += weight(nid)(j)
      }
    }
  }

  def spikeUpdate(preSpike:Array[Int], postSpike:Array[Int]): Unit ={
    for (i <- 0 until preLen) {
      for (j <- 0 until postLen) {
      }
    }
  }
}
