package nodescala

import scala.language.postfixOps
import scala.collection._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.async
import org.scalatest._
import NodeScala._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite {

  test("A Future should always be created") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }

  test("A Future should never be created") {
    val never = Future.never[Int]

    expectExceptionOfType[TimeoutException] {
      Await.result(never, 1 second)
    }
  }

  test("`Future.delay` stalls for the time duration specified") {
    val delayed = Future.delay(500 milliseconds)
    Await.result(delayed, 600 milliseconds)
  }

  test("`all` succeeds when every future succeeds") {
    val a = Future.delay(100 milliseconds).map(_ => 3)
    val b = Future.delay(200 milliseconds).map(_ => 6)
    val c = Future.delay(300 milliseconds).map(_ => 9)
    val all = Future.all(List(a, b, c))
    assert(Await.result(all, 500 milliseconds) == List(3, 6, 9))
  }

  test("`all` fails when any single future fails") {
    val error = new OutOfMemoryError("Nope.")

    val a = Future.delay(100 milliseconds).map(_ => 3)
    val b = Future.failed(error)
    val c = Future.delay(300 milliseconds).map(_ => 9)
    val all = Future.all(List(a, b, c))

    expectExceptionWithCause(error) {
      Await.result(all, 500 milliseconds)
    }
  }

  test("`any` succeeds when its first future to complete succeeds") {
    val a = Future.delay(300 milliseconds).flatMap(_ => Future.failed(new Exception))
    val b = Future.delay(100 milliseconds).map(_ => "Woop.")
    val c = Future.delay(200 milliseconds).flatMap(_ => Future.failed(new Exception))
    val any = Future.any(List(a, b, c))
    assert(Await.result(any, 500 milliseconds) == "Woop.")
  }

  test("`any` fails when its first future to complete fails") {
    val exception = new Exception
    val a = Future.delay(300 milliseconds).flatMap(_ => Future.failed(new Exception))
    val b = Future.delay(200 milliseconds).map(_ => "Woop.")
    val c = Future.delay(100 milliseconds).flatMap(_ => Future.failed(exception))
    val any = Future.any(List(a, b, c))

    expectExceptionEqualTo(exception) {
      Await.result(any, 500 milliseconds)
    }
  }

  test("`now` returns the future's value when it is already complete") {
    val future = Future.always(17)
    assert(future.now == 17)
  }

  test("`now` fails with an error when the future is not yet complete") {
    val future = Future.never[String]

    expectExceptionOfType[NoSuchElementException] {
      future.now
    }
  }

  test("`continueWith` continues a successful future once it has completed") {
    val future = Future.delay(200 milliseconds).map(_ => 99)
    val continued = future.continueWith { f =>
      f.value.get.map(i => i + 1).getOrElse(0)
    }
    assert(Await.result(continued, 300 milliseconds) == 100)
  }

  test("`continueWith` continues a failed future once it has completed") {
    val future = Future.delay(100 milliseconds).flatMap(_ => Future.failed[Int](new NoSuchElementException))
    val continued = future.continueWith { f =>
      f.value.get.map(i => i + 1).getOrElse(0)
    }
    assert(Await.result(continued, 200 milliseconds) == 0)
  }

  test("`continueWith` handles exceptions thrown by the supplied function") {
    val future = Future.delay(50 milliseconds).map(_ => "Meh")
    val exception = new IllegalStateException("Boom!")
    val continued = future.continueWith { f =>
      throw exception
    }

    expectExceptionEqualTo(exception) {
      Await.result(continued, 100 milliseconds)
    }
  }

  test("`continue` continues a successful future once it has completed") {
    val future = Future.delay(200 milliseconds).map(_ => "sausage")
    val continued = future.continue { result =>
      result.map(i => i + " sandwich").getOrElse("no sandwich :-(")
    }
    assert(Await.result(continued, 300 milliseconds) == "sausage sandwich")
  }

  test("`continue` continues a failed future once it has completed") {
    val future = Future.delay(100 milliseconds).flatMap(_ => Future.failed[String](new NoSuchElementException))
    val continued = future.continue { result =>
      result.map(i => i + " sandwich").getOrElse("no sandwich :-(")
    }
    assert(Await.result(continued, 200 milliseconds) == "no sandwich :-(")
  }

  test("`continue` handles exceptions thrown by the supplied function") {
    val future = Future.delay(20 milliseconds).map(_ => "What?")
    val exception = new Exception("Explode!")
    val continued = future.continueWith { f =>
      throw exception
    }

    expectExceptionEqualTo(exception) {
      Await.result(continued, 100 milliseconds)
    }
  }

  test("`run` executes a future with the ability to cancel it") {
    val number = new AtomicInteger(0)
    val working = Future.run() { cancellationToken =>
      Future {
        while (cancellationToken.nonCancelled) {
          number.incrementAndGet()
        }
      }
    }

    Future.delay(500 milliseconds).onSuccess { case _ =>
      working.unsubscribe()
    }

    val value = number.get
    assert(value >= 500, s"value = $value")
  }

  test("CancellationTokenSource should allow stopping the computation") {
    val cts = CancellationTokenSource()
    val ct = cts.cancellationToken
    val p = Promise[String]()

    async {
      while (ct.nonCancelled) {
        // do work
      }

      p.success("done")
    }

    cts.unsubscribe()
    assert(Await.result(p.future, 1 second) == "done")
  }

  class DummyExchange(val request: Request) extends Exchange {
    @volatile var response = ""
    val loaded = Promise[String]()
    def write(s: String) {
      response += s
    }
    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    @volatile private var started = false
    var handler: Exchange => Unit = null

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }

  test("Listener should serve the next request as a future") {
    val dummy = new DummyListener(8191, "/test")
    val subscription = dummy.start()

    def test(req: Request) {
      val f = dummy.nextRequest()
      dummy.emit(req)
      val (reqReturned, _) = Await.result(f, 1 second)

      assert(reqReturned == req)
    }

    test(immutable.Map("StrangeHeader" -> List("StrangeValue1")))
    test(immutable.Map("StrangeHeader" -> List("StrangeValue2")))

    subscription.unsubscribe()
  }

  test("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield kv.toString + "\n"
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield kv.toString + "\n").mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))

    dummySubscription.unsubscribe()
  }

  private def expectException[E <: Throwable](check: E => Unit)(toRun: => Unit): E = {
    try {
      toRun
      fail()
    } catch {
      case e: E => {
        check(e)
        return e
      }
    }
  }

  private def expectExceptionOfType[E <: Throwable] = expectException((e: E) => { }) _

  private def expectExceptionEqualTo[E <: Throwable](exception: E) = expectException((e: E) => assert(exception == e)) _

  private def expectExceptionWithCause[E <: Throwable](exception: E) = expectException((e: E) => assert(exception == e.getCause)) _
}
