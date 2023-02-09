package RingNoC.sim

import RingNoC.NocInterface
import spinal.core._
import spinal.core.sim._
import spinal.lib._

class NocPacket(val dest: Int,
                val src: Int,
                val custom: BigInt,
                val data: Seq[BigInt]) {
  def head: BigInt = (dest.toBigInt << 60) | (src.toBigInt << 52) | custom

  def headOnly: Boolean = data.isEmpty

  def equals(that: NocPacket): Boolean =
    dest==that.dest &&
      src==that.src &&
      custom==that.custom &&
      data == that.data
}

object NocPacket {
  def apply(head: BigInt, body:Seq[BigInt]): NocPacket ={
    val dest = (head >> 60).toInt
    val src = (head >> 52).toInt & 0xF
    val custom = head & ((BigInt(1)<<48) - 1)
    new NocPacket(dest = dest, src = src, custom = custom, data = body)
  }

  def apply(dest: Int, src: Int, custom: BigInt, data: Seq[BigInt] = Seq()): NocPacket ={
    new NocPacket(dest = dest, src = src, custom = custom, data = data)
  }
}