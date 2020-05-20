/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.addOptionTag
import com.intellij.util.getAttributeBooleanValue
import org.jdom.Element
import org.jetbrains.kotlin.idea.core.script.ScriptDefinitionsManager
import org.jetbrains.kotlin.idea.core.script.settings.KotlinScriptingSettings.KotlinScriptDefinitionValue.Companion.DEFAULT
import org.jetbrains.kotlin.idea.util.application.getServiceSafe
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.KotlinScriptDefinitionFromAnnotatedTemplate

@State(
    name = "KotlinScriptingSettings",
    storages = [Storage("kotlinScripting.xml")]
)
class KotlinScriptingSettings : PersistentStateComponent<Element> {

    /**
     * true if notification about multiple script definition applicable for one script file is suppressed
     */
    var suppressDefinitionsCheck = false

    private var scriptDefinitions = linkedMapOf<KotlinScriptDefinitionKey, KotlinScriptDefinitionValue>()

    override fun getState(): Element {
        val definitionsRootElement = Element("KotlinScriptingSettings")

        if (suppressDefinitionsCheck) {
            definitionsRootElement.addOptionTag(
                KotlinScriptingSettings::suppressDefinitionsCheck.name,
                suppressDefinitionsCheck.toString()
            )
        }

        if (scriptDefinitions.isEmpty()) {
            return definitionsRootElement
        }

        for (scriptDefinition in scriptDefinitions) {
            definitionsRootElement.addScriptDefinitionContentElement(scriptDefinition.key, scriptDefinition.value)
        }

        return definitionsRootElement
    }

    override fun loadState(state: Element) {
        state.getOptionTag(KotlinScriptingSettings::suppressDefinitionsCheck.name)?.let {
            suppressDefinitionsCheck = it
        }

        val scriptDefinitionsList = state.getChildren(SCRIPT_DEFINITION_TAG)
        for (scriptDefinitionElement in scriptDefinitionsList) {
            scriptDefinitions[scriptDefinitionElement.toKey()] = scriptDefinitionElement.toValue()
        }
    }

    fun setOrder(scriptDefinition: ScriptDefinition, order: Int) {
        scriptDefinitions[scriptDefinition.toKey()] =
            scriptDefinitions[scriptDefinition.toKey()]?.copy(order = order) ?: KotlinScriptDefinitionValue(order)
    }


    fun setEnabled(order: Int, scriptDefinition: ScriptDefinition, isEnabled: Boolean) {
        scriptDefinitions[scriptDefinition.toKey()] =
            scriptDefinitions[scriptDefinition.toKey()]?.copy(isEnabled = isEnabled) ?: KotlinScriptDefinitionValue(
                order,
                isEnabled = isEnabled
            )
    }

    fun setAutoReloadConfigurations(order: Int, scriptDefinition: ScriptDefinition, autoReloadScriptDependencies: Boolean) {
            scriptDefinitions[scriptDefinition.toKey()] =
                scriptDefinitions[scriptDefinition.toKey()]?.copy(autoReloadConfigurations = autoReloadScriptDependencies)
                    ?: KotlinScriptDefinitionValue(
                        order,
                        autoReloadConfigurations = autoReloadScriptDependencies
                    )
    }

    private fun isGradleScriptDefinition(definition: ScriptDefinition, project: Project): List<ScriptDefinition> {
        /*val gradleScriptDefinitions = isGradleScriptDefinition(definition, project)
        if (gradleScriptDefinitions.isNotEmpty()) {
            // TODO: enable all gradle definitions
            for (scriptDefinition in gradleScriptDefinitions) {
                scriptDefinitions[scriptDefinition.toKey()] =
                    scriptDefinitions[scriptDefinition.toKey()]?.copy(autoReloadConfigurations = autoReloadScriptDependencies)
                        ?: KotlinScriptDefinitionValue(
                            order,
                            autoReloadConfigurations = autoReloadScriptDependencies
                        )
            }
        }*/
        val pattern = definition.asLegacyOrNull<KotlinScriptDefinitionFromAnnotatedTemplate>()?.scriptFilePattern ?: return emptyList()
        if (pattern.matches("build.gradle.kts") || pattern.matches("setings.gradle.kts") || pattern.matches("init.gradle.kts")) {
            return ScriptDefinitionsManager.getInstance(project).getAllDefinitions().filter {
                val pattern = definition.asLegacyOrNull<KotlinScriptDefinitionFromAnnotatedTemplate>()?.scriptFilePattern ?: return@filter false
                pattern.matches("build.gradle.kts") || pattern.matches("setings.gradle.kts") || pattern.matches("init.gradle.kts")
            }
        }
        return emptyList()
    }

