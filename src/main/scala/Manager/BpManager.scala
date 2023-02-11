package Manager

import CacheSNN._
import Util.Misc
import RingNoC.NocInterface
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm._

case class BpCmd() extends Bundle {
  val useRegCmd = Bool()
  val write = Bool()
  val dest = UInt(4 bits)
  val field1 = UInt(8 bits)
  val field2 = Bits(32 bits)
  val mAddr = UInt(32 bits)

  def toNocHead(id: UInt): Bits = {
    require(id.getWidth == 4)
    val packetType = useRegCmd ? PacketType.R_CMD | PacketType.D_CMD
    dest ## B(0, 12 bits) ## packetType.asBits ## write ## field1 ## id ## field2
  }
}

class BpManager extends Component {
  val io = new Bundle {
    val free = out Bool()
    val cmd = slave(Stream(BpCmd()))
    val localSend = master(NocInterface())
    val readRsp = slave(BaseReadRsp())
    val writeRsp = slave(BaseWriteRsp())
    val axi = master(Axi4(CacheSNN.axiMasterConfig.copy(idWidth = 1)))
  }

  val cmdBuffer = Mem(Flow(BpCmd()), 16)
  val cmdIssuePtr = Counter(5 bits, io.cmd.fire)
  val cmdRspPtr = Counter(5 bits)
  val cmdProcessPtr = Counter(5 bits)

  io.free := cmdRspPtr===cmdIssuePtr

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
    val idle = makeInstantEntry()
    val nocHead = new State
    val axiReadCmd, axiReadData = new State
    val ptrIncr = new State

    val cmd = cmdBuffer.readSync(cmdProcessPtr(3 downto 0))
    val isDataCmdWrite = cmd.write && !cmd.useRegCmd

    idle.whenIsActive{
      when(cmdProcessPtr=/=cmdIssuePtr){
        goto(nocHead)
      }
    }

    nocHead.whenIsActive{
      io.localSend.valid := True
      io.localSend.flit := cmd.toNocHead(id = cmdProcessPtr(3 downto 0))
      io.localSend.last := !isDataCmdWrite
      when(io.localSend.ready){
        when(isDataCmdWrite) {
          goto(axiReadCmd)
        } otherwise {
          goto(ptrIncr)
        }
      }
    }

    axiReadCmd.whenIsActive{
      io.axi.readCmd.valid := True
      io.axi.readCmd.addr := cmd.mAddr
      io.axi.readCmd.len := cmd.field1
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
      goto(idle)
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

    val readPrior = RegInit(True)
    readPrior.clearWhen(io.readRsp.lastFire && io.writeRsp.valid)
    readPrior.setWhen(io.writeRsp.fire)

    idle.whenIsActive {
      when(io.readRsp.valid && readPrior){
        bufferRead := True
        bufferAddr := io.readRsp.id
        goto(axiWriteCmd)
      }elsewhen io.writeRsp.valid {
        bufferWrite := True
        bufferAddr := io.writeRsp.id
        io.writeRsp.ready := True
        goto(ptrIncr0)
      }
    }

    axiWriteCmd.whenIsActive {
      io.axi.writeCmd.valid := True
      io.axi.writeCmd.addr := cmd.mAddr
      io.axi.writeCmd.len := !cmd.useRegCmd ? cmd.field1 | 0
      when(io.axi.writeCmd.ready){
        goto(axiWriteData)
      }
    }

    axiWriteData.whenIsActive {
      io.axi.writeData.arbitrationFrom(io.readRsp)
      io.axi.writeData.data := io.readRsp.data
      io.axi.writeData.last := io.readRsp.last
      bufferWrite := True
      bufferAddr := io.readRsp.id
      when(io.readRsp.lastFire){
        goto(axiWriteAck)
      }
    }

    axiWriteAck.whenIsActive {
      io.axi.writeRsp.ready := True
      when(io.axi.writeRsp.valid){
        goto(ptrIncr0)
      }
    }

    ptrIncr0.whenIsActive{
      bufferRead := True
      bufferAddr := cmdRspPtr.resized
      goto(ptrIncr1)
    }

    ptrIncr1.whenIsActive{
      when(!cmd.valid && !io.free){
        cmdRspPtr.increment()
        goto(ptrIncr0)
      }otherwise{
        goto(idle)
      }
    }
  }
}
