package qq
package cc

import monix.eval.Task
import qq.data._

import scalaz.\/
import scalaz.syntax.plusEmpty._
import scalaz.syntax.std.option._

trait QQRuntime[J] {

  def print(value: J): String

  def platformPrelude: PlatformPrelude[J]

  def enjectFilter(obj: List[(\/[String, CompiledFilter[J]], CompiledFilter[J])]): CompiledFilter[J]

  def enlistFilter(filter: CompiledFilter[J]): CompiledFilter[J]

  @inline final def evaluateLeaf(component: LeafComponent[J]): CompiledFilter[J] = component match {
    case IdFilter() => _ => j => Task.now(j :: Nil)
    case Dereference(name) =>
      (bindings: VarBindings[J]) =>
        bindings.get(name).cata(
          p => (_: J) => Task.now(p.value :: Nil),
          (_: J) => Task.raiseError(QQRuntimeException(s"Variable $name not bound"))
        )
    case ConstNumber(num) => constNumber(num)
    case ConstString(str) => constString(str)
    case SelectKey(key) => selectKey(key)
    case SelectIndex(index) => selectIndex(index)
    case SelectRange(start, end) => selectRange(start, end)
  }

  def selectKey(key: String): CompiledFilter[J]

  def selectIndex(index: Int): CompiledFilter[J]

  def selectRange(start: Int, end: Int): CompiledFilter[J]

  def constNumber(num: Double): CompiledFilter[J]

  def constString(str: String): CompiledFilter[J]

  def addJsValues(first: J, second: J): Task[J]

  def subtractJsValues(first: J, second: J): Task[J]

  def multiplyJsValues(first: J, second: J): Task[J]

  def divideJsValues(first: J, second: J): Task[J]

  def moduloJsValues(first: J, second: J): Task[J]

  def collectResults(f: CompiledFilter[J]): CompiledFilter[J]

}