package dash.app

import dash.StorageProgram
import qq.QQCompiler.{CompiledFilter, OrCompilationError}
import qq._
import scodec.bits.BitVector
import upickle.Js
import com.thoughtworks.each.Monadic._

trait DashApp {

  def getCompiledProgram(qqProgram: String): StorageProgram[OrCompilationError[CompiledFilter[Js.Value]]] = monadic[StorageProgram] {

    import qq.FilterProtocol._
    import StorageProgram._

    val hash = qqProgram.hashCode.toString
    val programInStorage = get(hash).each

    val decodedOptimizedProgram = programInStorage match {
      case None =>
        val parsedQQProgram = Parser.program.parse(qqProgram).get.value
        val optimizedProgram = Optimizer.optimize(parsedQQProgram)
        val encodedOptimizedProgram = programCodec.encode(optimizedProgram).require
        val out = encodedOptimizedProgram.toBase64
        update(hash, out).each
        optimizedProgram
      case Some(encodedProgram) =>
        programCodec.decode(BitVector.fromBase64(encodedProgram).get).require.value
    }

    QQCompiler.compileProgram[Js.Value](UpickleRuntime: QQRuntime[Js.Value], decodedOptimizedProgram)
  }

}