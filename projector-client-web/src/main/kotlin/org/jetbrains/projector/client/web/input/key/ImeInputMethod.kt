/*
 * MIT License
 *
 * Copyright (c) 2019-2021 JetBrains s.r.o.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.jetbrains.projector.client.web.input.key

import kotlinx.browser.document
import org.jetbrains.projector.client.common.misc.TimeStamp
import org.jetbrains.projector.common.misc.isUpperCase
import org.jetbrains.projector.common.protocol.toServer.ClientEvent
import org.jetbrains.projector.common.protocol.toServer.ClientKeyEvent.KeyEventType.DOWN
import org.jetbrains.projector.common.protocol.toServer.ClientKeyEvent.KeyEventType.UP
import org.jetbrains.projector.common.protocol.toServer.ClientKeyPressEvent
import org.jetbrains.projector.common.protocol.toServer.KeyModifier
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.CompositionEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.InputEvent
import org.w3c.dom.events.KeyboardEvent
import kotlin.math.roundToInt

class ImeInputMethod(
  openingTimeStamp: Int,
  clientEventConsumer: (ClientEvent) -> Unit,
) : InputMethod {

  private val handler = ImeInputMethodEventHandler(
    openingTimeStamp = openingTimeStamp,
    clientEventConsumer = clientEventConsumer,
    clearInputField = ::clearInputField,
  )

  private val inputField = (document.createElement("textarea") as HTMLTextAreaElement).apply {
    style.apply {
      position = "fixed"
      bottom = "-30%"
      left = "50%"
    }

    autocomplete = "off"
    asDynamic().autocapitalize = "none"

    onblur = {
      this.focus()
      this.click()
    }

    addEventListener("compositionstart", handler::handleEvent)
    addEventListener("compositionend", handler::handleEvent)
    onkeydown = handler::handleEvent
    onkeyup = handler::handleEvent
    oninput = handler::handleEvent

    onclick = {
      it.stopPropagation()
    }

    document.body!!.appendChild(this)
  }

  init {
    clearInputField()
  }

  private fun clearInputField() {
    inputField.value = ""
  }

  init {
    inputField.focus()
    inputField.click()
  }

  override fun dispose() {
    inputField.remove()
  }
}

class ImeInputMethodEventHandler(
  private val openingTimeStamp: Int,
  private val clientEventConsumer: (ClientEvent) -> Unit,
  private val clearInputField: () -> Unit,
) {

  fun handleEvent(event: Event): Unit = when (event) {
    is KeyboardEvent -> fireKeyEvent(event)
    is InputEvent -> handleInputEvent()
    is CompositionEvent -> handleCompositionEvent(event)

    else -> throw UnsupportedOperationException("Unknown event '$event' with type '${event.type}'")
  }

  private var skipNextKeyUp = false

  private fun fireKeyEvent(event: KeyboardEvent) {
    if (event.key == "Process") {
      return
    }

    val type = when (event.type) {  // todo: move to clientEventConsumer.fireKeyEvent, remove parameter
      "keydown" -> DOWN
      "keyup" -> UP
      else -> throw IllegalArgumentException("Bad event type '${event.type}'")
    }

    if (event.keyCode == 229 && type == DOWN) {
      // an Input Method Editor is processing key input
      // source: https://w3c.github.io/uievents/#determine-keydown-keyup-keyCode
      skipNextKeyUp = true
      return
    }

    if (skipNextKeyUp && type == UP) {
      skipNextKeyUp = false
      return
    }

    clientEventConsumer.fireKeyEvent(type, event, openingTimeStamp)
  }

  private var composing = false

  private fun handleInputEvent() {
    if (!composing) {
      clearInputField()
    }
  }

  private fun handleCompositionEvent(event: CompositionEvent) {
    when (event.type) {
      "compositionstart" -> {
        composing = true
      }

      "compositionend" -> {
        clearInputField()
        composing = false

        event.data.forEach { char ->
          fireKeyPress(char)
        }
      }
    }
  }

  fun fireKeyPress(char: Char) {
    val message = ClientKeyPressEvent(
      timeStamp = TimeStamp.current.roundToInt() - openingTimeStamp,
      char = char,
      modifiers = if (char.isUpperCase()) setOf(KeyModifier.SHIFT_KEY) else setOf(),  // todo: use modifiers of the last KEY_UP
    )
    clientEventConsumer(message)
  }
}
