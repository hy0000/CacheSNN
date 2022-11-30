package CacheSNN

import spinal.core.sim._

import scala.util.Random

object CacheSnnTest {
  val simConfig = SpinalSimConfig(_spinalConfig = MySpinalConfig).withWave

  def vToRaw(v: Seq[Int], width: Int): BigInt = {
    val mask = (1 << width) - 1
    v.foldRight(BigInt(0))((s, acc) => (acc << width) | (s & mask))
  }

  def rawToV(raw: BigInt, width: Int, n: Int): Seq[Int] = {
    val mask = (1 << width) - 1
    val msb = 1 << (width - 1)
    val tyb = 1 << width
    (0 until n).map(i => (raw >> (i * width) & mask).toInt)
      .map { m => if ((m & msb) == 0) m else m - tyb }
  }

  def randomIntN(n: Int) = {
    val upBound = 1 << (n - 1)
    Random.nextInt(upBound * 2) - upBound
  }

  def randomInt16 = {
    val upBound = 1 << 15
    Random.nextInt(upBound * 2) - upBound
  }
}
