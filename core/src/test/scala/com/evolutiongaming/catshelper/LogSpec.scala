package com.evolutiongaming.catshelper

import cats.Id
import cats.arrow.FunctionK
import cats.effect.IO

import scala.util.control.NoStackTrace
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import com.evolutiongaming.catshelper.IOSuite._

class LogSpec extends AnyFunSuite with Matchers {

  import LogSpec._

  test("trace, debug, info, warn, error") {

    val stateT = for {
      log0 <- logOf("source")
      log   = log0.prefixed(">").mapK(FunctionK.id)
      _    <- log.trace("trace")
      _    <- log.debug("debug")
      _    <- log.info("info")
      _    <- log.warn("warn")
      _    <- log.warn("warn", Error)
      _    <- log.error("error")
      _    <- log.error("error", Error)
    } yield {}


    val (state, _) = stateT.run(State(Nil))
    state shouldEqual State(List(
      Action.Error1("> error", Error),
      Action.Error0("> error"),
      Action.Warn1("> warn", Error),
      Action.Warn0("> warn"),
      Action.Info("> info"),
      Action.Debug("> debug"),
      Action.Trace("> trace"),
      Action.OfStr("source")))
  }

  test("trace, debug, info, warn, error with MDC") {

    val mdc = "label" -> "value"

    val stateT = for {
      log0 <- logOf("source")
      log   = log0.prefixed(">").mapK(FunctionK.id)
      _    <- log.trace("trace", Log.Mdc.Lazy(mdc))
      _    <- log.debug("debug", Log.Mdc.Lazy(mdc))
      _    <- log.info("info", Log.Mdc.Lazy(mdc))
      _    <- log.warn("warn", Log.Mdc.Lazy(mdc))
      _    <- log.warn("warn", Error, Log.Mdc.Lazy(mdc))
      _    <- log.error("error", Log.Mdc.Lazy(mdc))
      _    <- log.error("error", Error, Log.Mdc.Lazy(mdc))
    } yield {}


    val (state, _) = stateT.run(State(Nil))
    state shouldEqual State(List(
      Action.Error1("> error", Error, Log.Mdc.Lazy(mdc)),
      Action.Error0("> error", Log.Mdc.Lazy(mdc)),
      Action.Warn1("> warn", Error, Log.Mdc.Lazy(mdc)),
      Action.Warn0("> warn", Log.Mdc.Lazy(mdc)),
      Action.Info("> info", Log.Mdc.Lazy(mdc)),
      Action.Debug("> debug", Log.Mdc.Lazy(mdc)),
      Action.Trace("> trace", Log.Mdc.Lazy(mdc)),
      Action.OfStr("source")))
  }

  test("trace, debug, info, warn, error with preset MDC") {

    val mdc = Log.Mdc.Eager("label" -> "preset")

    val stateT = for {
      log0 <- logOf("source")
      log = log0.withMdc(mdc)
      _ <- log.trace("trace")
      _ <- log.debug("debug")
      _ <- log.info("info")
      _ <- log.warn("warn")
      _ <- log.warn("warn", Error)
      _ <- log.error("error")
      _ <- log.error("error", Error)
    } yield {}

    val (state, _) = stateT.run(State(Nil))
    state shouldEqual State(List(
      Action.Error1("error", Error, mdc),
      Action.Error0("error", mdc),
      Action.Warn1("warn", Error, mdc),
      Action.Warn0("warn", mdc),
      Action.Info("info", mdc),
      Action.Debug("debug", mdc),
      Action.Trace("trace", mdc),
      Action.OfStr("source")))
  }

  test("MDC cleanup") {

    val io = for {
      logOf <- LogOf.slf4j[IO]
      log <- logOf(getClass)
      _ <- log.info("whatever", Log.Mdc.Lazy("k" -> "v"))
    } yield org.slf4j.MDC.getCopyOfContextMap

    io.unsafeRunSync() shouldEqual null
  }

  test("LogOf.log") {
    implicit val instance = logOf

    val (_, logByClass) = LogOf.log[StateT, AnyRef].run(State(Nil))
    val (_, logByName) = LogOf.log[StateT]("some name").run(State(Nil))

    logByClass should not be null
    logByName should not be null
  }
}

object LogSpec {

  val logOf: LogOf[StateT] = {
    val logOf = new LogOf[StateT] {

      def apply(source: String) = {
        StateT { state =>
          val action = Action.OfStr(source)
          (state.add(action), log)
        }
      }

      def apply(source: Class[?]) = {
        StateT { state =>
          val action = Action.OfClass(source)
          (state.add(action), log)
        }
      }
    }

    logOf.mapK(FunctionK.id)
  }

  val log: Log[StateT] = {
    val log = new Log[StateT] {

      def trace(msg: => String, mdc: Log.Mdc) = {
        StateT { state =>
          val action = Action.Trace(msg, mdc)
          (state.add(action), ())
        }
      }

      def debug(msg: => String, mdc: Log.Mdc) = {
        StateT { state =>
          val action = Action.Debug(msg, mdc)
          (state.add(action), ())
        }
      }

      def info(msg: => String, mdc: Log.Mdc) = {
        StateT { state =>
          val action = Action.Info(msg, mdc)
          (state.add(action), ())
        }
      }

      def warn(msg: => String, mdc: Log.Mdc) = {
        StateT { state =>
          val action = Action.Warn0(msg, mdc)
          (state.add(action), ())
        }
      }

      def warn(msg: => String, cause: Throwable, mdc: Log.Mdc) = {
        StateT { state =>
          val action = Action.Warn1(msg, cause, mdc)
          (state.add(action), ())
        }
      }

      def error(msg: => String, mdc: Log.Mdc) = {
        StateT { state =>
          val action = Action.Error0(msg, mdc)
          (state.add(action), ())
        }
      }

      def error(msg: => String, cause: Throwable, mdc: Log.Mdc) = {
        StateT { state =>
          val action = Action.Error1(msg, cause, mdc)
          (state.add(action), ())
        }
      }
    }

    log.mapK(FunctionK.id)
  }


  final case class State(actions: List[Action]) {

    def add(action: Action): State = copy(actions = action :: actions)
  }


  type StateT[A] = cats.data.StateT[Id, State, A]

  object StateT {
    def apply[A](f: State => (State, A)): StateT[A] = cats.data.StateT[Id, State, A](f)
  }


  sealed trait Action

  object Action {
    final case class OfStr(source: String) extends Action
    final case class OfClass(source: Class[?]) extends Action
    final case class Trace(msg: String, mdc: Log.Mdc = Log.Mdc.empty) extends Action
    final case class Debug(msg: String, mdc: Log.Mdc = Log.Mdc.empty) extends Action
    final case class Info(msg: String, mdc: Log.Mdc = Log.Mdc.empty) extends Action
    final case class Warn0(msg: String, mdc: Log.Mdc = Log.Mdc.empty) extends Action
    final case class Warn1(msg: String, throwable: Throwable, mdc: Log.Mdc = Log.Mdc.empty) extends Action
    final case class Error0(msg: String, mdc: Log.Mdc = Log.Mdc.empty) extends Action
    final case class Error1(msg: String, throwable: Throwable, mdc: Log.Mdc = Log.Mdc.empty) extends Action
  }

  case object Error extends RuntimeException with NoStackTrace
}
