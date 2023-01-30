package CacheSNN.sim

import CacheSNN.{AER, PacketType}
import spinal.lib._
import RingNoC.sim.NocPacket

case class AerPacketSim(dest: Int,
                        src: Int,
                        id: Int,
                        eventType: AER.TYPE.E,
                        nid: Int,
                        data:Seq[BigInt] = Seq()){
  def toNocPacket:NocPacket = {
    val custom = (PacketType.AER.position.toBigInt<<45) | (id.toBigInt << 32) | (eventType.position.toBigInt<<29) | nid
    NocPacket(dest, src, custom, data)
  }
}

object AerPacketSim {
  def apply(bp: BasePacketSim): AerPacketSim = {
    val eventTypeRaw = (bp.field2>>(16 + 13)).toInt
    val nid = (bp.field2 & ((1<<16) - 1)).toInt
    val eventType: AER.TYPE.E = eventTypeRaw match {
      case 0 => AER.TYPE.W_FETCH
      case 1 => AER.TYPE.W_WRITE
      case 2 => AER.TYPE.PRE_SPIKE
      case 3 => AER.TYPE.POST_SPIKE
      case 4 => AER.TYPE.CURRENT
    }
    new AerPacketSim(dest = bp.dest, src = bp.src, id = bp.id, eventType = eventType, nid = nid)
  }
  implicit def toRawPacket(bp: AerPacketSim): NocPacket = bp.toNocPacket
  implicit def toRawPackets(bps: Seq[AerPacketSim]): Seq[NocPacket] = bps.map(_.toNocPacket)
}
