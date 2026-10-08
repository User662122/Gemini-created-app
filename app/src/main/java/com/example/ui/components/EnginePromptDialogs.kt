package com.example.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.engine.gecko.GeckoPromptRequest
import com.example.ui.engine.gecko.PromptAnswer
import com.example.ui.engine.gecko.PromptChoice
import org.mozilla.geckoview.GeckoSession
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * The dialogs a page can raise.
 *
 * Android WebView provides most of these itself — `alert()`, `confirm()`, HTTP authentication, the
 * file picker — so a WebView app can implement nothing and still look complete. GeckoView delegates
 * every one of them to the embedder, which means they have to exist here or the page appears to hang.
 *
 * Requests are answered one at a time, oldest first: two `alert()`s in a row queue rather than
 * stacking dialogs, which is also how a browser behaves.
 *
 * File choosers and Android runtime permissions are *not* drawn here — they need the activity result
 * launchers that belong to the screen, so [BrowserScreen][com.example.ui.BrowserScreen] handles them
 * and this composable only renders what it can answer itself.
 */
@Composable
fun EnginePromptDialogs(
    requests: List<GeckoPromptRequest>,
    onResolve: (Long, PromptAnswer) -> Unit,
) {
    val request = requests.firstOrNull { it !is GeckoPromptRequest.FileChooser && it !is GeckoPromptRequest.AndroidPermissions }
        ?: return

    when (request) {
        is GeckoPromptRequest.JsAlert -> AlertDialog(
            onDismissRequest = { onResolve(request.id, PromptAnswer.Dismiss) },
            title = { Text(request.origin) },
            text = { Text(request.message.ifBlank { "This page says nothing." }) },
            confirmButton = {
                TextButton(onClick = { onResolve(request.id, PromptAnswer.Allow) }) { Text("OK") }
            }
        )

        is GeckoPromptRequest.JsConfirm -> AlertDialog(
            onDismissRequest = { onResolve(request.id, PromptAnswer.Dismiss) },
            title = { Text(request.origin) },
            text = { Text(request.message) },
            confirmButton = {
                TextButton(onClick = { onResolve(request.id, PromptAnswer.Allow) }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { onResolve(request.id, PromptAnswer.Deny) }) { Text("Cancel") }
            }
        )

        is GeckoPromptRequest.JsText -> {
            var value by remember(request.id) { mutableStateOf(request.defaultValue) }
            AlertDialog(
                onDismissRequest = { onResolve(request.id, PromptAnswer.Dismiss) },
                title = { Text(request.origin) },
                text = {
                    Column {
                        if (request.message.isNotBlank()) Text(request.message)
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onResolve(request.id, PromptAnswer.Text(value)) }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { onResolve(request.id, PromptAnswer.Dismiss) }) { Text("Cancel") }
                }
            )
        }

        is GeckoPromptRequest.HttpAuth -> {
            var username by remember(request.id) { mutableStateOf("") }
            var password by remember(request.id) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { onResolve(request.id, PromptAnswer.Dismiss) },
                title = {
                    Text(if (request.isProxy) "Proxy authentication" else "Sign in to ${request.host}")
                },
                text = {
                    Column {
                        Text(
                            if (request.realm.isBlank()) {
                                "This site is asking for a username and password."
                            } else {
                                request.realm
                            }
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            singleLine = true,
                            label = { Text("Username") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            singleLine = true,
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onResolve(request.id, PromptAnswer.Credentials(username, password)) }
                    ) { Text("Sign in") }
                },
                dismissButton = {
                    TextButton(onClick = { onResolve(request.id, PromptAnswer.Dismiss) }) { Text("Cancel") }
                }
            )
        }

        is GeckoPromptRequest.ContentPermission -> AlertDialog(
            onDismissRequest = { onResolve(request.id, PromptAnswer.Deny) },
            title = { Text("Allow ${request.origin}?") },
            text = { Text("This site wants to ${request.kind.description}.") },
            confirmButton = {
                TextButton(onClick = { onResolve(request.id, PromptAnswer.Allow) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { onResolve(request.id, PromptAnswer.Deny) }) { Text("Block") }
            }
        )

        is GeckoPromptRequest.MediaPermission -> {
            val devices = when {
                request.allowsVideo && request.allowsAudio -> "camera and microphone"
                request.allowsVideo -> "camera"
                request.allowsAudio -> "microphone"
                else -> "camera or microphone"
            }
            AlertDialog(
                onDismissRequest = { onResolve(request.id, PromptAnswer.Deny) },
                title = { Text("Allow ${request.origin}?") },
                text = { Text("This site wants to use your $devices.") },
                confirmButton = {
                    TextButton(onClick = { onResolve(request.id, PromptAnswer.Allow) }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = { onResolve(request.id, PromptAnswer.Deny) }) { Text("Block") }
                }
            )
        }

        is GeckoPromptRequest.Choice -> ChoiceDialog(request, onResolve)

        is GeckoPromptRequest.DateTime -> DateTimePrompt(request, onResolve)

        // Handled by the screen with its own launchers; never silenced here.
        is GeckoPromptRequest.FileChooser, is GeckoPromptRequest.AndroidPermissions -> Unit
    }
}

