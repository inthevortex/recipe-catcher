package org.eu.angsumanray.recipecatcher.wear.tile

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Future

/** Runs [block] in this scope and exposes the result as the [ListenableFuture] the Tiles API expects. */
internal fun <T> CoroutineScope.listenableFuture(block: suspend () -> T): ListenableFuture<T> {
    val result = CompletableFuture<T>()
    val job = launch {
        try {
            result.complete(block())
        } catch (e: CancellationException) {
            result.cancel(false)
        } catch (e: Throwable) {
            result.completeExceptionally(e)
        }
    }
    return object : ListenableFuture<T>, Future<T> by result {
        override fun addListener(listener: Runnable, executor: Executor) {
            result.whenComplete { _, _ -> executor.execute(listener) }
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            job.cancel()
            return result.cancel(mayInterruptIfRunning)
        }
    }
}
