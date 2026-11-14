package com.sulaymoniy.soalarm.ring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.random.Random

private const val PROBLEMS_REQUIRED = 3

/** Backup way to stop the alarm when the camera can't be used: solve 3 problems. */
@Composable
fun MathChallengeScreen(reason: String?, onSolved: () -> Unit) {
    var solved by rememberSaveable { mutableIntStateOf(0) }
    var problem by rememberSaveable { mutableStateOf(newProblem()) }
    var answer by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }

    val check = {
        if (answer.trim().toIntOrNull() == problem.second) {
            solved++
            answer = ""
            wrong = false
            if (solved >= PROBLEMS_REQUIRED) onSolved() else problem = newProblem()
        } else {
            wrong = true
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (reason != null) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                "Problem ${solved + 1} of $PROBLEMS_REQUIRED",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                problem.first,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = answer,
                onValueChange = { value ->
                    answer = value.filter { it.isDigit() || it == '-' }.take(6)
                    wrong = false
                },
                label = { Text("Your answer") },
                singleLine = true,
                isError = wrong,
                supportingText = { if (wrong) Text("Not quite, try again") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { check() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = check, modifier = Modifier.fillMaxWidth()) {
                Text("Check")
            }
        }
    }
}

/** Returns question text and its answer. */
private fun newProblem(): Pair<String, Int> = when (Random.nextInt(3)) {
    0 -> {
        val a = Random.nextInt(12, 90)
        val b = Random.nextInt(12, 90)
        "$a + $b" to a + b
    }
    1 -> {
        val a = Random.nextInt(50, 100)
        val b = Random.nextInt(11, a)
        "$a − $b" to a - b
    }
    else -> {
        val a = Random.nextInt(3, 10)
        val b = Random.nextInt(11, 25)
        "$a × $b" to a * b
    }
}
