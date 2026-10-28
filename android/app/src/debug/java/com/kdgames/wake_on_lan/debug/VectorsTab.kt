package com.kdgames.wake_on_lan.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Pass = Color(0xFF7CFFB2)
private val Fail = Color(0xFFFF8F85)

@Composable
fun VectorsTab() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<VectorRunner.Report?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var runId by remember { mutableIntStateOf(0) }

    LaunchedEffect(runId) {
        report = null
        error = null
        runCatching {
            withContext(Dispatchers.Default) {
                val json = context.assets.open("vectors.json").bufferedReader().use { it.readText() }
                VectorRunner(json).run()
            }
        }.onSuccess { report = it }.onFailure { error = "${it::class.simpleName}: ${it.message}" }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val r = report
            when {
                error != null -> Text("Hata: $error", color = Fail)
                r == null -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text("Çalışıyor…")
                }
                else -> Text(
                    "${r.passed}/${r.total} geçti",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (r.passed == r.total) Pass else Fail,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { runId++ }) { Text("Tekrar çalıştır") }
        }
        report?.let { r ->
            Text("XDH sağlayıcısı: ${r.providerInfo}", style = MaterialTheme.typography.bodySmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(r.groups) { group -> GroupCard(group) }
            }
        }
    }
}

@Composable
private fun GroupCard(group: VectorRunner.Group) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (group.ok) "✓" else "✗", color = if (group.ok) Pass else Fail, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(group.name, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${group.passed}/${group.total}", fontFamily = FontFamily.Monospace)
            }
            group.failures.forEach {
                Text(it, color = Fail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
