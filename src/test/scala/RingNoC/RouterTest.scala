package RingNoC

import CacheSNN.CacheSnnTest._
import RingNoC.sim._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.StreamReadyRandomizer

import scala.collection.mutable
import scala.util.Random

class RouterDeMuxTest extends AnyFunSuite {
  val routerConfig = RouterConfig(Random.nextInt(16))
  val complied = simConfig.compile(new RouterDeMux(routerConfig))

  test("route test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val nocInDriver = new NocInterfaceDriver(dut.io.nocIn, dut.clockDomain)
      val packets = Seq.fill(100)(
        NocPacket(
          dest = Random.nextInt(16),
          src = Random.nextInt(16),
          custom = BigInt(48, Random),
          data = Seq.fill(Random.nextInt(256))(BigInt(64, Random))
        )
      )
      nocInDriver.sendPacket(packets)

      StreamReadyRandomizer(dut.io.nocOut, dut.clockDomain)
      StreamReadyRandomizer(dut.io.localOut, dut.clockDomain)
      val localPacket = packets.filter(p => p.dest==routerConfig.coordinate)
      val nocPacket = packets.filter(p => p.dest!=routerConfig.coordinate)
      val nocOutAsserter = new NocInterfaceAsserter(dut.io.nocOut, dut.clockDomain)
      val localOutAsserter = new NocInterfaceAsserter(dut.io.localOut, dut.clockDomain)
      nocOutAsserter.addPacket(nocPacket)
      localOutAsserter.addPacket(localPacket)
      nocOutAsserter.waiteComplete()
      localOutAsserter.waiteComplete()
    }
  }
}

class RouterDeMuxLocalTest extends AnyFunSuite {
  val routerConfig = RouterConfig(Random.nextInt(16))
  val complied = simConfig.compile(new RouterDeMuxLocal(routerConfig))

  test("route test"){
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val nocInDriver = new NocInterfaceDriver(dut.io.localIn, dut.clockDomain)
      val packets = Seq.fill(100)(
        NocPacket(
          dest = Random.nextInt(16),
          src = routerConfig.coordinate,
          custom = BigInt(48, Random),
          data = Seq.fill(Random.nextInt(256))(BigInt(64, Random))
        )
      ).filter(p => p.dest != routerConfig.coordinate)
      nocInDriver.sendPacket(packets)

      StreamReadyRandomizer(dut.io.nocLeftOut, dut.clockDomain)
      StreamReadyRandomizer(dut.io.nocRightOut, dut.clockDomain)
      val leftPacket = packets.filter(p => p.dest<routerConfig.coordinate)
      val rightPacket = packets.filter(p => p.dest>routerConfig.coordinate)
      val leftAsserter = new NocInterfaceAsserter(dut.io.nocLeftOut, dut.clockDomain)
      val rightAsserter = new NocInterfaceAsserter(dut.io.nocRightOut, dut.clockDomain)
      leftAsserter.addPacket(leftPacket)
      rightAsserter.addPacket(rightPacket)
      leftAsserter.waiteComplete()
      rightAsserter.waiteComplete()
    }
  }

  test("error packet test"){
    intercept[Throwable]{
      complied.doSim{dut =>
        dut.clockDomain.forkStimulus(2)
        SimTimeout(100000)
        val errorPacket = NocPacket(
          dest = routerConfig.coordinate,
          src = routerConfig.coordinate,
          custom = 0
        )
        val driver = new NocInterfaceDriver(dut.io.localIn, dut.clockDomain)
        driver.sendPacket(errorPacket)
        dut.clockDomain.waitSamplingWhere(dut.io.localIn.valid.toBoolean)
        dut.clockDomain.waitSampling()
      }
    }
  }
}

class RouterTest extends AnyFunSuite {
  val routerConfig = RouterConfig(Random.nextInt(16))
  val complied = simConfig.compile(new Router(routerConfig))

  class RouterOutMonitor(port:Stream[Fragment[NocInterface]], clockDomain: ClockDomain)
    extends NocInterfaceMonitor(port, clockDomain){

    val targetPacket = Array.fill(16)(mutable.Queue[NocPacket]())
    usingReadyRandomizer()

    override def onPacket(p: NocPacket) = {
      val tp = targetPacket(p.src).dequeue()
      assert(p.equals(tp))
    }

    def addPacket(p:NocPacket): Unit ={
      targetPacket(p.src).enqueue(p)
    }

    def addPackets(ps:Seq[NocPacket]): Unit ={
      for(p <- ps){
        addPacket(p)
      }
    }

    def waitComplete(): Unit ={
      for(tpQueue <- targetPacket){
        clockDomain.waitSamplingWhere(tpQueue.isEmpty)
      }
    }
  }

  case class RouterAgent(dut:Router){
    val leftInDriver = new NocInterfaceDriver(dut.io.leftIn, dut.clockDomain)
    val rightInDriver = new NocInterfaceDriver(dut.io.rightIn, dut.clockDomain)
    val localInDriver = new NocInterfaceDriver(dut.io.local.send, dut.clockDomain)

    val leftOutMonitor = new RouterOutMonitor(dut.io.leftOut, dut.clockDomain)
    val rightOutMonitor = new RouterOutMonitor(dut.io.rightOut, dut.clockDomain)
    val localOutMonitor = new RouterOutMonitor(dut.io.local.rec, dut.clockDomain)
  }

  def initDut(dut:Router):RouterAgent = {
    dut.clockDomain.forkStimulus(2)
    SimTimeout(1000000)
    RouterAgent(dut)
  }

  test("single direction test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      val id = routerConfig.coordinate
      val leftId = (id+16-1) % 16
      val rightId = (id + 1) % 16
      def randomData = Seq.fill(Random.nextInt(256))(BigInt(64, Random))

      val localToLeftP = NocPacket(leftId, id, 0, randomData)
      val localToRightP = NocPacket(rightId, id, 0, randomData)
      agent.localInDriver.sendPacket(localToLeftP, localToRightP)
      agent.leftOutMonitor.addPacket(localToLeftP)
      agent.rightOutMonitor.addPacket(localToRightP)

      val leftToLocalP = NocPacket(id, leftId, 0, randomData)
      val leftToRightP = NocPacket(rightId, leftId, 0, randomData)
      agent.leftInDriver.sendPacket(leftToLocalP, leftToRightP)
      agent.localOutMonitor.addPacket(leftToLocalP)
      agent.rightOutMonitor.addPacket(leftToRightP)

      val rightToLocalP = NocPacket(id, rightId, 0, randomData)
      val rightToLeftP = NocPacket(leftId, rightId, 0, randomData)
      agent.rightInDriver.sendPacket(rightToLocalP, rightToLeftP)
      agent.localOutMonitor.addPacket(rightToLocalP)
      agent.leftOutMonitor.addPacket(rightToLeftP)

      agent.leftOutMonitor.waitComplete()
      agent.rightOutMonitor.waitComplete()
      agent.localOutMonitor.waitComplete()
    }
  }
}
