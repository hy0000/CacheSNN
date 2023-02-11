package Manager

import CacheSNN._
import Util.Misc
import RingNoC.NocInterface
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm._

case class BpCmd() extends Bundle {
  val head = Bits(64 bits)
  val mAddr = UInt(32 bits)
}

class BpManager extends Component {
  val io = new Bundle {
    val cmd = slave(Stream(BpCmd()))
    val localSend = master(NocInterface())
    val readRsp = slave(BaseReadRsp())
    val writeRsp = slave(BaseWriteRsp())
    val axi = master(Axi4(CacheSNN.axiMasterConfig.copy(idWidth = 1)))
  }

  val cmdBuffer = Mem(Flow(BpCmd()), 16)
  val cmdIssuePtr = Counter(5 bits)
  val cmdRspPtr = Counter(5 bits)
  val cmdProcessPtr = Counter(5 bits)

  val cmdIssueArea = new Area {
    val wrapped = cmdIssuePtr.msb ^ cmdRspPtr.msb
    io.cmd.ready := !(wrapped && cmdIssuePtr(3 downto 0)===cmdRspPtr(3 downto 0))
    cmdBuffer.write(
      address = cmdIssuePtr.value.resized,
      data = io.cmd.asFlow,
      enable = io.cmd.fire
    )
  }

  Misc.setAxiMasterDefault(io.axi, id = 0)
  Misc.clearIO(io)

  val cmdProcessFsm = new StateMachine {
    val nocHead = makeInstantEntry()
    val axiReadCmd, axiReadData = new State
    val ptrIncr = new State

    val cmd = cmdBuffer.readSync(cmdProcessPtr(3 downto 0))
    val cmdValid = cmdProcessPtr=/=cmdIssuePtr
    val bph = BasePacketHead(cmd.head)
    val isDataCmdWrite = bph.write && bph.packetType===PacketType.D_CMD

    nocHead.whenIsActive{
      when(cmdValid){
        io.localSend.flit := cmd.head
        io.localSend.flit(35 downto 32).removeAssignments()
        io.localSend.flit(35 downto 32) := cmdIssuePtr.value.asBits.resized
        io.localSend.last := !isDataCmdWrite
        when(io.localSend.ready){
          when(isDataCmdWrite) {
            goto(axiReadCmd)
          } otherwise {
            goto(ptrIncr)
          }
        }
      }
    }

    axiReadCmd.whenIsActive{
      io.axi.readCmd.valid := True
      io.axi.readCmd.addr := cmd.mAddr
      io.axi.readCmd.len := bph.field1.asUInt
      when(io.axi.readCmd.ready){
        goto(axiReadData)
      }
    }

    axiReadData.whenIsActive {
      io.localSend.arbitrationFrom(io.axi.r)
      io.localSend.flit := io.axi.r.data
      io.localSend.last := io.axi.r.last
      when(io.localSend.lastFire){
        goto(ptrIncr)
      }
    }

    ptrIncr.whenIsActive{
      cmdProcessPtr.increment()
    }
  }

  val cmdRspFsm = new StateMachine {
    val idle = makeInstantEntry()
    val axiWriteCmd, axiWriteData, axiWriteAck = new State
    val ptrIncr0, ptrIncr1 = new State

    val bufferAddr = U(0, 4 bits)
    val bufferRead, bufferWrite = False
    val cmd = cmdBuffer.readWriteSync(
      address = bufferAddr,
      data = Flow(BpCmd()).getZero,
      enable = bufferRead || bufferWrite,
      write = bufferWrite
    )
    val bph = BasePacketHead(cmd.head)

    idle.whenIsActive {
      when(io.readRsp.valid){
        bufferRead := True
        bufferAddr := io.readRsp.id
        goto(axiWriteCmd)
      }elsewhen io.writeRsp.valid {
        bufferWrite := True
        bufferAddr := io.writeRsp.id
        goto(ptrIncr0)
      }
    }

    axiWriteCmd.whenIsActive {
      io.axi.writeCmd.valid := True
      io.axi.writeCmd.addr := cmd.mAddr
      when(bph.packetType===PacketType.R_RSP){
        io.axi.writeCmd.len := 0
      }otherwise{
        io.axi.writeCmd.len := bph.field1.asUInt
      }
      when(io.axi.writeCmd.ready){
        goto(axiWriteData)
      }
    }

    axiWriteData.whenIsActive {
      io.axi.writeData.arbitrationFrom(io.readRsp)
      io.axi.writeData.data := io.readRsp.data
      io.axi.writeData.last := io.readRsp.last
      when(io.readRsp.lastFire){
        goto(ptrIncr0)
      }
    }

    ptrIncr0.whenIsActive{
      bufferRead := True
      bufferAddr := cmdRspPtr.resized
      goto(ptrIncr1)
    }

    ptrIncr1.whenIsActive{
      when(cmd.valid){
        goto(idle)
      }otherwise{
        cmdRspPtr.increment()
        goto(ptrIncr0)
      }
    }
  }
}
