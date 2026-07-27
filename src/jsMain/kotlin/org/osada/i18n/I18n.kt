@file:Suppress("TooManyFunctions")

package org.osada.i18n

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.xhr.XMLHttpRequest

/**
 * OSADA localization runtime.
 *
 * English is the canonical source language. Every lookup follows the same deterministic chain:
 * selected language -> English -> stable key. Bundles are loaded by domain from
 * `i18n/<language>/<domain>.json`; nested domains such as `units/openpanzer` are supported.
 */
internal object I18n {
    private const val STORAGE_KEY = "osada-language"
    private const val DEFAULT_DOMAIN = "ui"
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299

    private val bundles = mutableMapOf<String, dynamic>()
    private val loadedBundles = mutableSetOf<String>()
    private val bundleWaiters = mutableMapOf<String, MutableList<() -> Unit>>()
    private val reportedMissingKeys = mutableSetOf<String>()
    private val languageChangeListeners = mutableSetOf<() -> Unit>()

    var language: Language = Language.ENGLISH
        private set

    private var initialized = false
    private var languageChangeRevision = 0
    private var requestedLanguage = Language.ENGLISH

    fun initialize(onReady: () -> Unit) {
        if (initialized) {
            onReady()
            return
        }
        language = initialLanguage()
        requestedLanguage = language
        ensureDomain(DEFAULT_DOMAIN) {
            initialized = true
            applyDocumentLanguage()
            onReady()
        }
    }

    /** Loads the English base bundle and then the selected-language overlay for [domain]. */
    fun ensureDomain(
        domain: String,
        onReady: () -> Unit,
    ) {
        loadBundle(Language.ENGLISH, domain) {
            if (language == Language.ENGLISH) {
                onReady()
            } else {
                loadBundle(language, domain, onReady)
            }
        }
    }

    /**
     * Switches locale inside the current game session. No page reload, game restore, or menu
     * reconstruction occurs. Already loaded domains receive the requested language overlay first;
     * listeners then refresh their existing DOM while preserving gameplay and menu state.
     */
    fun setLanguage(
        value: Language,
        onReady: () -> Unit = {},
    ) {
        if (language == value && requestedLanguage == value) {
            onReady()
            return
        }
        requestedLanguage = value
        val revision = ++languageChangeRevision
        val domains = loadedEnglishDomains().ifEmpty { setOf(DEFAULT_DOMAIN) }
        loadLanguageDomains(value, domains) {
            if (revision != languageChangeRevision) return@loadLanguageDomains
            language = value
            requestedLanguage = value
            localStorage.setItem(STORAGE_KEY, value.locale)
            reportedMissingKeys.clear()
            applyDocumentLanguage()
            languageChangeListeners.toList().forEach { listener ->
                try {
                    listener()
                    // A listener can throw anything; one broken listener must not block the rest.
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Throwable,
                ) {
                    console.error("[i18n] Language-change listener failed", error)
                }
            }
            onReady()
        }
    }

    fun onLanguageChanged(listener: () -> Unit) {
        languageChangeListeners += listener
    }

    fun t(
        key: String,
        args: Map<String, Any?> = emptyMap(),
        domain: String = DEFAULT_DOMAIN,
    ): String {
        val template = stringValue(language, domain, key) ?: stringValue(Language.ENGLISH, domain, key)
        if (template == null) {
            reportMissing(domain, key)
            return interpolate(key, args)
        }
        return interpolate(template, args)
    }

    /**
     * Like [t], but returns null on a miss instead of the raw key — for callers that have their own
     * fallback key to try next (e.g. a gendered `_f` variant falling back to the ungendered base)
     * and must not trigger [reportMissing] for a variant that is allowed to not exist.
     */
    fun tOrNull(
        key: String,
        args: Map<String, Any?> = emptyMap(),
        domain: String = DEFAULT_DOMAIN,
    ): String? {
        val template = stringValue(language, domain, key) ?: stringValue(Language.ENGLISH, domain, key)
        return template?.let { interpolate(it, args) }
    }

    fun plural(
        key: String,
        count: Number,
        args: Map<String, Any?> = emptyMap(),
        domain: String = DEFAULT_DOMAIN,
    ): String {
        val category = pluralCategory(count)
        val template =
            branchValue(language, domain, key, category)
                ?: branchValue(Language.ENGLISH, domain, key, category)
        val allArgs = args + mapOf("count" to count)
        if (template == null) {
            reportMissing(domain, key)
            return interpolate(key, allArgs)
        }
        return interpolate(template, allArgs)
    }

    fun select(
        key: String,
        selector: String,
        args: Map<String, Any?> = emptyMap(),
        domain: String = DEFAULT_DOMAIN,
    ): String {
        val template =
            branchValue(language, domain, key, selector)
                ?: branchValue(Language.ENGLISH, domain, key, selector)
        if (template == null) {
            reportMissing(domain, key)
            return interpolate(key, args)
        }
        return interpolate(template, args)
    }

    fun formatNumber(value: Number): String {
        val constructor = js("Intl.NumberFormat")
        val formatter = js("Reflect.construct")(constructor, arrayOf(language.locale))
        return formatter.format(value) as String
    }

    internal fun installBundlesForTests(
        english: String,
        selected: String,
        selectedLanguage: Language,
        domain: String = DEFAULT_DOMAIN,
    ) {
        bundles.clear()
        loadedBundles.clear()
        bundleWaiters.clear()
        reportedMissingKeys.clear()
        languageChangeListeners.clear()
        language = selectedLanguage
        requestedLanguage = selectedLanguage
        putBundle(Language.ENGLISH, domain, parseBundle(english, "test:en:$domain"))
        putBundle(selectedLanguage, domain, parseBundle(selected, "test:${selectedLanguage.code}:$domain"))
        initialized = true
    }

