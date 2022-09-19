// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.api.model

import scala.jdk.CollectionConverters.IteratorHasAsScala

import org.alephium.protocol.Hash
import org.alephium.protocol.vm.StatefulContext
import org.alephium.protocol.vm.lang.{Ast, CompiledContract, CompiledScript}
import org.alephium.serde.serialize
import org.alephium.util.{AVector, DiffMatchPatch, Hex}

final case class CompileScriptResult(
    name: String,
    bytecodeTemplate: String,
    bytecodeDebugPatch: CompileProjectResult.Patch,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig],
    warnings: AVector[String]
)

object CompileScriptResult {
  def from(compiled: CompiledScript): CompileScriptResult = {
    val bytecodeTemplate      = compiled.code.toTemplateString()
    val bytecodeDebugTemplate = compiled.debugCode.toTemplateString()
    val scriptAst             = compiled.ast
    val fields = CompileResult.FieldsSig(
      scriptAst.getTemplateVarsNames(),
      scriptAst.getTemplateVarsTypes(),
      scriptAst.getTemplateVarsMutability()
    )
    CompileScriptResult(
      scriptAst.name,
      bytecodeTemplate,
      CompileProjectResult.diffPatch(bytecodeTemplate, bytecodeDebugTemplate),
      fields = fields,
      functions = AVector.from(scriptAst.funcs.view.map(CompileResult.FunctionSig.from)),
      warnings = compiled.warnings
    )
  }
}

final case class CompileContractResult(
    name: String,
    bytecode: String,
    bytecodeDebugPatch: CompileProjectResult.Patch,
    codeHash: Hash,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig],
    events: AVector[CompileResult.EventSig],
    warnings: AVector[String]
)

object CompileContractResult {
  def from(compiled: CompiledContract): CompileContractResult = {
    val contractAst = compiled.ast
    assume(contractAst.templateVars.isEmpty) // Template variable is disabled right now
    val bytecode      = Hex.toHexString(serialize(compiled.code))
    val debugBytecode = Hex.toHexString(serialize(compiled.debugCode))
    val fields = CompileResult.FieldsSig(
      contractAst.getFieldNames(),
      contractAst.getFieldTypes(),
      contractAst.getFieldMutability()
    )
    CompileContractResult(
      contractAst.name,
      bytecode,
      CompileProjectResult.diffPatch(bytecode, debugBytecode),
      compiled.code.hash,
      fields,
      functions = AVector.from(contractAst.funcs.view.map(CompileResult.FunctionSig.from)),
      events = AVector.from(contractAst.events.map(CompileResult.EventSig.from)),
      warnings = compiled.warnings
    )
  }
}

final case class CompileProjectResult(
    contracts: AVector[CompileContractResult],
    scripts: AVector[CompileScriptResult]
)

object CompileProjectResult {
  def from(
      contracts: AVector[CompiledContract],
      scripts: AVector[CompiledScript]
  ): CompileProjectResult = {
    val compiledContracts = contracts.map(c => CompileContractResult.from(c))
    val compiledScripts   = scripts.map(s => CompileScriptResult.from(s))
    CompileProjectResult(compiledContracts, compiledScripts)
  }

  final case class Patch(value: String) extends AnyVal

  def diffPatch(code: String, debugCode: String): Patch = {
    val diffs = new DiffMatchPatch().diff_main(code, debugCode)
    val diffsConverted = diffs.iterator().asScala.map { diff =>
      diff.operation match {
        case DiffMatchPatch.Operation.EQUAL  => s"=${diff.text.length}"
        case DiffMatchPatch.Operation.DELETE => s"-${diff.text.length}"
        case DiffMatchPatch.Operation.INSERT => s"+${diff.text}"
      }
    }
    Patch(diffsConverted.mkString(""))
  }

  def applyPatchUnsafe(code: String, patch: Patch): String = {
    val pattern   = "[=+-][0-9a-f]*".r
    var index     = 0
    val debugCode = new StringBuilder()
    pattern.findAllIn(patch.value).foreach { part =>
      part(0) match {
        case '=' =>
          val length = part.tail.toInt
          debugCode ++= (code.slice(index, index + length))
          index = index + length
        case '+' =>
          debugCode ++= (part.tail)
        case '-' =>
          index = index + part.tail.toInt
      }
    }
    debugCode.result()
  }
}

object CompileResult {

  final case class FieldsSig(
      names: AVector[String],
      types: AVector[String],
      isMutable: AVector[Boolean]
  )

  final case class FunctionSig(
      name: String,
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      isPublic: Boolean,
      paramNames: AVector[String],
      paramTypes: AVector[String],
      paramIsMutable: AVector[Boolean],
      returnTypes: AVector[String]
  )
  object FunctionSig {
    def from(func: Ast.FuncDef[StatefulContext]): FunctionSig = {
      FunctionSig(
        func.id.name,
        func.usePreapprovedAssets,
        func.useAssetsInContract,
        func.isPublic,
        func.getArgNames(),
        func.getArgTypeSignatures(),
        func.getArgMutability(),
        func.getReturnSignatures()
      )
    }
  }

  final case class EventSig(
      name: String,
      fieldNames: AVector[String],
      fieldTypes: AVector[String]
  )
  object EventSig {
    def from(event: Ast.EventDef): EventSig = {
      EventSig(
        event.name,
        event.getFieldNames(),
        event.getFieldTypeSignatures()
      )
    }
  }
}
