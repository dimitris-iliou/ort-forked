/*
 * Copyright (C) 2022 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.packagemanagers.python

import java.io.File

import org.apache.logging.log4j.kotlin.logger

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.plugins.packagemanagers.python.utils.PythonInspector
import org.ossreviewtoolkit.plugins.packagemanagers.python.utils.toOrtPackages
import org.ossreviewtoolkit.plugins.packagemanagers.python.utils.toPackageReferences
import org.ossreviewtoolkit.utils.common.CommandLineTool
import org.ossreviewtoolkit.utils.common.safeDeleteRecursively
import org.ossreviewtoolkit.utils.common.withoutPrefix
import org.ossreviewtoolkit.utils.common.withoutSuffix
import org.ossreviewtoolkit.utils.ort.createOrtTempFile

import org.semver4j.RangesListFactory
import org.semver4j.Semver

internal object PoetryCommand : CommandLineTool {
    override fun command(workingDir: File?) = "poetry"

    override fun transformVersion(output: String) = output.substringAfter("version ").removeSuffix(")")
}

/**
 * [Poetry](https://python-poetry.org/) package manager for Python.
 */
class Poetry(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, "Poetry", analysisRoot, analyzerConfig, repoConfig) {
    companion object {
        /**
         * The name of the build system requirements and information file used by modern Python packages.
         */
        internal const val PYPROJECT_FILENAME = "pyproject.toml"
    }

    class Factory : AbstractPackageManagerFactory<Poetry>("Poetry") {
        override val globsForDefinitionFiles = listOf("poetry.lock")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Poetry(type, analysisRoot, analyzerConfig, repoConfig)
    }

    override fun resolveDependencies(definitionFile: File, labels: Map<String, String>): List<ProjectAnalyzerResult> {
        val scopeName = parseScopeNamesFromPyproject(definitionFile.resolveSibling(PYPROJECT_FILENAME))
        val resultsForScopeName = scopeName.associateWith { inspectLockfile(definitionFile, it) }

        val packages = resultsForScopeName
            .flatMap { (_, results) -> results.packages }
            .toOrtPackages()
            .distinctBy { it.id }
            .toSet()

        val project = Project.EMPTY.copy(
            id = Identifier(
                type = projectType,
                namespace = "",
                name = definitionFile.relativeTo(analysisRoot).path,
                version = VersionControlSystem.getCloneInfo(definitionFile.parentFile).revision
            ),
            definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
            scopeDependencies = resultsForScopeName.mapTo(mutableSetOf()) { (scopeName, results) ->
                Scope(scopeName, results.resolvedDependenciesGraph.toPackageReferences())
            },
            vcsProcessed = processProjectVcs(definitionFile.parentFile)
        )

        return listOf(ProjectAnalyzerResult(project, packages))
    }

    /**
     * Return the result of running Python inspector against a requirements file generated by exporting the dependencies
     * in [lockfile] with the scope named [dependencyGroupName] via the `poetry export` command.
     */
    private fun inspectLockfile(lockfile: File, dependencyGroupName: String): PythonInspector.Result {
        val workingDir = lockfile.parentFile
        val requirementsFile = createOrtTempFile("requirements.txt")

        logger.info { "Generating '${requirementsFile.name}' file in '$workingDir' directory..." }

        val options = listOf(
            "export",
            "--without-hashes",
            "--format=requirements.txt",
            "--only=$dependencyGroupName"
        )

        val requirements = PoetryCommand.run(workingDir, *options.toTypedArray()).requireSuccess().stdout
        requirementsFile.writeText(requirements)

        val poetryAnalyzerConfig = analyzerConfig
            .withPackageManagerOption(managerName, "overrideProjectType", projectType)

        return Pip(managerName, analysisRoot, poetryAnalyzerConfig, repoConfig).runPythonInspector(requirementsFile) {
            detectPythonVersion(workingDir)
        }.also {
            requirementsFile.parentFile.safeDeleteRecursively()
        }
    }

    private fun detectPythonVersion(workingDir: File): String? {
        val pyprojectFile = workingDir.resolve(PYPROJECT_FILENAME)
        val constraint = getPythonVersionConstraint(pyprojectFile) ?: return null
        return getPythonVersion(constraint)?.also {
            logger.info { "Detected Python version '$it' from '$constraint'." }
        }
    }
}

internal fun parseScopeNamesFromPyproject(pyprojectFile: File): Set<String> {
    // The implicit "main" scope is always present.
    val scopes = mutableSetOf("main")

    if (!pyprojectFile.isFile) return scopes

    pyprojectFile.readLines().mapNotNullTo(scopes) { line ->
        // Handle both "[tool.poetry.<scope>-dependencies]" and "[tool.poetry.group.<scope>.dependencies]" syntax.
        val poetryEntry = line.withoutPrefix("[tool.poetry.")
        poetryEntry.withoutPrefix("group.") { poetryEntry }
            .withoutSuffix("dependencies]")
            ?.trimEnd('-', '.')
            ?.takeUnless { it.isEmpty() }
    }

    return scopes
}

internal fun getPythonVersion(constraint: String): String? {
    val rangeLists = constraint.split(',')
        .map { RangesListFactory.create(it) }
        .takeIf { it.isNotEmpty() } ?: return null

    return PYTHON_VERSIONS.lastOrNull { version ->
        rangeLists.all { rangeList ->
            val semver = Semver.coerce(version)
            semver != null && rangeList.isSatisfiedBy(semver)
        }
    }
}

internal fun getPythonVersionConstraint(pyprojectTomlFile: File): String? {
    val dependenciesSection = getTomlSectionContent(pyprojectTomlFile, "tool.poetry.dependencies")
        ?: return null

    return dependenciesSection.split('\n').firstNotNullOfOrNull {
        it.trim().withoutPrefix("python = ")
    }?.removeSurrounding("\"")
}

private fun getTomlSectionContent(tomlFile: File, sectionName: String): String? {
    val lines = tomlFile.takeIf { it.isFile }?.readLines() ?: return null

    val sectionHeaderIndex = lines.indexOfFirst { it.trim() == "[$sectionName]" }
    if (sectionHeaderIndex == -1) return null

    val sectionLines = lines.subList(sectionHeaderIndex + 1, lines.size).takeWhile { !it.trim().startsWith('[') }
    return sectionLines.joinToString("\n")
}
