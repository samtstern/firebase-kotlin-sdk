/*
 * Copyright (c) 2020 GitLive Ltd.  Use of this source code is governed by the Apache 2.0 license.
 */

package dev.gitlive.firebase.database

import com.google.android.gms.tasks.Task
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.Logger
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseApp
import dev.gitlive.firebase.database.ChildEvent.Type
import dev.gitlive.firebase.decode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.tasks.asDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

fun encode(value: Any?) =
    dev.gitlive.firebase.encode(value, ServerValue.TIMESTAMP)

fun <T> encode(strategy: SerializationStrategy<T> , value: T): Any? =
    dev.gitlive.firebase.encode(strategy, value, ServerValue.TIMESTAMP)

suspend fun <T> Task<T>.awaitWhileOnline(): T = coroutineScope {

    val notConnected = Firebase.database
        .reference(".info/connected")
        .valueEvents
        .filter { !it.value<Boolean>() }
        .produceIn(this)

    select<T> {
        asDeferred().onAwait { it.also { notConnected.cancel() } }
        notConnected.onReceive { throw DatabaseException("Database not connected") }
    }
}

actual val Firebase.database
        by lazy { FirebaseDatabase(com.google.firebase.database.FirebaseDatabase.getInstance()) }

actual fun Firebase.database(url: String) =
    FirebaseDatabase(com.google.firebase.database.FirebaseDatabase.getInstance(url))

actual fun Firebase.database(app: FirebaseApp) =
    FirebaseDatabase(com.google.firebase.database.FirebaseDatabase.getInstance(app.android))

actual fun Firebase.database(app: FirebaseApp, url: String) =
    FirebaseDatabase(com.google.firebase.database.FirebaseDatabase.getInstance(app.android, url))

actual class FirebaseDatabase internal constructor(val android: com.google.firebase.database.FirebaseDatabase) {

    private var persistenceEnabled = true

    actual fun reference(path: String) =
        DatabaseReference(android.getReference(path), persistenceEnabled)

    actual fun setPersistenceEnabled(enabled: Boolean) =
        android.setPersistenceEnabled(enabled).also { persistenceEnabled = enabled }

    actual fun setLoggingEnabled(enabled: Boolean) =
        android.setLogLevel(Logger.Level.DEBUG.takeIf { enabled } ?: Logger.Level.NONE)
}

actual open class Query internal constructor(
    open val android: com.google.firebase.database.Query,
    val persistenceEnabled: Boolean
) {
    actual fun orderByKey() = Query(android.orderByKey(), persistenceEnabled)

    actual fun orderByChild(path: String) = Query(android.orderByChild(path), persistenceEnabled)

    actual fun startAt(value: String, key: String?) = Query(android.startAt(value, key), persistenceEnabled)

    actual fun startAt(value: Double, key: String?) = Query(android.startAt(value, key), persistenceEnabled)

    actual fun startAt(value: Boolean, key: String?) = Query(android.startAt(value, key), persistenceEnabled)

    actual val valueEvents get() = callbackFlow {
        println("adding value event listener to query ${this@Query}")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                offer(DataSnapshot(snapshot))
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                close(error.toException())
            }
        }
        android.addValueEventListener(listener)
        awaitClose { android.removeEventListener(listener) }
    }

    actual fun childEvents(vararg types: Type) = callbackFlow {
        println("adding child event listener to query ${this@Query}")
        val listener = object : ChildEventListener {

            val moved by lazy { types.contains(Type.MOVED) }
            override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                if(moved) offer(ChildEvent(DataSnapshot(snapshot), Type.MOVED, previousChildName))
            }

            val changed by lazy { types.contains(Type.CHANGED) }
            override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                if(changed) offer(ChildEvent(DataSnapshot(snapshot), Type.CHANGED, previousChildName))
            }

            val added by lazy { types.contains(Type.ADDED) }
            override fun onChildAdded(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                if(added) offer(ChildEvent(DataSnapshot(snapshot), Type.ADDED, previousChildName))
            }

            val removed by lazy { types.contains(Type.REMOVED) }
            override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) {
                if(removed) offer(ChildEvent(DataSnapshot(snapshot), Type.REMOVED, null))
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                close(error.toException())
            }
        }
        android.addChildEventListener(listener)
        awaitClose { android.removeEventListener(listener) }
    }

    override fun toString() = android.toString()
}

actual class DatabaseReference internal constructor(
    override val android: com.google.firebase.database.DatabaseReference,
    persistenceEnabled: Boolean
): Query(android, persistenceEnabled) {

    actual val key get() = android.key

    actual fun child(path: String) = DatabaseReference(android.child(path), persistenceEnabled)

    actual fun push() = DatabaseReference(android.push(), persistenceEnabled)
    actual fun onDisconnect() = OnDisconnect(android.onDisconnect(), persistenceEnabled)

    actual suspend fun setValue(value: Any?) = android.setValue(encode(value))
        .run { if(persistenceEnabled) await() else awaitWhileOnline() }
        .run { Unit }

    actual suspend fun <T> setValue(strategy: SerializationStrategy<T>, value: T) =
        android.setValue(encode(strategy, value))
            .run { if(persistenceEnabled) await() else awaitWhileOnline() }
            .run { Unit }

    @Suppress("UNCHECKED_CAST")
    actual suspend fun updateChildren(update: Map<String, Any?>) =
        android.updateChildren(encode(update) as Map<String, Any?>)
            .run { if(persistenceEnabled) await() else awaitWhileOnline() }
            .run { Unit }

    actual suspend fun removeValue() = android.removeValue()
        .run { if(persistenceEnabled) await() else awaitWhileOnline() }
        .run { Unit }
}

@Suppress("UNCHECKED_CAST")
actual class DataSnapshot internal constructor(val android: com.google.firebase.database.DataSnapshot) {

    actual val exists get() = android.exists()

    actual val key get() = android.key

    actual inline fun <reified T> value() =
        decode<T>(value = android.value)

    actual fun <T> value(strategy: DeserializationStrategy<T>) =
        decode(strategy, android.value)

    actual fun child(path: String) = DataSnapshot(android.child(path))
    actual val children: Iterable<DataSnapshot> get() = android.children.map { DataSnapshot(it) }
}

actual class OnDisconnect internal constructor(
    val android: com.google.firebase.database.OnDisconnect,
    val persistenceEnabled: Boolean
) {

    actual suspend fun removeValue() = android.removeValue()
        .run { if(persistenceEnabled) await() else awaitWhileOnline() }
        .run { Unit }

    actual suspend fun cancel() = android.cancel()
        .run { if(persistenceEnabled) await() else awaitWhileOnline() }
        .run { Unit }

    actual suspend fun setValue(value: Any) =
        android.setValue(encode(value))
            .run { if(persistenceEnabled) await() else awaitWhileOnline() }
            .run { Unit }

    actual suspend fun <T> setValue(strategy: SerializationStrategy<T>, value: T) =
        android.setValue(encode(strategy, value))
            .run { if(persistenceEnabled) await() else awaitWhileOnline() }
            .run { Unit}

    actual suspend fun updateChildren(update: Map<String, Any?>) =
        android.updateChildren(update.mapValues { (_, it) -> encode(it) })
            .run { if(persistenceEnabled) await() else awaitWhileOnline() }
            .run { Unit }
}

actual typealias DatabaseException = com.google.firebase.database.DatabaseException

