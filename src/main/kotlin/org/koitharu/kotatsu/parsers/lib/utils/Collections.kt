package org.koitharu.kotatsu.parsers.lib.utils

/**
 * Returns the first element that is an instances of specified type parameter T.
 *
 * @throws [NoSuchElementException] if no such element is found.
 */
public inline fun <reified T> Iterable<*>.firstInstance(): T = first { it is T } as T

/**
 * Returns the first element that is an instances of specified type parameter T, or `null` if element was not found.
 */
public inline fun <reified T> Iterable<*>.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T
