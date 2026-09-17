package com.github.fabiopelliccia.copilotsessionsimportexport.ui

import com.github.fabiopelliccia.copilotsessionsimportexport.core.CopilotSessionsBundle
import com.github.fabiopelliccia.copilotsessionsimportexport.core.IdeSessionRecord
import com.github.fabiopelliccia.copilotsessionsimportexport.core.ImportLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private const val COPILOT_PLUGIN_ID = "com.github.copilot"

/**
 * Resolves the GitHub Copilot plugin descriptor, when it is installed and enabled.
 *
 * The single call site, in the whole plugin, of `PluginManager.findEnabledPlugin`: an API the
 * IntelliJ Plugin Verifier reports as internal (no public replacement can resolve another plugin's
 * class loader, see the "Avvisi attesi del Plugin Verifier" section of the README). Both
 * [CopilotIdeSessionBridge.CopilotApi.load], which needs the class loader, and
 * [CopilotIdeSessionBridge.pluginStatus], which needs only the version for the import log, go
 * through this one function, so the Plugin Verifier counts one usage instead of two.
 */
private fun resolveCopilotPluginDescriptor(): IdeaPluginDescriptor? =
    PluginManager.getInstance().findEnabledPlugin(PluginId.getId(COPILOT_PLUGIN_ID))

/**
 * Reads and writes the session record the GitHub Copilot plugin keeps inside the IDE.
 *
 * The chat history list is not backed by `~/.copilot`: it is backed by a per project Nitrite
 * database owned by the Copilot plugin, and that database is held open (and file locked) by the
 * running IDE. Restoring an archive therefore cannot touch it directly - the only safe way in is
 * to ask the plugin itself, through its `AgentSessionPersistenceService`.
 *
 * That service is an internal, undocumented API of another plugin, so everything here is reached by
 * reflection through the Copilot plugin class loader and every entry point degrades to "not
 * available" instead of failing: an IDE without the Copilot plugin, or with a version whose API
 * moved, must still be able to import the `~/.copilot` part of an archive.
 *
 * Its methods are `suspend` functions, which the JVM sees as an extra trailing
 * [Continuation] parameter; [callSuspend] adapts them to a blocking call. This is only legal
 * because both callers run inside a background task, never on the EDT.
 */
internal object CopilotIdeSessionBridge {

    private const val SERVICE_CLASS = "com.github.copilot.agent.session.persistence.AgentSessionPersistenceService"
    private const val ENTITY_PACKAGE = "com.github.copilot.agent.session.persistence.nitrite.entity"
    private const val COROUTINE_SINGLETONS = "kotlin.coroutines.intrinsics.CoroutineSingletons"

    /** A suspend call that never returns is worse than one that fails: bound the wait. */
    private val CALL_TIMEOUT_SECONDS = 60L

    private val gson = Gson()

    /**
     * Id, version and enabled state of the GitHub Copilot plugin, for the import log (§A) only.
     *
     * `null` when the plugin is not installed **or** disabled: telling the two apart would need
     * the internal `PluginManager.getPlugins()` (which lists disabled plugins too) in addition to
     * [resolveCopilotPluginDescriptor], and this diagnostic detail is not worth a second usage the
     * Plugin Verifier would have to report.
     */
    data class CopilotPluginStatus(val id: String, val version: String?, val enabled: Boolean)

    fun pluginStatus(): CopilotPluginStatus? =
        resolveCopilotPluginDescriptor()?.let {
            CopilotPluginStatus(id = COPILOT_PLUGIN_ID, version = it.version, enabled = true)
        }

    /**
     * Outcome of [capture]. [unavailableReason] is `null` when the Copilot persistence API answered:
     * an empty [records] then really means "this project owns none of those conversations", which is
     * a very different situation from "the bridge could not be reached at all" and has to be
     * reported differently to the user.
     */
    data class CaptureResult(
        val records: Map<String, IdeSessionRecord> = emptyMap(),
        val unavailableReason: String? = null,
    )

    /**
     * Returns the IDE side record of every conversation in [conversationIds] that the Copilot
     * plugin knows about for [project]. Conversations owned by another project are simply absent
     * from the result - they live in a different database and cannot be read from here.
     */
    fun capture(
        project: Project,
        conversationIds: Collection<String>,
        log: ImportLog = ImportLog.NOOP,
    ): CaptureResult {
        if (conversationIds.isEmpty()) return CaptureResult()
        val api = api() ?: run {
            val reason = unavailableReason()
            log.line("IDE side capture skipped: $reason")
            return CaptureResult(unavailableReason = reason)
        }
        return runCatching {
            val service = api.service(project)
            val wanted = conversationIds.toSet()
            val captured = LinkedHashMap<String, IdeSessionRecord>()
            for (session in api.listAllSessions(service)) {
                val conversationId = api.conversationId(session) ?: continue
                if (conversationId !in wanted || conversationId in captured) continue
                // The listing may carry only the session header; re-read it to get the turns.
                val full = runCatching { api.getSession(service, api.sessionId(session)) }.getOrNull() ?: session
                captured[conversationId] = api.toRecord(full, conversationId)
            }
            log.kv("ide.captured", "${captured.size}/${wanted.size}")
            CaptureResult(records = captured)
        }.onFailure { log.failure("IDE side capture failed", it) }
            .getOrElse { CaptureResult(unavailableReason = describe(it)) }
    }

