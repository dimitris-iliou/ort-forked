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

package org.ossreviewtoolkit.clients.osv

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.result.shouldBeSuccess
import io.kotest.matchers.shouldBe

import java.time.Instant

import kotlinx.serialization.json.JsonObject

import org.ossreviewtoolkit.utils.test.getAssetAsString

private val VULNERABILITY_FOR_PACKAGE_BY_COMMIT_REQUEST = VulnerabilitiesForPackageRequest(
    commit = "6879efc2c1596d11a6a6ad296f80063b558d5e0f"
)
private val VULNERABILITY_FOR_PACKAGE_BY_NAME_AND_VERSION = VulnerabilitiesForPackageRequest(
    pkg = Package(
        name = "jinja2",
        ecosystem = "PyPI"
    ),
    version = "2.4.1"
)
private val VULNERABILITY_FOR_PACKAGE_BY_INVALID_COMMIT_REQUEST = VulnerabilitiesForPackageRequest(
    commit = "6879efc2c1596d11a6a6ad296f80063b558d5e0c"
)

private fun Vulnerability.patchIgnorableFields() =
    copy(
        modified = Instant.EPOCH,
        databaseSpecific = emptyJsonObject.takeIf { databaseSpecific != null },
        affected = affected.mapTo(mutableSetOf()) { affected ->
            affected.copy(ecosystemSpecific = emptyJsonObject.takeIf { affected.ecosystemSpecific != null })
        }
    )

private fun Vulnerability.normalizeUrls(): Vulnerability {
    val references = references.mapTo(mutableSetOf()) { it.copy(url = it.url.removeSuffix("/")) }
    return copy(references = references)
}

private val emptyJsonObject = JsonObject(emptyMap())

private fun List<Vulnerability>.patchFields() = map { it.patchIgnorableFields().normalizeUrls() }

class OsvServiceWrapperFunTest : StringSpec({
    "getVulnerabilitiesForPackage() returns the expected vulnerability when queried by commit" {
        val expectedResult = getAssetAsString("vulnerabilities-by-commit-expected-result.json")

        val result = OsvServiceWrapper().getVulnerabilitiesForPackage(VULNERABILITY_FOR_PACKAGE_BY_COMMIT_REQUEST)

        result.shouldBeSuccess { actualData ->
            val expectedData = OsvService.JSON.decodeFromString<List<Vulnerability>>(expectedResult)
            actualData.patchFields() shouldContainExactlyInAnyOrder expectedData.patchFields()
        }
    }

    "getVulnerabilitiesForPackage() returns the expected vulnerability when queried by name and version" {
        val expectedResult = getAssetAsString("vulnerabilities-by-name-and-version-expected-result.json")

        val result = OsvServiceWrapper().getVulnerabilitiesForPackage(VULNERABILITY_FOR_PACKAGE_BY_NAME_AND_VERSION)

        result.shouldBeSuccess { actualData ->
            val expectedData = OsvService.JSON.decodeFromString<List<Vulnerability>>(expectedResult)
            actualData.patchFields() shouldContainExactlyInAnyOrder expectedData.patchFields()
        }
    }

    "getVulnerabilityIdsForPackages() return the vulnerability IDs for the given batch request" {
        val requests = listOf(
            VULNERABILITY_FOR_PACKAGE_BY_COMMIT_REQUEST,
            VULNERABILITY_FOR_PACKAGE_BY_INVALID_COMMIT_REQUEST,
            VULNERABILITY_FOR_PACKAGE_BY_NAME_AND_VERSION
        )

        val result = OsvServiceWrapper().getVulnerabilityIdsForPackages(requests)

        result.shouldBeSuccess {
            it shouldBe listOf(
                listOf(
                    "CVE-2021-45931",
                    "CVE-2022-33068",
                    "CVE-2023-25193",
                    "CVE-2024-56732",
                    "OSV-2020-484"
                ),
                emptyList(),
                listOf(
                    "GHSA-462w-v97r-4m45",
                    "GHSA-8r7q-cvjq-x353",
                    "GHSA-fqh9-2qgg-h84h",
                    "GHSA-g3rq-g295-4j3m",
                    "GHSA-h5c8-rqwp-cp95",
                    "GHSA-h75v-3vvj-5mfj",
                    "GHSA-hj2j-77xm-mc5v",
                    "GHSA-q2x7-8rv6-6q7h",
                    "PYSEC-2014-8",
                    "PYSEC-2014-82",
                    "PYSEC-2019-217",
                    "PYSEC-2019-220",
                    "PYSEC-2021-66"
                )
            )
        }
    }

    "getVulnerabilityForId() returns the expected vulnerability for the given ID" {
        val expectedResult = getAssetAsString("vulnerability-by-id-expected-result.json")

        val result = OsvServiceWrapper().getVulnerabilityForId("GHSA-xvch-5gv4-984h")

        result.shouldBeSuccess { actualData ->
            val expectedData = OsvService.JSON.decodeFromString<Vulnerability>(expectedResult)
            actualData.patchIgnorableFields() shouldBe expectedData.patchIgnorableFields()
        }
    }

    "getVulnerabilitiesForIds() return the vulnerabilities for the given IDs" {
        val ids = setOf("GHSA-xvch-5gv4-984h", "PYSEC-2014-82")

        val result = OsvServiceWrapper().getVulnerabilitiesForIds(ids)

        result.shouldBeSuccess {
            it.map { vulnerability -> vulnerability.id } shouldContainExactlyInAnyOrder ids
        }
    }
})
