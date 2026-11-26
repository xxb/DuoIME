// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.engine

import dev.duoime.core.model.CandidateItem
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyProcessResult
import dev.duoime.core.model.SinkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAndSinkContractTest {
    private class FakeEngine : InputEngine {
        private val _state = MutableStateFlow(EngineSnapshot.EMPTY)
        override val state: StateFlow<EngineSnapshot> = _state

        override fun onKey(event: EngineKeyEvent): KeyProcessResult {
            val ch = event.label ?: return KeyProcessResult.PassThrough
            val next = _state.value.preedit + ch
            _state.value = _state.value.copy(
                preedit = next,
                composingText = next,
                candidates = listOf(CandidateItem(text = next, indexOnPage = 0)),
                isComposing = true
            )
            return KeyProcessResult.Consumed
        }

        override fun selectCandidate(indexOnPage: Int): KeyProcessResult {
            val candidate = _state.value.candidates.getOrNull(indexOnPage) ?: return KeyProcessResult.PassThrough
            reset()
            return KeyProcessResult.CommitText(candidate.text)
        }

        override fun page(forward: Boolean): Boolean = false

        override fun setOption(name: String, on: Boolean) {
            if (name == "traditionalization") {
                _state.value = _state.value.copy(
                    variant = if (on) ChineseVariant.TRADITIONAL else ChineseVariant.SIMPLIFIED
                )
            }
        }

        override fun selectSchema(id: String): Boolean {
            _state.value = _state.value.copy(schemaId = id)
            return true
        }

        override fun reset() {
            _state.value = EngineSnapshot.EMPTY
        }
    }

    private class FakeSink(override val capabilities: SinkCapabilities = SinkCapabilities.TERMINAL) : InputSink {
        val committed = mutableListOf<String>()
        var composing: String? = null
        var deletes: Int = 0
        val keys = mutableListOf<Pair<Int, Int>>()
        val editorActions = mutableListOf<Int>()

        override fun commitText(text: CharSequence) {
            committed.add(text.toString())
        }

        override fun setComposing(text: CharSequence?) {
            composing = text?.toString()
        }

        override fun deleteBackward(count: Int) {
            deletes += count
        }

        override fun sendKey(keyCode: Int, metaState: Int) {
            keys.add(keyCode to metaState)
        }

        override fun performEditorAction(action: Int) {
            editorActions.add(action)
        }
    }

    @Test
    fun inputEngineAndSinkContractsOperatePurelyOnJvm() {
        val engine = FakeEngine()
        assertEquals(KeyProcessResult.Consumed, engine.onKey(EngineKeyEvent.fromChar('n')))
        assertTrue(engine.state.value.isComposing)
        engine.setOption("traditionalization", true)
        assertEquals(ChineseVariant.TRADITIONAL, engine.state.value.variant)
        assertEquals(KeyProcessResult.CommitText("n"), engine.selectCandidate(0))
        assertFalse(engine.state.value.isComposing)

        val sink = FakeSink()
        assertFalse(sink.capabilities.composing)
        assertTrue(sink.capabilities.rawKeys)
        assertFalse(
            sink.commitImage(uri = "content://dev.duoime.app/sketch/1.png", mime = "image/png", label = "Sketch")
        )
        assertFalse(sink.sendHostCommand(id = "custom_cmd", payload = "{}"))

        val context = EditorContext(packageName = "dev.example.host", isPassword = false, incognito = true)
        val feedback =
            FeedbackKey(action = KeyAction.SwitchMode(InputMode.PINYIN_26), label = "中", zone = FeedbackZone.ACTION)
        assertTrue(context.incognito)
        assertEquals("中", feedback.label)

        // PassThroughEngine contract: never composes, passes all keys/selections through safely
        val passthrough: InputEngine = PassThroughEngine()
        assertEquals(EngineSnapshot.EMPTY, passthrough.state.value)
        assertEquals(KeyProcessResult.PassThrough, passthrough.onKey(EngineKeyEvent.fromChar('a')))
        assertEquals(KeyProcessResult.PassThrough, passthrough.selectCandidate(0))
        assertFalse(passthrough.selectSyllable(0))
        assertFalse(passthrough.page(true))
        assertFalse(passthrough.selectSchema(EngineSchemas.PINYIN_26))
        passthrough.setOption(EngineOptions.INCOGNITO, true)
        passthrough.reset()
        assertEquals(EngineSnapshot.EMPTY, passthrough.state.value)
    }
}
