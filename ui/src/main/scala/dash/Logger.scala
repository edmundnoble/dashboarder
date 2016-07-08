package dash

import dash.bindings.{BrowserConsoleAppender, L4JSLogger, Log4JavaScript, PopUpAppender}

import scala.annotation.elidable
import scala.annotation.elidable._

trait Logger {
  @elidable(FINE) def debug(msg: String, e: Exception): Unit
  @elidable(FINE) def debug(msg: String): Unit
  @elidable(INFO) def info(msg: String, e: Exception): Unit
  @elidable(INFO) def info(msg: String): Unit
  @elidable(WARNING) def warn(msg: String, e: Exception): Unit
  @elidable(WARNING) def warn(msg: String): Unit
  @elidable(SEVERE) def error(msg: String, e: Exception): Unit
  @elidable(SEVERE) def error(msg: String): Unit
  @elidable(SEVERE) def fatal(msg: String, e: Exception): Unit
  @elidable(SEVERE) def fatal(msg: String): Unit
}

object LoggerFactory {
  lazy val consoleAppender = new BrowserConsoleAppender
  lazy val popupAppender = new PopUpAppender

  /**
    * Create a logger that outputs to browser console
    */
  def getLogger(name: String): Logger = {
    val nativeLogger = Log4JavaScript.log4javascript.getLogger(name)
    nativeLogger.addAppender(consoleAppender)
    new L4JSLogger(nativeLogger)
  }

  /**
    * Create a logger that outputs to a separate popup window
    */
  def getPopUpLogger(name: String): Logger = {
    val nativeLogger = Log4JavaScript.log4javascript.getLogger(name)
    nativeLogger.addAppender(popupAppender)
    new L4JSLogger(nativeLogger)
  }
}
