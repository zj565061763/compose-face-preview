package com.sd.lib.compose.facepreview

import com.google.common.truth.Truth.assertThat
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ConflatedExecutorDispatcherTest {
  @Test
  fun submit_whileTaskIsPending_disposesReplacedValueAndDeliversLatest() {
    val tasks = ArrayDeque<Runnable>()
    val disposedValues = mutableListOf<Int>()
    val deliveredValues = mutableListOf<Int>()
    val dispatcher = ConflatedExecutorDispatcher(
      executor = Executor(tasks::addLast),
      onDispose = disposedValues::add,
    )

    dispatcher.submit(value = 1, isCurrent = { true }, onValue = deliveredValues::add)
    dispatcher.submit(value = 2, isCurrent = { true }, onValue = deliveredValues::add)

    assertThat(tasks).hasSize(1)
    assertThat(disposedValues).containsExactly(1)
    tasks.removeFirst().run()
    assertThat(deliveredValues).containsExactly(2)
    assertThat(disposedValues).containsExactly(1, 2).inOrder()
  }

  @Test
  fun submit_fromCallback_schedulesFollowUpOnOwnedExecutor() {
    val tasks = ArrayDeque<Runnable>()
    val deliveredValues = mutableListOf<Int>()
    lateinit var dispatcher: ConflatedExecutorDispatcher<Int>
    dispatcher = ConflatedExecutorDispatcher(
      executor = Executor(tasks::addLast),
    )
    dispatcher.submit(value = 1, isCurrent = { true }) { value ->
      deliveredValues += value
      dispatcher.submit(value = 2, isCurrent = { true }, onValue = deliveredValues::add)
    }

    tasks.removeFirst().run()

    assertThat(deliveredValues).containsExactly(1)
    assertThat(tasks).hasSize(1)
    tasks.removeFirst().run()
    assertThat(deliveredValues).containsExactly(1, 2).inOrder()
  }

  @Test
  fun submit_executorThrowsOutOfMemory_disposesPendingValueAndRemainsReusable() {
    val expected = OutOfMemoryError("expected")
    var shouldFail = true
    val disposedValues = mutableListOf<Int>()
    val deliveredValues = mutableListOf<Int>()
    val dispatcher = ConflatedExecutorDispatcher(
      executor = Executor { command ->
        if (shouldFail) {
          shouldFail = false
          throw expected
        }
        command.run()
      },
      onDispose = disposedValues::add,
    )

    val actual = try {
      dispatcher.submit(value = 1, isCurrent = { true }, onValue = deliveredValues::add)
      null
    } catch (error: Throwable) {
      error
    }
    dispatcher.submit(value = 2, isCurrent = { true }, onValue = deliveredValues::add)

    assertThat(actual).isSameInstanceAs(expected)
    assertThat(deliveredValues).containsExactly(2)
    assertThat(disposedValues).containsExactly(1, 2).inOrder()
  }
}
