package qq

import fastparse.core.{ParseError, Parsed}
import monix.eval.Task

import scalaz.\/
import scalaz.std.list._
import scalaz.syntax.traverse._
import scalaz.syntax.either._
import Util._
import matryoshka._
import qq.Compiler.CompiledFilter
import shapeless.{Nat, Sized}

object Runner {

  def parseAndCompile(compiler: Compiler, program: String, optimize: Boolean = true): \/[Exception, CompiledFilter[compiler.type]] = {
    val parsed = Parser.program.parse(program)
    parsed match {
      case Parsed.Success((definitions, main), _) =>
        if (optimize) {
          compiler.compileProgram(
            definitions.map(Definition.body.modify(Optimizer.optimize)),
            Optimizer.optimize(main)
          )
        } else {
          compiler.compileProgram(definitions, main)
        }
      case f@Parsed.Failure(_, _, _) =>
        new ParseError(f).left
    }
  }

  def run(compiler: Compiler, qqProgram: String, optimize: Boolean = true)(input: List[compiler.AnyTy]): Task[List[compiler.AnyTy]] = {
    parseAndCompile(compiler, qqProgram, optimize).fold(
      Task.raiseError,
      input.traverseM(_)
    )
  }

}