    private fun initialLanguage(): Language {
        val stored = Language.fromCodeOrNull(localStorage.getItem(STORAGE_KEY))
        return stored ?: Language.bestMatch(browserLanguageTags())
    }

    private fun browserLanguageTags(): List<String> {
        val navigator = window.navigator.asDynamic()
        val result = mutableListOf<String>()
        val languages = navigator.languages
        if (languages != null && languages != undefined) {
            val length = languages.length as? Int ?: 0
            for (index in 0 until length) {
                (languages[index] as? String)?.let(result::add)
            }
        }
        (navigator.language as? String)?.let { if (it !in result) result += it }
        return result
    }

    private fun applyDocumentLanguage() {
        document.documentElement?.setAttribute("lang", language.locale)
    }

    private fun loadedEnglishDomains(): Set<String> =
        loadedBundles
            .mapNotNull { id -> id.takeIf { it.startsWith("${Language.ENGLISH.code}:") }?.substringAfter(':') }
            .toSet()

    private fun loadLanguageDomains(
        targetLanguage: Language,
        domains: Set<String>,
        onReady: () -> Unit,
    ) {
        if (targetLanguage == Language.ENGLISH || domains.isEmpty()) {
            onReady()
            return
        }
        var pending = domains.size
        domains.forEach { domain ->
            loadBundle(targetLanguage, domain) {
                pending--
                if (pending == 0) onReady()
            }
        }
    }

    private fun loadBundle(
        bundleLanguage: Language,
        domain: String,
        onReady: () -> Unit,
    ) {
        val id = bundleId(bundleLanguage, domain)
        if (id in loadedBundles) {
            onReady()
            return
        }
        bundleWaiters[id]?.let { waiters ->
            waiters += onReady
            return
        }
        bundleWaiters[id] = mutableListOf(onReady)

        val request = XMLHttpRequest()
        var completed = false

        fun finish(bundle: dynamic) {
            if (completed) return
            completed = true
            putBundle(bundleLanguage, domain, bundle)
            bundleWaiters.remove(id).orEmpty().forEach { it() }
        }

        request.onload = {
            val status = request.status.toInt()
            val body = request.responseText
            if ((status in HTTP_OK_MIN..HTTP_OK_MAX || status == 0) && !body.isNullOrBlank()) {
                finish(parseBundle(body, "i18n/${bundleLanguage.code}/$domain.json"))
            } else {
                if (bundleLanguage == Language.ENGLISH) {
                    console.error("[i18n] Missing canonical bundle i18n/en/$domain.json (HTTP $status)")
                } else {
                    console.warn("[i18n] Missing optional bundle i18n/${bundleLanguage.code}/$domain.json")
                }
                finish(emptyBundle())
            }
        }
        request.onerror = {
            console.warn("[i18n] Could not load i18n/${bundleLanguage.code}/$domain.json")
            finish(emptyBundle())
        }
        request.open("GET", "i18n/${bundleLanguage.code}/$domain.json", true)
        request.send(null)
    }

    private fun putBundle(
        bundleLanguage: Language,
        domain: String,
        bundle: dynamic,
    ) {
        val id = bundleId(bundleLanguage, domain)
        bundles[id] = bundle
        loadedBundles += id
    }

    private fun parseBundle(
        raw: String,
        source: String,
    ): dynamic =
        try {
            JSON.parse<dynamic>(raw)
            // JSON.parse's JS interop throws an untyped SyntaxError; there is no narrower type to
            // catch, and a malformed bundle must degrade to empty rather than crash the caller.
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Throwable,
        ) {
            console.error("[i18n] Invalid JSON in $source", error)
            emptyBundle()
        }

    private fun stringValue(
        bundleLanguage: Language,
        domain: String,
        key: String,
    ): String? = (rawValue(bundleLanguage, domain, key) as? String)?.takeIf { it.isNotBlank() }

    private fun branchValue(
        bundleLanguage: Language,
        domain: String,
        key: String,
        branch: String,
    ): String? {
        val value = rawValue(bundleLanguage, domain, key) ?: return null
        return (value[branch] as? String)?.takeIf { it.isNotBlank() }
            ?: (value["other"] as? String)?.takeIf { it.isNotBlank() }
    }

    private fun rawValue(
        bundleLanguage: Language,
        domain: String,
        key: String,
    ): dynamic {
        val bundle = bundles[bundleId(bundleLanguage, domain)] ?: return null
        val value = bundle[key]
        return if (value == undefined || value == null) null else value
    }

    private fun pluralCategory(count: Number): String {
        val constructor = js("Intl.PluralRules")
        val rules = js("Reflect.construct")(constructor, arrayOf(language.locale))
        return rules.select(count) as String
    }

    private fun interpolate(
        template: String,
        args: Map<String, Any?>,
    ): String {
        var result = template
        args.forEach { (name, value) ->
            result = result.replace("{$name}", formatArgument(value))
        }
        return result
    }

    private fun formatArgument(value: Any?): String =
        when (value) {
            null -> ""
            is Number -> formatNumber(value)
            else -> value.toString()
        }

    private fun reportMissing(
        domain: String,
        key: String,
    ) {
        val id = "$domain:$key"
        if (reportedMissingKeys.add(id)) {
            console.warn("[i18n] Missing key $id for ${language.code} and English")
        }
    }

    private fun bundleId(
        bundleLanguage: Language,
        domain: String,
    ): String = "${bundleLanguage.code}:$domain"

    private fun emptyBundle(): dynamic = js("({})")
}
