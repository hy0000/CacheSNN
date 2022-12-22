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
  implicit def toRawPacket(bp: AerPacketSim): NocPacket = bp.toNocPacket
  implicit def toRawPackets(bps: Seq[AerPacketSim]): Seq[NocPacket] = bps.map(_.toNocPacket)
}
