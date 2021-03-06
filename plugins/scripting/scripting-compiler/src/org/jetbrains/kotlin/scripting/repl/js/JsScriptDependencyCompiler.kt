/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.repl.js

import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureDescriptor
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.descriptors.konan.kotlinLibrary
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.emptyLoggingContext
import org.jetbrains.kotlin.ir.backend.js.generateJsCode
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsManglerDesc
import org.jetbrains.kotlin.ir.backend.js.utils.NameTables
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.descriptors.IrFunctionFactory
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.serialization.js.ModuleKind
import org.jetbrains.kotlin.storage.LockBasedStorageManager

// Transforms klib into js code in script-friendly way
class JsScriptDependencyCompiler(
    private val configuration: CompilerConfiguration,
    private val nameTables: NameTables,
    private val symbolTable: SymbolTable
) {
    fun compile(dependencies: List<ModuleDescriptor>): String {
        val builtIns: KotlinBuiltIns = dependencies.single { it.allDependencyModules.isEmpty() }.builtIns
        val languageVersionSettings = LanguageVersionSettingsImpl.DEFAULT
        val moduleName = Name.special("<script-dependencies>")
        val storageManager = LockBasedStorageManager.NO_LOCKS
        val moduleDescriptor = ModuleDescriptorImpl(moduleName, storageManager, builtIns, null).also {
            it.setDependencies(dependencies.map { d -> d as ModuleDescriptorImpl } + it)
            it.initialize(PackageFragmentProvider.Empty)
        }

        val typeTranslator = TypeTranslator(symbolTable, languageVersionSettings, moduleDescriptor.builtIns).also {
            it.constantValueGenerator = ConstantValueGenerator(moduleDescriptor, symbolTable)
        }

        val irBuiltIns = IrBuiltIns(builtIns, typeTranslator, symbolTable)
        val functionFactory = IrFunctionFactory(irBuiltIns, symbolTable)
        irBuiltIns.functionFactory = functionFactory
        val jsLinker = JsIrLinker(null, emptyLoggingContext, irBuiltIns, symbolTable, functionFactory, null)

        val moduleFragment = IrModuleFragmentImpl(moduleDescriptor, irBuiltIns)
        val irDependencies = dependencies.map { jsLinker.deserializeFullModule(it, it.kotlinLibrary) }
        val irProviders = generateTypicalIrProviderList(moduleDescriptor, irBuiltIns, symbolTable, deserializer = jsLinker)

        ExternalDependenciesGenerator(symbolTable, irProviders, configuration.languageVersionSettings)
            .generateUnboundSymbolsAsDependencies()
        moduleFragment.patchDeclarationParents()

        val backendContext = JsIrBackendContext(
            moduleDescriptor,
            irBuiltIns,
            symbolTable,
            moduleFragment,
            emptySet(),
            configuration,
            true
        )

        ExternalDependenciesGenerator(symbolTable, irProviders, configuration.languageVersionSettings)
            .generateUnboundSymbolsAsDependencies()
        moduleFragment.patchDeclarationParents()

        moduleFragment.files += irDependencies.flatMap { it.files }

        configuration.put(JSConfigurationKeys.MODULE_KIND, ModuleKind.PLAIN)

        return generateJsCode(backendContext, moduleFragment, nameTables)
    }
}
