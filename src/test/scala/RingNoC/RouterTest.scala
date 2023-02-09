package RingNoC

import CacheSNN.CacheSnnTest._
import RingNoC.sim._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.sim.StreamReadyRandomizer

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

}