    /**
     * Inserts [record] into the Copilot plugin storage of [project] so the conversation shows up in
     * the chat history. Any record already bound to the same conversation is removed first, which
     * keeps a repeated import from producing duplicated entries in the list.
     *
     * Returns `true` only when the session was actually written.
     */
    fun restore(project: Project, record: IdeSessionRecord, log: ImportLog = ImportLog.NOOP): Boolean {
        val conversationId = record.conversationId ?: return false
        val session = record.session ?: return false
        val api = api() ?: run {
            log.line("IDE side restore skipped: ${unavailableReason()}")
            return false
        }
        return runCatching {
            val service = api.service(project)
            val replaced = api.deleteByConversationId(service, conversationId)
            if (replaced > 0) log.kv("ide.replacedExisting", replaced)

            val complete = session.deepCopy().apply {
                add("turns", record.turns)
                add("workingSet", record.workingSet)
            }
            api.createSession(service, complete, record.turns, record.workingSet)
            log.kv("ide.restored", record.ideSessionId)
            log.kv("ide.restoredTurns", record.turns.size())
            true
        }.onFailure { log.failure("IDE side restore failed", it) }.getOrDefault(false)
    }

    private fun api(): CopilotApi? {
        cachedApi?.let { return it }
        val outcome = runCatching { CopilotApi.load() }
        val loaded = outcome.getOrNull()
        loadFailure = when {
            loaded != null -> null
            outcome.isFailure -> describe(outcome.exceptionOrNull()!!)
            else -> CopilotSessionsBundle.message("ide.unavailable.pluginMissing")
        }
        return loaded?.also { cachedApi = it }
    }

    /**
     * Why the Copilot persistence API cannot be used, or `null` when it can. Callers surface this
     * instead of guessing: an API that moved and a plugin that is simply not installed need very
     * different answers from the user.
     */
    fun unavailableReason(): String? {
        if (api() != null) return null
        return loadFailure ?: CopilotSessionsBundle.message("ide.unavailable.apiMissing")
    }

    private fun describe(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        val detail = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.name
        return CopilotSessionsBundle.message("ide.unavailable.apiFailed", cause.javaClass.simpleName, detail)
    }

    @Volatile
    private var cachedApi: CopilotApi? = null

    /** Set by [api] whenever the reflective handles cannot be resolved. */
    @Volatile
    private var loadFailure: String? = null

