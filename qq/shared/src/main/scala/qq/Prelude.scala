package qq

import qq.QQCompiler.OrCompilationError

trait Prelude[AnyTy] {
  def all(runtime: QQRuntime[AnyTy]): OrCompilationError[List[CompiledDefinition[AnyTy]]]
}
