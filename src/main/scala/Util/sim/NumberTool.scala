package Util.sim

import scala.util.Random

object NumberTool {
  def vToRaw(v: Seq[Int], width: Int): BigInt = {
    val mask = (1 << width) - 1
    v.foldRight(BigInt(0))((s, acc) => (acc << width) | (s & mask))
  }

  def vToRawV(v: Seq[Int], width: Int, n: Int): Seq[BigInt] = {
    v.grouped(n).map(g => vToRaw(g, width)).toSeq
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

  def randomUIntN(n: Int): Int = {
    val upBound = 1 << (n - 1)
    Random.nextInt(upBound)
  }

  def booleanToInt(b: Boolean) = if (b) 1 else 0

  def quantize(x: Double, q: Int): Int = {
    val y = math.round(x * (1 << q)).toInt
    val upBound = (1 << (q + 1)) - 1
    val lowBound = -upBound - 1
    if (y >= upBound) {
      upBound
    } else if (y <= lowBound) {
      lowBound
    } else {
      y
    }
  }
}
