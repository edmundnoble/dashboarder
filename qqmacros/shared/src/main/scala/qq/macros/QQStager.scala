package qq
package macros

import cats.Eval
import cats.implicits._
import fastparse.core.Parsed
import qq.Platform.Rec._
import qq.cc.{LocalOptimizer, Parser}
import qq.data._
import qq.data.ast._
import qq.util.Recursion.RecursiveFunction

import scala.language.experimental.macros
import scala.reflect.macros.whitebox

// this is used to pre-prepare QQ programs at compile time
object QQStager {

  def qqimpl(c: whitebox.Context)(pieces: c.Tree*): c.Tree = {
    import c.universe._
    def lift[T: Liftable](value: T): Tree = implicitly[Liftable[T]].apply(value)

    implicit def disjunctionLiftable[E: Liftable, A: Liftable]: Liftable[E Either A] =
      Liftable[E Either A](_.fold(
        e => q"scala.util.Left(${lift[E](e)})",
        a => q"scala.util.Right(${lift[A](a)})"
      ))

    implicit def definitionLiftable[F: Liftable]: Liftable[Definition[F]] =
      Liftable { case Definition(name, params, body) => q"qq.data.Definition(${lift(name)}, ${lift(params)}, ${lift(body)})" }

    implicit def mathOpLiftable: Liftable[MathOperator] =
      Liftable {
        case Add => q"qq.data.ast.Add"
        case Subtract => q"qq.data.ast.Subtract"
        case Multiply => q"qq.data.ast.Multiply"
        case Divide => q"qq.data.ast.Divide"
        case Modulo => q"qq.data.ast.Modulo"
        case Equal => q"qq.data.ast.Equal"
        case LTE => q"qq.data.ast.LTE"
        case GTE => q"qq.data.ast.GTE"
        case LessThan => q"qq.data.ast.LessThan"
        case GreaterThan => q"qq.data.ast.GreaterThan"
      }

    implicit def pathLiftable: Liftable[PathComponent] =
      Liftable {
        case CollectResults => q"qq.data.ast.CollectResults"
        case SelectKey(k) => q"qq.data.ast.SelectKey(${lift(k)})"
        case SelectIndex(i) => q"qq.data.ast.SelectIndex(${lift(i)})"
        case SelectRange(s, e) => q"qq.data.ast.SelectRange(${lift(s)}, ${lift(e)})"
      }

    def pathOpLift[A](f: A => Eval[c.universe.Tree]): PathOperationF[A] => Eval[c.universe.Tree] = {
      case PathGet => Eval.now(q"qq.data.ast.PathGet")
      case PathModify(m) => f(m).map { r => q"qq.data.ast.PathModify($r)" }
      case PathSet(s) => f(s).map { r => q"qq.data.ast.PathSet($r)" }
    }

    val liftFilter: RecursiveFunction[FilterAST, c.universe.Tree] = new RecursiveFunction[FilterAST, c.universe.Tree] {
      override def run(value: FilterAST, loop: FilterAST => Eval[c.universe.Tree]): Eval[c.universe.Tree] = {
        val sub: Eval[c.universe.Tree] = value.unFix match {
          case PathOperation(pc, op) => pathOpLift(loop)(op) map { o => q"qq.data.ast.PathOperation[qq.data.FilterAST](${lift(pc)}, $o)" }
          case AsBinding(name, as, in) => (loop(as) |@| loop(in)).map { (a, i) => q"qq.data.ast.AsBinding[qq.data.FilterAST](${lift(name)}, $a, $i)" }
          case Dereference(name) => Eval.now(q"qq.data.ast.Dereference[qq.data.FilterAST](${lift(name)})")
          case ComposeFilters(first, second) => (loop(first) |@| loop(second)).map { (f, s) => q"qq.data.ast.ComposeFilters[qq.data.FilterAST]($f, $s)" }
          case SilenceExceptions(child) => loop(child) map { f => q"qq.data.ast.SilenceExceptions[qq.data.FilterAST]($f)" }
          case EnlistFilter(child) => loop(child) map { f => q"qq.data.ast.EnlistFilter[qq.data.FilterAST]($f)" }
          case EnsequenceFilters(first, second) => (loop(first) |@| loop(second)).map { (f, s) => q"qq.data.ast.EnsequenceFilters[qq.data.FilterAST]($f, $s)" }
          case EnjectFilters(obj) => obj.traverse[Eval, (c.universe.Tree, c.universe.Tree)] { case (k, v) =>
            for {
              ke <- k.traverse(loop).map(e => lift(e.leftMap(lift(_))))
              ve <- loop(v)
            } yield (ke, ve)
          }
            .map { o => q"qq.data.ast.EnjectFilters[qq.data.FilterAST](${lift(o)})" }
          case CallFilter(name: String, params) => params.traverse(loop).map { p => q"qq.data.ast.CallFilter[qq.data.FilterAST](${lift(name)}, $p)" }
          case FilterNot() => Eval.now(q"qq.data.ast.FilterNot[qq.data.FilterAST]()")
          case ConstNumber(v) => Eval.now(q"qq.data.ast.ConstNumber[qq.data.FilterAST](${lift(v)})")
          case ConstBoolean(v) => Eval.now(q"qq.data.ast.ConstBoolean[qq.data.FilterAST](${lift(v)})")
          case ConstString(v) => Eval.now(q"qq.data.ast.ConstString[qq.data.FilterAST](${lift(v)})")
          case FilterMath(first, second, op) => (loop(first) |@| loop(second)).map { (f, s) => q"qq.data.ast.FilterMath[qq.data.FilterAST]($f, $s, ${lift(op)})" }
        }
        sub.map(f => q"qq.util.Fix[qq.data.ast.FilterComponent]($f)")
      }
    }

    implicit def concreteFilterLiftable: Liftable[FilterAST] =
      Liftable(liftFilter(_))

    implicit def programLiftable: Liftable[Program[FilterAST]] = Liftable[Program[FilterAST]](
      value => q"qq.data.Program(${lift(value.defns)}, ${lift(value.main)})"
    )

    val program = c.prefix.tree match {
      // access data of string interpolation
      case Apply(_, List(Apply(_, rawParts))) =>
        if (rawParts.length != 1) {
          c.abort(c.enclosingPosition, "$ detected. qq is not an interpolator, it's for a single string")
        }
        rawParts.head match {
          case Literal(Constant(str: String)) => str
          case _ =>
            c.abort(c.enclosingPosition, "invalid") // TODO: make the error message more readable
        }
      case _ =>
        c.abort(c.enclosingPosition, "invalid") // TODO: make the error message more readable
    }
    val parsedProgram: Program[FilterAST] = Parser.program.parse(program) match {
      case f@Parsed.Failure(_, _, _) =>
        c.abort(c.enclosingPosition, "QQ parsing error: " + f.extra.traced.trace)
      case Parsed.Success(prog, _) => prog
    }
    val optimizedProgram = LocalOptimizer.optimizeProgram(parsedProgram)
    lift(optimizedProgram)
  }

  final implicit class qqops(val sc: StringContext) {

    def qq(pieces: Any*): Program[FilterAST] = macro QQStager.qqimpl

  }

}

