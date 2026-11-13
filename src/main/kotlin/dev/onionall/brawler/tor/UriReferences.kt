package dev.onionall.brawler.tor

import java.net.URI

/**
 * RFC3986 empty-path reference: ?query заменяет query текущего документа, а не его путь.
 * URI.resolve в JDK теряет последний path segment для query-only reference.
 * HTML entities должны быть декодированы DOM ровно один раз до этой границы.
 * @author Стефан Колесников
 * @author GPT-6.1 Sol
 */
object UriReferences {
    @JvmStatic fun resolve(source: String, href: String): URI {
        val base = URI.create(source)
        val reference = URI.create(href)
        if (reference.scheme == null && reference.rawAuthority == null && reference.rawPath.isEmpty()) {
            val prefix = source.substringBefore('#').substringBefore('?')
            val query = reference.rawQuery ?: base.rawQuery
            return URI.create(prefix + query?.let { "?$it" }.orEmpty() + reference.rawFragment?.let { "#$it" }.orEmpty())
        }
        return base.resolve(reference)
    }
}