    /**
     * The reflective handles on the Copilot plugin classes, resolved once. Construction fails fast
     * when anything is missing, so callers only have to deal with "available or not".
     */
    private class CopilotApi(
        private val getInstance: Method,
        private val companion: Any,
        private val listAllSessions: Method,
        private val getSessionMethod: Method,
        private val createSessionMethod: Method,
        private val deleteSessionMethod: Method,
        private val getConversationId: Method,
        private val getId: Method,
        private val toNtAgentSession: Method,
        private val toPersistedAgentSession: Method,
        private val ntSessionClass: Class<*>,
        private val ntTurnListType: java.lang.reflect.Type,
        private val ntWorkingSetListType: java.lang.reflect.Type,
    ) {

        fun service(project: Project): Any =
            getInstance.invoke(companion, project) ?: error("Copilot session persistence service unavailable")

        @Suppress("UNCHECKED_CAST")
        fun listAllSessions(service: Any): List<Any> =
            callSuspend(listAllSessions, service) as? List<Any> ?: emptyList()

        fun getSession(service: Any, id: String): Any? = callSuspend(getSessionMethod, service, id)

        fun conversationId(session: Any): String? = getConversationId.invoke(session) as? String

        fun sessionId(session: Any): String = getId.invoke(session) as String

        fun deleteByConversationId(service: Any, conversationId: String): Int {
            val stale = listAllSessions(service).filter { conversationId(it) == conversationId }
            for (session in stale) callSuspend(deleteSessionMethod, service, sessionId(session))
            return stale.size
        }

        fun toRecord(session: Any, conversationId: String): IdeSessionRecord {
            val nt = toNtAgentSession.invoke(null, session)
            val json = gson.toJsonTree(nt, ntSessionClass).asJsonObject
            val turns = json.remove("turns") as? JsonArray ?: JsonArray()
            val workingSet = json.remove("workingSet") as? JsonArray ?: JsonArray()
            return IdeSessionRecord(
                conversationId = conversationId,
                session = json,
                turns = turns,
                workingSet = workingSet,
            )
        }

        fun createSession(service: Any, session: JsonObject, turns: JsonArray, workingSet: JsonArray) {
            val nt = gson.fromJson(session, ntSessionClass)
            val ntTurns = gson.fromJson<List<Any>>(turns, ntTurnListType)
            val ntWorkingSet = gson.fromJson<List<Any>>(workingSet, ntWorkingSetListType)
            val persisted = toPersistedAgentSession.invoke(null, nt, ntTurns, ntWorkingSet)
            callSuspend(createSessionMethod, service, persisted)
        }

        companion object {

            fun load(): CopilotApi? {
                // A disabled plugin has no usable class loader anyway, so resolving only the
                // enabled one loses nothing; see resolveCopilotPluginDescriptor's kdoc for why
                // this is the only call site of the underlying internal API in the whole plugin.
                val loader = resolveCopilotPluginDescriptor()?.pluginClassLoader ?: return null
                val serviceClass = loader.loadClass(SERVICE_CLASS)
                val companionField = serviceClass.getDeclaredField("Companion").apply { isAccessible = true }
                val companion = companionField.get(null) ?: return null
                val sessionClass = loader.loadClass("com.github.copilot.agent.session.persistence.PersistedAgentSession")
                val turnClass = loader.loadClass("com.github.copilot.agent.session.persistence.PersistedAgentTurn")
                val workingSetClass =
                    loader.loadClass("com.github.copilot.agent.session.persistence.PersistedAgentWorkingSetItem")
                val ntSessionClass = loader.loadClass("$ENTITY_PACKAGE.NtAgentSession")
                val ntTurnClass = loader.loadClass("$ENTITY_PACKAGE.NtAgentTurn")
                val ntWorkingSetClass = loader.loadClass("$ENTITY_PACKAGE.NtAgentWorkingSetItem")
                val converters = loader.loadClass("$ENTITY_PACKAGE.NtAgentSessionKt")

                return CopilotApi(
                    getInstance = companion.javaClass.getMethod("getInstance", Project::class.java),
                    companion = companion,
                    listAllSessions = serviceClass.getMethod("listAllSessions", Continuation::class.java),
                    getSessionMethod = serviceClass.getMethod(
                        "getSession",
                        String::class.java,
                        Continuation::class.java,
                    ),
                    createSessionMethod = serviceClass.getMethod(
                        "createSession",
                        sessionClass,
                        Continuation::class.java,
                    ),
                    deleteSessionMethod = serviceClass.getMethod(
                        "deleteSession",
                        String::class.java,
                        Continuation::class.java,
                    ),
                    getConversationId = sessionClass.getMethod("getConversationId"),
                    getId = sessionClass.getMethod("getId"),
                    toNtAgentSession = converters.getMethod("toNtAgentSession", sessionClass),
                    toPersistedAgentSession = converters.getMethod(
                        "toPersistedAgentSession",
                        ntSessionClass,
                        List::class.java,
                        List::class.java,
                    ),
                    ntSessionClass = ntSessionClass,
                    ntTurnListType = TypeToken.getParameterized(List::class.java, ntTurnClass).type,
                    ntWorkingSetListType = TypeToken.getParameterized(List::class.java, ntWorkingSetClass).type,
                ).also {
                    // Referenced only to fail early if the API shape changed. Both classes are what
                    // `toPersistedAgentSession` produces, but only a turn carries an id: a working
                    // set item is keyed by its file url, so probing `getId` on it would always
                    // throw and disable the whole bridge.
                    turnClass.getMethod("getId")
                    workingSetClass.getMethod("getFileUrl")
                }
            }
        }
    }

    /**
     * Invokes a `suspend` [method] and waits for its result.
     *
     * A suspend function compiled to the JVM either returns its value directly, when it completed
     * without suspending, or the `COROUTINE_SUSPENDED` marker, in which case the result is delivered
     * later to the continuation.
     */
    private fun callSuspend(method: Method, target: Any, vararg args: Any?): Any? {
        val continuation = BlockingContinuation()
        val raw = method.invoke(target, *args, continuation)
        val suspended = raw != null && raw.javaClass.name == COROUTINE_SINGLETONS
        return if (suspended) continuation.await(CALL_TIMEOUT_SECONDS) else raw
    }

    private class BlockingContinuation : Continuation<Any?> {

        private val latch = CountDownLatch(1)

        @Volatile
        private var outcome: Result<Any?> = Result.success(null)

        override val context: CoroutineContext get() = EmptyCoroutineContext

        override fun resumeWith(result: Result<Any?>) {
            outcome = result
            latch.countDown()
        }

        fun await(timeoutSeconds: Long): Any? {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                error("The GitHub Copilot plugin did not answer within $timeoutSeconds seconds")
            }
            return outcome.getOrThrow()
        }
    }
}
