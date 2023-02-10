package RingNoC

import CacheSNN.CacheSnnTest._
import RingNoC.sim._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.sim.StreamReadyRandomizer

import scala.util
import scala.util.Random

class RouterDeMuxTest extends AnyFunSuite {
  val routerConfig = RouterConfig(0, 16)
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
      nocOutAsserter.waitComplete()
      localOutAsserter.waitComplete()
    }
  }
}

class RouterDeMuxLocalTest extends AnyFunSuite {

  def tb(routerConfig: RouterConfig): Unit ={
    val complied = simConfig.compile(new RouterDeMuxLocal(routerConfig))
    complied.doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val nocInDriver = new NocInterfaceDriver(dut.io.localIn, dut.clockDomain)
      val leftN = if (routerConfig.n % 2 == 0) routerConfig.n / 2 - 1 else routerConfig.n / 2
      val rightN = routerConfig.n / 2
      val leftDest = (1 to leftN).map { leftDis =>
        (routerConfig.coordinate - leftDis + routerConfig.n) % routerConfig.n
      }
      val rightDest = (1 to rightN).map { rightDis =>
        (routerConfig.coordinate + rightDis) % routerConfig.n
      }

      def genPacket(dest:Int):NocPacket = {
        NocPacket(
          dest = dest,
          src = routerConfig.coordinate,
          custom = BigInt(48, Random),
          data = Seq.fill(Random.nextInt(256))(BigInt(64, Random))
        )
      }
      val leftPacket = leftDest.map(genPacket)
      val rightPacket = rightDest.map(genPacket)
      nocInDriver.sendPacket(leftPacket++rightPacket)
      StreamReadyRandomizer(dut.io.nocLeftOut, dut.clockDomain)
      StreamReadyRandomizer(dut.io.nocRightOut, dut.clockDomain)
      val leftAsserter = new NocInterfaceAsserter(dut.io.nocLeftOut, dut.clockDomain)
      val rightAsserter = new NocInterfaceAsserter(dut.io.nocRightOut, dut.clockDomain)
      leftAsserter.addPacket(leftPacket)
      rightAsserter.addPacket(rightPacket)
      leftAsserter.waitComplete()
      rightAsserter.waitComplete()
    }
  }

  test("route test"){
    val n = Random.nextInt(14) + 2
    val configs = Seq(
      RouterConfig(0, 4),
      RouterConfig(0, 3),
      RouterConfig(2, 5),
      RouterConfig(Random.nextInt(n), n)
    )
    configs.foreach(tb)
  }

  test("error packet test"){
    intercept[Throwable]{
      val complied = simConfig.compile(new RouterDeMuxLocal(RouterConfig(1, 4)))
      complied.doSim{dut =>
        dut.clockDomain.forkStimulus(2)
        SimTimeout(100000)
        val errorPacket = NocPacket(
          dest = 1,
          src = 1,
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
  val routerConfig = RouterConfig(Random.nextInt(4), 4)
  val complied = simConfig.compile(new Router(routerConfig))

  case class RouterAgent(dut:Router){
    val leftInDriver = new NocInterfaceDriver(dut.io.leftIn, dut.clockDomain)
    val rightInDriver = new NocInterfaceDriver(dut.io.rightIn, dut.clockDomain)
    val localInDriver = new NocInterfaceDriver(dut.io.local.send, dut.clockDomain)

    val leftOutMonitor = new NocInterfaceAsserter(dut.io.leftOut, dut.clockDomain)
    val rightOutMonitor = new NocInterfaceAsserter(dut.io.rightOut, dut.clockDomain)
    val localOutMonitor = new NocInterfaceAsserter(dut.io.local.rec, dut.clockDomain)
  }

  def initDut(dut:Router):RouterAgent = {
    dut.clockDomain.forkStimulus(2)
    SimTimeout(1000000)
    StreamReadyRandomizer(dut.io.leftOut, dut.clockDomain)
    StreamReadyRandomizer(dut.io.rightOut, dut.clockDomain)
    StreamReadyRandomizer(dut.io.local.rec, dut.clockDomain)
    RouterAgent(dut)
  }

  test("single direction test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      val id = routerConfig.coordinate
      val leftId = (id+routerConfig.n-1) % routerConfig.n
      val rightId = (id + 1) % routerConfig.n
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

  test("random test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      val id = routerConfig.coordinate
      val leftId = (id + routerConfig.n - 1) % routerConfig.n
      val rightId = (id + 1) % routerConfig.n

      def genPacket(src:Int, dest0:Int, dest1:Int): Seq[NocPacket] ={
        Seq.fill(1000)(
          NocPacket(
            dest = if(Random.nextBoolean()) dest0 else dest1,
            src = src,
            custom = BigInt(48, Random),
            data = Seq.fill(Random.nextInt(256))(BigInt(64, Random))
          )
        )
      }

      val leftSendPacket = genPacket(leftId, id, rightId)
      val localSendPacket = genPacket(id, leftId, rightId)
      val rightSendPacket = genPacket(rightId, id, leftId)
      agent.leftInDriver.sendPacket(leftSendPacket)
      agent.localInDriver.sendPacket(localSendPacket)
      agent.rightInDriver.sendPacket(rightSendPacket)

      val leftRecPacket = (localSendPacket ++ rightSendPacket).filter(p => p.dest==leftId)
      val localRecPacket = (leftSendPacket ++ rightSendPacket).filter(p => p.dest==id)
      val rightRecPacket = (localSendPacket ++ leftSendPacket).filter(p => p.dest==rightId)
      agent.localOutMonitor.addPacket(localRecPacket)
      agent.leftOutMonitor.addPacket(leftRecPacket)
      agent.rightOutMonitor.addPacket(rightRecPacket)
      agent.localOutMonitor.waitComplete()
      agent.leftOutMonitor.waitComplete()
      agent.rightOutMonitor.waitComplete()
    }
  }
}
