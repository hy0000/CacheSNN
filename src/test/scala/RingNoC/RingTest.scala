package RingNoC

import CacheSNN.CacheSnnTest._
import RingNoC.sim._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.StreamReadyRandomizer

import scala.util.Random

class RingModule(n:Int) extends Component {
  val local = Vec(slave(NocInterfaceLocal()), n)

  val ringBus = Ring()
  for(i <- 0 until n){
    ringBus.addNode(local(i), i)
  }
}

class RingTest extends AnyFunSuite {
  val n = 6//Random.nextInt(14) + 2
  println(s"RingTest start with n==$n")
  val complied = simConfig.compile(new RingModule(n))
  def randomData = Seq.fill(Random.nextInt(256))(BigInt(64, Random))

  case class RingAgent(dut:RingModule){
    val driver = dut.local.map(noc => new NocInterfaceDriver(noc.send, dut.clockDomain))
    val asserter = dut.local.map(noc => new NocInterfaceAsserter(noc.rec, dut.clockDomain))

    def waitComplete(): Unit ={
      asserter.foreach(_.waitComplete())
    }
  }

  def initDut(dut:RingModule): RingAgent ={
    dut.clockDomain.forkStimulus(2)
    SimTimeout(1000000)
    dut.local.foreach(noc => StreamReadyRandomizer(noc.rec, dut.clockDomain))
    RingAgent(dut)
  }

  test("point to point test"){
    complied.doSim{ dut =>
      val agent = initDut(dut)
      for(src <- 0 until n){
        for(dest <- 0 until n){
          if(src!=dest){
            val p = NocPacket(dest = dest, src = src, 0, randomData)
            agent.driver(src).sendPacket(p)
            agent.asserter(dest).addPacket(p)
            agent.asserter(dest).waitComplete()
          }
        }
      }
    }
  }

  test("delay test"){
    complied.doSim { dut =>
      val agent = initDut(dut)
      val p = NocPacket(dest = 1, src = 0, 0, Seq(BigInt(0)))
      agent.driver(0).sendPacket(p)
      agent.asserter(1).addPacket(p)
      dut.clockDomain.waitSamplingWhere(dut.local(0).send.valid.toBoolean)
      dut.clockDomain.waitSampling(6)
      assert(dut.local(1).rec.valid.toBoolean)
    }
  }
  /*
  test("dead lock test"){
    simConfig.compile(new RingModule(4)).doSim{ dut =>
      dut.clockDomain.forkStimulus(2)
      SimTimeout(100000)
      val agent = RingAgent(dut)
      for(src <- 0 until 4){
        val dest = (src + 2) % 4
        val data = (0 until 1024).map(i => BigInt(i))
        val p = NocPacket(dest, src, 0, data)
        agent.driver(src).sendPacket(p)
        agent.asserter(dest).addPacket(p)
      }
      agent.waitComplete()
    }
  }

  test("random test"){
    complied.doSim(273262820) { dut =>
      val agent = initDut(dut)
      val packets = Array.tabulate(n, n){(src, dest) =>
        if(src != dest){
          Seq.fill(Random.nextInt(128/n))(
            NocPacket(dest = dest, src = src, custom = BigInt(48, Random), data = randomData)
          )
        }else{
          Seq()
        }
      }
      for (src <- 0 until n) {
        val thisPacket = Random.shuffle(packets(src).flatten.toSeq)
        agent.driver(src).sendPacket(thisPacket)
        for(p <- thisPacket){
          agent.asserter(p.dest).addPacket(p)
        }
      }
      for(i <- 0 until n){
        agent.asserter(i).waitComplete()
      }
    }
  }
   */
}
