package qq
package data

import cats.Functor
import cats.implicits._

object Program {
  type Definitions[F] = Vector[Definition[F]]

  implicit private final val definitionsFunctor: Functor[Definitions] =
    catsStdInstancesForVector.compose[Definition](Definition.definitionFunctor)

  implicit def programFunctor: Functor[Program] = new Functor[Program] {
    override def map[A, B](fa: Program[A])(f: (A) => B): Program[B] = Program[B](definitionsFunctor.map(fa.defns)(f), f(fa.main))
  }
}

case class Program[F](defns: Program.Definitions[F], main: F)

