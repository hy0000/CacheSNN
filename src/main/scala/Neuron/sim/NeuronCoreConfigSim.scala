package Neuron.sim

import Util.sim.NumberTool._

object NeuronCoreConfigSim {
  def apply(nidBase:Int, acc:Int, srcList:Seq[Int], threshold:Int, spikeLen: Int): NeuronCoreConfigSim = {
    new NeuronCoreConfigSim (nidBase, acc, srcList, threshold, spikeLen)
  }

  def genRegField(cfg: Seq[NeuronCoreConfigSim]): NeuronCoreReg ={
    var i = 0
    var nidField, mapField, lenField = 0L
    var thresholdField = BigInt(0)
    for (c <- cfg) {
      val srcOh = Seq(0, 1, 4, 5).map(srcId => c.srcList.contains(srcId)).map(booleanToInt)
      val srcOhRaw = vToRaw(srcOh, 1).toLong
      nidField |= ((c.nidBase >> 8) | 1) << (i * 8)
      mapField |= ((c.acc << 6) | (i << 4) | srcOhRaw) << (i * 8)
      lenField |= (c.spikeLen - 1) << (i * 4)
      thresholdField |= BigInt(c.threshold & 0xFFFF) << (i * 16)
      i += 1
    }

    val threshold0 = (thresholdField & ((1L << 32) - 1)).toLong
    val threshold1 = (thresholdField >> 32).toLong
    NeuronCoreReg(
      nidField = nidField,
      threshold0 = threshold0,
      threshold1 = threshold1,
      mapField = mapField,
      lenField = lenField
    )
  }
}

class NeuronCoreConfigSim (val nidBase:Int, val acc:Int, val srcList:Seq[Int], val threshold:Int, val spikeLen: Int)

case class NeuronCoreReg(nidField:Long, threshold0:Long, threshold1:Long, mapField:Long, lenField:Long)

object NeuronRegAddr {
  val NidField = 0x00
  val MapField = 0x04
  val Threshold0 = 0x08
  val Threshold1 = 0x0C
  val LenField = 0x10
}
