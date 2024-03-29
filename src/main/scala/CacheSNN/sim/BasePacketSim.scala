package CacheSNN.sim

import RingNoC.sim.NocPacket
import spinal.lib._
import CacheSNN.{AER, PacketType}

import scala.util.Random

case class BasePacketSim(dest:Int,
                         src:Int,
                         packetType:PacketType.E,
                         write:Boolean,
                         id:Int,
                         field1:BigInt,
                         field2:BigInt,
                         data:Seq[BigInt] = Seq()){
  import PacketType._

  def toNocPacket:NocPacket = {
    val writeBigInt = (if(write) 1 else 0).toBigInt
    val custom = (packetType.position.toBigInt<<45) | (writeBigInt<<44) | (field1<<36) | (id.toBigInt<<32) | field2
    NocPacket(dest, src, custom, data)
  }

  def toRspPacket(field1:Int = 0, field2:Long = 0, data:Seq[BigInt] = Seq()): BasePacketSim = {
    val pt = packetType match {
      case R_CMD => R_RSP
      case D_CMD => D_RSP
      case _ => throw new UnsupportedOperationException()
    }
    this.copy(
      dest = src, src = dest, packetType = pt, // reversed field
      field1 = field1, field2 = field2, data = data // new field
    )
  }
}

object BasePacketSim {
  def apply(p:NocPacket): BasePacketSim ={
    val packetTypeRaw = (p.custom>>45).toInt & 0x7
    val packetType: PacketType.E = packetTypeRaw match {
      case 0 => PacketType.R_CMD
      case 1 => PacketType.R_RSP
      case 2 => PacketType.D_CMD
      case 3 => PacketType.D_RSP
      case 4 => PacketType.AER
      case 5 => PacketType.ERROR
    }
    val write = ((p.custom >> 44).toInt & 0x1) == 1
    val field1 = (p.custom >> 36) & 0xFF
    val id = (p.custom >> 32).toInt & 0xF
    val field2 = (p.custom & ((BigInt(1) << 32) - 1)).toLong
    new BasePacketSim(
      dest = p.dest,
      src = p.src,
      packetType = packetType,
      write = write,
      id = id,
      field1 = field1,
      field2 = field2,
      data = p.data
    )
  }

  implicit def toRawPacket(bp:BasePacketSim): NocPacket = bp.toNocPacket
  implicit def toRawPackets(bps:Seq[BasePacketSim]): Seq[NocPacket] = bps.map(_.toNocPacket)

  def regRead(dest:Int, src:Int, id:Int, addr:Int): BasePacketSim ={
    BasePacketSim(dest, src, packetType = PacketType.R_CMD, write = false, id = id, field1 = addr, field2 = 0)
  }

  def regWrite(dest:Int, src:Int, id:Int, addr:Int, data:Long): BasePacketSim = {
    BasePacketSim(dest, src, packetType = PacketType.R_CMD, write = true, id = id, field1 = addr, field2 = data)
  }

  def errorPacket(dest:Int, src:Int): BasePacketSim ={
    val data = Seq.fill(Random.nextInt(10))(Random.nextInt(666).toBigInt)
    BasePacketSim(dest, src, packetType = PacketType.ERROR, write = false, id = 1, field1 = 0, field2 = 0, data)
  }

  def dataWrite(dest:Int, src:Int, id:Int, addr:Long, data:Seq[BigInt]): BasePacketSim ={
    BasePacketSim(dest, src, packetType = PacketType.D_CMD, write = true, id = id, field1 = data.length-1, field2 = addr, data)
  }

  def dataRead(dest:Int, src:Int, id:Int, addr:Long, len:Int): BasePacketSim ={
    BasePacketSim(dest, src, packetType = PacketType.D_CMD, write = false, id = id, field1 = len, field2 = addr)
  }

  def aerPacket(dest:Int, src:Int, aerType: AER.TYPE.E, nid:Int, data:Seq[BigInt] = Seq()): BasePacketSim ={
    val field2 = (aerType.position<<29) | nid
    BasePacketSim(dest, src, packetType = PacketType.AER, write = false, id = 0, field1 = 0, field2 = field2, data = data)
  }
}