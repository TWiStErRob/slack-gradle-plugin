/*
 * Copyright (C) 2022 Slack Technologies, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package slack.gradle

import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import java.io.File
import java.util.ServiceLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.streams.asSequence
import okhttp3.OkHttpClient
import okio.buffer
import okio.sink
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.BuildServiceRegistration
import org.gradle.internal.os.OperatingSystem
import slack.gradle.SlackTools.Companion.SERVICE_NAME
import slack.gradle.SlackTools.Parameters
import slack.gradle.agp.AgpHandler
import slack.gradle.util.JsonTools
import slack.gradle.util.Thermals
import slack.gradle.util.ThermalsWatcher
import slack.gradle.util.shutdown

/** Misc tools for Slack Gradle projects, usable in tasks as a [BuildService] too. */
public abstract class SlackTools : BuildService<Parameters>, AutoCloseable {

  public val agpHandler: AgpHandler by lazy { AgpHandlers.createHandler() }
  public val moshi: Moshi
    get() = JsonTools.MOSHI

  // I really really wish we could do this the "correct" way but Gradle is problematic with its
  // inconsistent expectations of Serializability. Specifically - it seems that `@Nested` does not
  // work for BuildService parameters
  public lateinit var globalConfig: GlobalConfig

  private val logger = Logging.getLogger("SlackTools")
  private val extensions: Map<Class<out SlackToolsExtension>, SlackToolsExtension>

  public lateinit var okHttpClient: Lazy<OkHttpClient>

  // Thermals watching vars
  private var thermalsReporter: ThermalsReporter? = null
  private val thermalsWatcher: ThermalsWatcher?
  private val thermalsExecutor: ExecutorService?
  private var thermalsAtClose: Thermals? = null

  /** Returns the current or latest captured thermals log. */
  public val thermals: Thermals?
    get() {
      return thermalsAtClose ?: peekThermals()
    }

  init {
    logger.debug("SlackTools created")

    // Thermals logging
    if (parameters.logThermals.get()) {
      thermalsExecutor =
        Executors.newSingleThreadExecutor { r ->
          Thread(r, "SlackToolsThermalsHeartbeat").apply { isDaemon = true }
        }
      thermalsWatcher = ThermalsWatcher(logger, ::thermalsFile)
      thermalsWatcher.start(thermalsExecutor)
    } else {
      thermalsWatcher = null
      thermalsExecutor = null
    }

    extensions = buildMap {
      ServiceLoader.load(SlackToolsExtension::class.java, SlackTools::class.java.classLoader)
        .stream()
        .asSequence()
        .forEach { provider ->
          val previous = put(provider.type(), provider.get())
          check(previous == null) {
            "Duplicate extension registered for ${provider.type().simpleName}"
          }
        }
    }

    if (extensions.isNotEmpty()) {
      val dependencies =
        object : SlackToolsDependencies {
          override val okHttpClient: Lazy<OkHttpClient>
            get() = this@SlackTools.okHttpClient
        }
      for (extension in extensions.values) {
        extension.bind(dependencies)
        if (extension is ThermalsReporter) {
          if (thermalsReporter != null) {
            logger.warn("Multiple thermals reporters registered, only the last one will be used")
          }
          thermalsReporter = extension
        }
      }
    }
  }

  private fun thermalsFile(): File {
    return parameters.thermalsOutputFile.asFile.get().apply {
      if (!exists()) {
        parentFile.mkdirs()
        createNewFile()
      }
    }
  }

  private fun peekThermals(): Thermals? {
    return thermalsWatcher?.peek()
  }

  /** Retrieves a loaded instance of [T], if any. */
  public fun <T : SlackToolsExtension> findExtension(type: Class<out T>): T? {
    @Suppress("UNCHECKED_CAST") return extensions[type] as T?
  }

  override fun close() {
    // Close thermals process and save off its current value
    thermalsAtClose = thermalsWatcher?.stop()
    runCatchingWithLog("Failed to report thermals") {
      thermalsAtClose?.let { thermalsAtClose ->
        // Write final thermals to output file
        val thermalsJsonFile = parameters.thermalsOutputJsonFile.get().asFile
        if (thermalsJsonFile.exists()) {
          thermalsJsonFile.delete()
        }
        thermalsJsonFile.parentFile.mkdirs()
        thermalsJsonFile.createNewFile()
        JsonWriter.of(thermalsJsonFile.sink().buffer()).use { writer ->
          moshi.adapter<Thermals>().toJson(writer, thermalsAtClose)
        }
        if (!parameters.offline.get()) {
          thermalsReporter?.reportThermals(thermalsAtClose)
        }
      }
    }

    runCatchingWithLog("Failed to close extension") {
      extensions.values.forEach(SlackToolsExtension::close)
    }

    thermalsExecutor?.shutdown()
    if (okHttpClient.isInitialized()) {
      okHttpClient.value.shutdown()
    }
  }

  private fun runCatchingWithLog(errorMessage: String, block: () -> Unit) {
    runCatching(block).onFailure { t -> logger.error(errorMessage, t) }
  }

  internal companion object {
    internal const val SERVICE_NAME = "slack-tools"

    internal fun register(
      project: Project,
      logThermals: Boolean,
      okHttpClient: Lazy<OkHttpClient>,
      thermalsLogJsonFileProvider: Provider<RegularFile>
    ): Provider<SlackTools> {
      return project.gradle.sharedServices
        .registerIfAbsent(SERVICE_NAME, SlackTools::class.java) {
          parameters.thermalsOutputFile.set(
            project.layout.buildDirectory.file("outputs/logs/last-build-thermals.log")
          )
          parameters.thermalsOutputJsonFile.set(thermalsLogJsonFileProvider)
          parameters.offline.set(project.gradle.startParameter.isOffline)
          parameters.cleanRequested.set(
            project.gradle.startParameter.taskNames.any { it.equals("clean", ignoreCase = true) }
          )
          parameters.logThermals.set(
            project.provider {
              logThermals && !parameters.cleanRequested.get() && OperatingSystem.current().isMacOsX
            }
          )
        }
        .apply {
          get().apply {
            globalConfig = GlobalConfig(project)
            this.okHttpClient = okHttpClient
          }
        }
    }
  }

  public interface Parameters : BuildServiceParameters {
    /** An output file that the thermals process (continuously) writes to during the build. */
    public val thermalsOutputFile: RegularFileProperty
    /** A structured version of [thermalsOutputFile] using JSON. */
    public val thermalsOutputJsonFile: RegularFileProperty
    public val offline: Property<Boolean>
    public val cleanRequested: Property<Boolean>
    public val logThermals: Property<Boolean>
  }
}

public interface ThermalsReporter {
  public fun reportThermals(thermals: Thermals)
}

public interface SlackToolsDependencies {
  public val okHttpClient: Lazy<OkHttpClient>
}

/** An extension for SlackTools. */
public interface SlackToolsExtension : AutoCloseable {
  public fun bind(sharedDependencies: SlackToolsDependencies)
}

public fun Project.slackTools(): SlackTools {
  return slackToolsProvider().get()
}

@Suppress("UNCHECKED_CAST")
public fun Project.slackToolsProvider(): Provider<SlackTools> {
  return (project.gradle.sharedServices.registrations.getByName(SERVICE_NAME)
      as BuildServiceRegistration<SlackTools, Parameters>)
    .service
}
