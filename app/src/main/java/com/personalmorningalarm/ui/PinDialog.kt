package com.personalmorningalarm.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personalmorningalarm.R
import com.personalmorningalarm.databinding.DialogPinBinding

/**
 * A 4-digit PIN entry dialog with its own on-screen numeric keypad (the system
 * keyboard is never shown) and filled/empty dots for the digits entered so far.
 *
 * The dialog does not decide what a completed PIN means — when the 4th digit is
 * entered it invokes [onComplete] with the digits and a handle to itself, so the
 * caller can verify, advance to a confirm step, [showError], or [dismiss]. This
 * keeps the single keypad reusable across set / confirm / verify / change flows
 * (see [PinPrompts]).
 */
class PinDialog(
    context: Context,
    title: String,
    message: String?,
    private val onComplete: (pin: String, dialog: PinDialog) -> Unit,
    private val onCancel: () -> Unit = {}
) {

    private val binding = DialogPinBinding.inflate(LayoutInflater.from(context))
    private val entered = StringBuilder()
    private val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)

    private var cancelled = true // until a PIN completes, dismissing counts as cancel

    private val dialog = MaterialAlertDialogBuilder(context)
        .setView(binding.root)
        .setOnCancelListener { if (cancelled) onCancel() }
        .create()

    init {
        setHeader(title, message)
        val digitKeys = mapOf(
            binding.key0 to '0', binding.key1 to '1', binding.key2 to '2',
            binding.key3 to '3', binding.key4 to '4', binding.key5 to '5',
            binding.key6 to '6', binding.key7 to '7', binding.key8 to '8',
            binding.key9 to '9'
        )
        digitKeys.forEach { (button, digit) -> button.setOnClickListener { append(digit) } }
        binding.keyBackspace.setOnClickListener { backspace() }
        binding.keyCancel.setOnClickListener {
            onCancel()
            dialog.dismiss()
        }
        updateDots()
    }

    fun show() = dialog.show()

    fun dismiss() {
        cancelled = false // programmatic dismiss after success isn't a user cancel
        dialog.dismiss()
    }

    /** Updates the title/message and clears any entered digits. */
    fun setHeader(title: String, message: String?) {
        binding.tvPinTitle.text = title
        binding.tvPinMessage.text = message ?: ""
        reset()
    }

    /** Shows [message] in the error slot and clears the entry for a retry. */
    fun showError(message: String) {
        binding.tvPinMessage.text = message
        reset()
    }

    private fun reset() {
        entered.clear()
        updateDots()
    }

    private fun append(digit: Char) {
        if (entered.length >= PIN_LENGTH) return
        entered.append(digit)
        updateDots()
        if (entered.length == PIN_LENGTH) onComplete(entered.toString(), this)
    }

    private fun backspace() {
        if (entered.isNotEmpty()) {
            entered.deleteCharAt(entered.length - 1)
            updateDots()
        }
    }

    private fun updateDots() {
        dots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                if (i < entered.length) R.drawable.pin_dot_filled else R.drawable.pin_dot_empty
            )
        }
    }

    private companion object {
        const val PIN_LENGTH = 4
    }
}
