package com.sd.lib.compose.facepreview

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 将待执行值合并为构造时指定 [Executor] 上的一个任务，只处理执行时最新且仍有效的值。 */
internal class ConflatedExecutorDispatcher<T : Any>(
  private val executor: Executor,
  private val onDispose: (T) -> Unit = {},
) {
  private val _pending = AtomicReference<PendingDispatch<T>?>()
  private val _isDispatchScheduled = AtomicBoolean(false)

  fun submit(
    value: T,
    isCurrent: () -> Boolean,
    onValue: (T) -> Unit,
  ) {
    if (!isCurrent()) {
      onDispose(value)
      return
    }

    val pending = PendingDispatch(
      value = value,
      isCurrent = isCurrent,
      onValue = onValue,
    )
    _pending.getAndSet(pending)?.also(::dispose)
    if (!isCurrent()) {
      if (_pending.compareAndSet(pending, null)) dispose(pending)
      return
    }

    schedule()
  }

  fun clear() {
    _pending.getAndSet(null)?.also(::dispose)
  }

  private fun schedule() {
    if (!_isDispatchScheduled.compareAndSet(false, true)) return
    try {
      executor.execute(::dispatch)
    } catch (error: Throwable) {
      _isDispatchScheduled.set(false)
      clear()
      throw error
    }
  }

  private fun dispatch() {
    val pending = _pending.getAndSet(null)
    try {
      if (pending != null && pending.isCurrent()) pending.onValue(pending.value)
    } finally {
      pending?.also(::dispose)
      _isDispatchScheduled.set(false)
      if (_pending.get() != null) schedule()
    }
  }

  private fun dispose(pending: PendingDispatch<T>) {
    onDispose(pending.value)
  }
}

private class PendingDispatch<T : Any>(
  val value: T,
  val isCurrent: () -> Boolean,
  val onValue: (T) -> Unit,
)
