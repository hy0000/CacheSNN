package CacheSNN.sim

import Util.sim.NumberTool._

import scala.util.Random

object SpikeFun {

  def randomSpike(n: Int, fireRate:Int = 2): Array[Int] ={
    Array.fill(n)(Random.nextInt(10)<fireRate).map(booleanToInt)
  }

  def randomPreSpikeRaw(n:Int): Seq[BigInt] ={
    require(n%64==0)
    val spike = randomSpike(n) ++ Array.fill(1024-n)(0)
    vToRawV(spike, width = 1, n = 64)
  }

  def randomPostSpikeRaw(n:Int): Seq[BigInt] ={
    require(n%64==0)
    val spike = randomSpike(n)
    vToRawV(spike, width = 1, n = 64)
  }
}