@Composable
private fun ChoiceDialog(
    request: GeckoPromptRequest.Choice,
    onResolve: (Long, PromptAnswer) -> Unit,
) {
    var selected by remember(request.id) {
        mutableStateOf(request.choices.filter { it.selected }.map { it.id })
    }
    val canConfirm = selected.isNotEmpty()

    AlertDialog(
        onDismissRequest = { onResolve(request.id, PromptAnswer.Dismiss) },
        title = { Text(request.title.ifBlank { request.origin }) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(request.choices, key = { it.id }) { choice ->
                    ChoiceRow(
                        choice = choice,
                        multiple = request.multiple,
                        selected = choice.id in selected,
                        onSelect = { checked ->
                            selected = if (request.multiple) {
                                if (checked) selected + choice.id else selected - choice.id
                            } else {
                                listOf(choice.id)
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    onResolve(
                        request.id,
                        if (request.multiple) {
                            PromptAnswer.ChoiceMany(selected)
                        } else {
                            PromptAnswer.ChoiceOne(selected.first())
                        }
                    )
                }
            ) { Text(if (request.multiple) "Done" else "Select") }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(request.id, PromptAnswer.Dismiss) }) { Text("Cancel") }
        }
    )
}

@Composable
private fun ChoiceRow(
    choice: PromptChoice,
    multiple: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !choice.disabled) { onSelect(!selected) }
            .padding(vertical = 4.dp),
    ) {
        if (multiple) {
            Checkbox(checked = selected, onCheckedChange = onSelect, enabled = !choice.disabled)
        } else {
            RadioButton(selected = selected, onClick = { onSelect(true) }, enabled = !choice.disabled)
        }
        Text(
            text = choice.label,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp),
            color = if (choice.disabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

/**
 * `<input type="date">` and friends.
 *
 * Implemented with the platform pickers rather than a Compose re-implementation: they are the same
 * calendar the rest of Android shows, they already handle the date arithmetic, and the string handed
 * back is the exact ISO form the HTML input expects.
 */
@Composable
private fun DateTimePrompt(
    request: GeckoPromptRequest.DateTime,
    onResolve: (Long, PromptAnswer) -> Unit,
) {
    val context = LocalContext.current
    val initial = remember(request.id) { parseDateTimeValue(request.value) }

    DisposableEffect(request.id) {
        val (year, month, day) = initial
        val initialHour = initialHourOf(request.value)
        val initialMinute = initialMinuteOf(request.value)
        var shown: android.app.Dialog? = null

        fun dismiss() = onResolve(request.id, PromptAnswer.Dismiss)
        fun answer(text: String) = onResolve(request.id, PromptAnswer.Text(text))

        if (request.type == GeckoSession.PromptDelegate.DateTimePrompt.Type.TIME) {
            val picker = TimePickerDialog(
                context,
                { _, hour, minute -> answer("%02d:%02d".format(hour, minute)) },
                initialHour,
                initialMinute,
                true
            )
            picker.setOnCancelListener { dismiss() }
            picker.show()
            shown = picker
        } else {
            val datePicker = DatePickerDialog(
                context,
                { _, pickedYear, pickedMonth, pickedDay ->
                    val date = LocalDate.of(pickedYear, pickedMonth + 1, pickedDay)
                    when (request.type) {
                        // A local datetime needs the time too, so the clock follows the calendar.
                        GeckoSession.PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> {
                            val timePicker = TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    answer("%sT%02d:%02d".format(date.toString(), hour, minute))
                                },
                                initialHour,
                                initialMinute,
                                true
                            )
                            timePicker.setOnCancelListener { dismiss() }
                            timePicker.show()
                            shown = timePicker
                        }

                        GeckoSession.PromptDelegate.DateTimePrompt.Type.MONTH ->
                            answer("%04d-%02d".format(date.year, date.monthValue))

                        GeckoSession.PromptDelegate.DateTimePrompt.Type.WEEK ->
                            answer(
                                "%04d-W%02d".format(
                                    date.get(IsoFields.WEEK_BASED_YEAR),
                                    date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                                )
                            )

                        else -> answer(date.toString())
                    }
                },
                year,
                month,
                day
            )
            datePicker.setOnCancelListener { dismiss() }
            datePicker.show()
            shown = datePicker
        }

        onDispose {
            // Leaving the screen must not leave an orphaned system dialog behind, and a dismissed
            // prompt is answered exactly like a cancelled one.
            shown?.dismiss()
        }
    }
}

private fun parseDateTimeValue(value: String): Triple<Int, Int, Int> {
    val today = LocalDate.now()
    if (value.length >= 10) {
        runCatching {
            val date = LocalDate.parse(value.substring(0, 10))
            return Triple(date.year, date.monthValue - 1, date.dayOfMonth)
        }
    }
    if (value.length >= 7) {
        runCatching {
            val year = value.substring(0, 4).toInt()
            val month = value.substring(5, 7).toInt()
            return Triple(year, month - 1, 1)
        }
    }
    return Triple(today.year, today.monthValue - 1, today.dayOfMonth)
}

private fun initialHourOf(value: String): Int =
    value.substringAfter('T', "").takeIf { it.length >= 5 }?.substring(0, 2)?.toIntOrNull()
        ?: value.takeIf { it.length >= 5 && !it.contains('-') }?.substring(0, 2)?.toIntOrNull()
        ?: 12

private fun initialMinuteOf(value: String): Int =
    value.substringAfter('T', "").takeIf { it.length >= 5 }?.substring(3, 5)?.toIntOrNull()
        ?: value.takeIf { it.length >= 5 && !it.contains('-') }?.substring(3, 5)?.toIntOrNull()
        ?: 0