    fun getScriptDefinitionOrder(scriptDefinition: ScriptDefinition): Int? {
        return scriptDefinitions[scriptDefinition.toKey()]?.order
    }

    fun isScriptDefinitionEnabled(scriptDefinition: ScriptDefinition): Boolean {
        return scriptDefinitions[scriptDefinition.toKey()]?.isEnabled ?: DEFAULT.isEnabled
    }

    fun autoReloadConfigurations(scriptDefinition: ScriptDefinition): Boolean {
        return scriptDefinitions[scriptDefinition.toKey()]?.autoReloadConfigurations ?: DEFAULT.autoReloadConfigurations
    }

    private data class KotlinScriptDefinitionKey(
        val definitionName: String,
        val className: String
    )

    private data class KotlinScriptDefinitionValue(
        val order: Int,
        val isEnabled: Boolean = true,
        val autoReloadConfigurations: Boolean = false
    ) {
        companion object {
            val DEFAULT = KotlinScriptDefinitionValue(Integer.MAX_VALUE)
        }
    }

    private fun Element.toKey() = KotlinScriptDefinitionKey(
        getAttributeValue(KotlinScriptDefinitionKey::definitionName.name),
        getAttributeValue(KotlinScriptDefinitionKey::className.name)
    )

    private fun ScriptDefinition.toKey() =
        KotlinScriptDefinitionKey(this.name, this.definitionId)

    private fun Element.addScriptDefinitionContentElement(definition: KotlinScriptDefinitionKey, settings: KotlinScriptDefinitionValue) {
        addElement(SCRIPT_DEFINITION_TAG).apply {
            setAttribute(KotlinScriptDefinitionKey::className.name, definition.className)
            setAttribute(KotlinScriptDefinitionKey::definitionName.name, definition.definitionName)

            addElement(KotlinScriptDefinitionValue::order.name).apply {
                text = settings.order.toString()
            }

            if (!settings.isEnabled) {
                addElement(KotlinScriptDefinitionValue::isEnabled.name).apply {
                    text = settings.isEnabled.toString()
                }
            }
            if (settings.autoReloadConfigurations) {
                addElement(KotlinScriptDefinitionValue::autoReloadConfigurations.name).apply {
                    text = settings.autoReloadConfigurations.toString()
                }
            }
        }
    }

    private fun Element.addElement(name: String): Element {
        val element = Element(name)
        addContent(element)
        return element
    }

    private fun Element.toValue(): KotlinScriptDefinitionValue {
        val order = getChildText(KotlinScriptDefinitionValue::order.name)?.toInt()
            ?: DEFAULT.order
        val isEnabled = getChildText(KotlinScriptDefinitionValue::isEnabled.name)?.toBoolean()
            ?: DEFAULT.isEnabled
        val autoReloadScriptDependencies = getChildText(KotlinScriptDefinitionValue::autoReloadConfigurations.name)?.toBoolean()
            ?: DEFAULT.autoReloadConfigurations

        return KotlinScriptDefinitionValue(order, isEnabled, autoReloadScriptDependencies)
    }

    private fun Element.getOptionTag(name: String) =
        getChildren("option").firstOrNull { it.getAttribute("name").value == name }?.getAttributeBooleanValue("value")

    companion object {
        fun getInstance(project: Project): KotlinScriptingSettings = project.getServiceSafe()

        private const val SCRIPT_DEFINITION_TAG = "scriptDefinition"

    }
}