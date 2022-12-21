package CacheSNN.sim

import RingNoC.sim.NocPacket
import spinal.lib._
import CacheSNN.PacketType

import scala.util.Random

case class BasePacket(dest:Int,
                      src:Int,
                      packetType:PacketType.E,
                      write:Boolean,
                      field1:BigInt,
                      field2:BigInt,
                      data:Seq[BigInt] = Seq()){

  def toNocPacket:NocPacket = {
    val writeBigInt = (if(write) 1 else 0).toBigInt
    val custom = (packetType.position.toBigInt<<45) | (writeBigInt<<44) | (field1<<36) | field2
    NocPacket(dest, src, custom, data)
  }
}

object BasePacket {
  implicit def toRawPacket(bp:BasePacket): NocPacket = bp.toNocPacket
  implicit def toRawPackets(bps:Seq[BasePacket]): Seq[NocPacket] = bps.map(_.toNocPacket)

  def regRead(dest:Int, src:Int, addr:Int): BasePacket ={
    BasePacket(dest, src, packetType = PacketType.R_CMD, write = false, field1 = addr, field2 = 0)
  }

  def regWrite(dest:Int, src:Int, addr:Int, data:Long): BasePacket = {
    BasePacket(dest, src, packetType = PacketType.R_CMD, write = true, field1 = addr, field2 = data)
  }

  def errorPacket(dest:Int, src:Int): BasePacket ={
    val data = Seq.fill(Random.nextInt(10))(Random.nextInt(666).toBigInt)
    BasePacket(dest, src, packetType = PacketType.ERROR, write = false, field1 = 0, field2 = 0, data)
  }
}