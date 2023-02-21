package Neuron.sim

import Util.sim.NumberTool._

object NeuronCoreConfigSim {
  def apply(nidBase:Int, acc:Int, srcList:Seq[Int], threshold:Int, spikeLen: Int, alpha:Int): NeuronCoreConfigSim = {
    new NeuronCoreConfigSim (nidBase, acc, srcList, threshold, spikeLen, alpha)
  }

  def genRegField(cfg: Seq[NeuronCoreConfigSim]): NeuronCoreReg ={
    var i = 0
    var nidField, mapField, lenField = 0L
    val paramField = Array.fill(4)(0L)
    for (c <- cfg) {
      val srcOh = Seq(0, 1, 4, 5).map(srcId => c.srcList.contains(srcId)).map(booleanToInt)
      val srcOhRaw = vToRaw(srcOh, 1).toLong
      nidField |= ((c.nidBase >> 8) | 1) << (i * 8)
      mapField |= ((c.acc << 6) | (i << 4) | srcOhRaw) << (i * 8)
      lenField |= (c.spikeLen - 1) << (i * 4)
      paramField(i) = (c.alpha & 0xFFFF)<<16
      paramField(i) |= c.threshold & 0xFFFF
      i += 1
    }
    NeuronCoreReg(
      nidField = nidField,
      mapField = mapField,
      paramField = paramField,
      lenField = lenField
    )
  }
}

class NeuronCoreConfigSim (val nidBase:Int, val acc:Int, val srcList:Seq[Int], val threshold:Int, val spikeLen: Int, val alpha:Int)

case class NeuronCoreReg(nidField:Long, paramField:Array[Long], mapField:Long, lenField:Long)

object NeuronRegAddr {
  val NidField = 0x00
  val MapField = 0x04
  val ParamField = Seq(0x08, 0x0C, 0x10, 0x14)
  val LenField = 0x18
}
