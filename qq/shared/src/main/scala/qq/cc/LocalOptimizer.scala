package qq
package cc

import matryoshka.Recursive.ops._
import matryoshka._
import qq.Platform.Rec._
import qq.data._
import qq.util.Recursion

import scala.language.higherKinds
import scalaz.std.option._
import scalaz.syntax.foldable1._
import scalaz.syntax.functor._
import scalaz.syntax.plus._
import scalaz.{Kleisli, NonEmptyList}

// QQ's local optimizer; not a whole-program optimizer, but optimizes a single filter.
object LocalOptimizer {

  import FilterComponent._

  // runs a function until it returns none
  // like in Matryoshka but tail recursive
  @annotation.tailrec
  final def repeatedly[A](f: A => Option[A])(expr: A): A =
  f(expr) match {
    case None => expr
    case Some(e) => repeatedly(f)(e)
  }

  // an optimization is a function with type Filter => Option[Filter] with return values meaning:
  // None => The optimization does not apply to this filter structure
  // Some(newFilter) => The optimization produced newFilter from this filter
  type LocalOptimization[F] = Kleisli[Option, F, F]

  // The identity filter is an identity with respect to composition of filters
  final def idCompose[T[_[_]] : Recursive : Corecursive]: LocalOptimization[T[FilterComponent]] = Kleisli { fr =>
    fr.project.map(_.project) match {
      case ComposeFilters(IdFilter(), s) => Some(embed[T](s))
      case ComposeFilters(f, IdFilter()) => Some(embed[T](f))
      case _ => None
    }
  }

  // The collect and enlist operations are inverses
  final def collectEnlist[T[_[_]] : Recursive]: LocalOptimization[T[FilterComponent]] = Kleisli { fr =>
    fr.project.map(_.project) match {
      case EnlistFilter(CollectResults(f)) => Some(f)
      case CollectResults(EnlistFilter(f)) => Some(f)
      case _ => None
    }
  }

  object MathOptimizations {
    // reduces constant math filters to their results
    final def constReduce[T[_[_]] : Recursive : Corecursive]: LocalOptimization[T[FilterComponent]] = Kleisli { fr =>
      fr.project.map(_.project) match {
        case AddFilters(ConstNumber(f), ConstNumber(s)) => Some(embed[T](ConstNumber(f + s)))
        case SubtractFilters(ConstNumber(f), ConstNumber(s)) => Some(embed[T](ConstNumber(f - s)))
        case MultiplyFilters(ConstNumber(f), ConstNumber(s)) => Some(embed[T](ConstNumber(f * s)))
        case DivideFilters(ConstNumber(f), ConstNumber(s)) => Some(embed[T](ConstNumber(f / s)))
        case ModuloFilters(ConstNumber(f), ConstNumber(s)) => Some(embed[T](ConstNumber(f % s)))
        case _ => None
      }
    }
  }

  // a list of all of the local optimizations available
  @inline final def localOptimizations[T[_[_]] : Recursive : Corecursive]: NonEmptyList[LocalOptimization[T[FilterComponent]]] =
  NonEmptyList(idCompose[T], collectEnlist[T], MathOptimizations.constReduce[T])

  // a function applying each of the local optimizations available, in rounds,
  // until none of the optimizations applies anymore
  @inline final def localOptimizationsƒ[T[_[_]] : Recursive : Corecursive]: T[FilterComponent] => T[FilterComponent] =
  repeatedly(localOptimizations[T].foldLeft1(_ <+> _).run)

  // recursively applied localOptimizationsƒ deep into a filter
  @inline final def optimizeFilter[T[_[_]] : Recursive : Corecursive](filter: T[FilterComponent]): T[FilterComponent] =
  Recursion.transCataT(localOptimizationsƒ[T]).apply(filter)

  @inline final def optimizeDefinition[T[_[_]] : Recursive : Corecursive](defn: Definition[T[FilterComponent]]): Definition[T[FilterComponent]] =
    defn.copy(body = optimizeFilter(defn.body))

  @inline final def optimizeProgram[T[_[_]] : Recursive : Corecursive](program: Program[T[FilterComponent]]): Program[T[FilterComponent]] =
    program.copy(defns = program.defns.map(optimizeDefinition[T]), main = optimizeFilter(program.main))

}