package qq.cc

import monix.eval.Task
import qq.data.VarBinding
import qq.util.TaskParallel

import scalaz.Tags.Parallel
import scalaz.std.list._
import scalaz.syntax.traverse._
import scalaz.{PlusEmpty, Reader}

object CompiledFilter {

  @inline final def const[J](value: J): CompiledFilter[J] =
    (_: VarBindings[J]) => (_: J) => Task.now(value :: Nil)

  @inline final def func[J](f: CompiledProgram[J]): CompiledFilter[J] =
    (_: VarBindings[J]) => f

  def composeFilters[J](f: CompiledFilter[J], s: CompiledFilter[J]): CompiledFilter[J] = {
    (for {
      fstFun <- Reader(f)
      sndFun <- Reader(s)
    } yield
      fstFun.andThen(_.flatMap(t => Parallel.unwrap(t.traverseM[TaskParallel, J](sndFun.andThen(Parallel(_))))))).run
  }

  def ensequenceCompiledFilters[J]
  (first: CompiledFilter[J], second: CompiledFilter[J]): CompiledFilter[J] =
    (for {fstFun <- Reader(first); sndFun <- Reader(second)} yield { jsv: J =>
      Task.mapBoth(fstFun(jsv), sndFun(jsv)) { (a, b) => a ++ b }
    }).run

  def zipFiltersWith[J]
  (first: CompiledFilter[J], second: CompiledFilter[J], fun: (J, J) => Task[J]): CompiledFilter[J] =
    (for {fstFun <- Reader(first); sndFun <- Reader(second)} yield { jsv: J =>
      Task.mapBoth(fstFun(jsv), sndFun(jsv)) { (f, s) => (f, s).zipped.map(fun) }.map(_.sequence).flatten
    }).run


  def letBinding[J](name: String, as: CompiledFilter[J], in: CompiledFilter[J]): CompiledFilter[J] = {
    (bindings: VarBindings[J]) =>
      (v: J) => {
        val res = as(bindings)(v)
        val newBindings = res.map(_.map(VarBinding[J](name, _)))
        val allBindings = newBindings.map(_.map(b => bindings + (b.name -> b)))
        val o = allBindings.map(_.map(in(_)(v)))
        o.flatMap((ltl: List[Task[List[J]]]) => ltl.sequence[Task, List[J]].map(_.flatten))
      }
  }

  implicit def compiledFilterPlusEmpty: PlusEmpty[CompiledFilter] = new PlusEmpty[CompiledFilter] {
    override def plus[A](a: CompiledFilter[A], b: => CompiledFilter[A]): CompiledFilter[A] =
      composeFilters(a, b)

    override def empty[A]: CompiledFilter[A] =
      (_: VarBindings[A]) => (j: A) => Task.now(j :: Nil)
  }

}
