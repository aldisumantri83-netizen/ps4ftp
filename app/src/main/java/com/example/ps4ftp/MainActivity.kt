package com.example.ps4ftp

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lock = remember { Mutex() }
    val ftp = remember { FTPClient() }
    var host by remember { mutableStateOf("192.168.1.") }
    var port by remember { mutableStateOf("2121") }
    var user by remember { mutableStateOf("anonymous") }
    var pass by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf("/") }
    var files by remember { mutableStateOf(listOf<FTPFile>()) }
    var status by remember { mutableStateOf("Belum terhubung") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf<FTPFile?>(null) }
    var mk by remember { mutableStateOf<String?>(null) }

    fun run(msg: String, block: () -> Unit) {
        scope.launch {
            busy = true; status = msg
            try { lock.withLock { withContext(Dispatchers.IO) { block() } } }
            catch (e: Exception) { status = "Error: ${e.message}" }
            busy = false
        }
    }
    fun refresh() {
        path = ftp.printWorkingDirectory() ?: "/"
        files = (ftp.listFiles() ?: emptyArray()).filter { it.name != "." && it.name != ".." }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        status = "${files.size} item"
    }
    fun cd(name: String) = run("Membuka…") { ftp.changeWorkingDirectory(name); refresh() }

    val dl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) run("Mengunduh $pending…") {
            val ok = ctx.contentResolver.openOutputStream(uri)!!.use { ftp.retrieveFile(pending, it) }
            status = if (ok) "Selesai: $pending" else "Gagal: ${ftp.replyString}"
        }
    }
    val ul = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            } ?: "upload.bin"
            run("Mengunggah $name…") {
                val ok = ctx.contentResolver.openInputStream(uri)!!.use { ftp.storeFile(name, it) }
                refresh(); status = if (ok) "Terunggah: $name" else "Gagal: ${ftp.replyString}"
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PS4 FTP Client", style = MaterialTheme.typography.titleLarge)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodySmall)

        if (!on) {
            OutlinedTextField(host, { host = it }, label = { Text("IP PS4") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text("Port (GoldHEN: 2121)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password (boleh kosong)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button({
                run("Menyambung…") {
                    if (ftp.isConnected) runCatching { ftp.disconnect() }
                    ftp.controlEncoding = "UTF-8"
                    ftp.connectTimeout = 8000
                    ftp.connect(host.trim(), port.trim().toInt())
                    if (!ftp.login(user, pass)) throw Exception("Login ditolak")
                    ftp.enterLocalPassiveMode()
                    ftp.setFileType(FTP.BINARY_FILE_TYPE)
                    on = true; refresh()
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Sambung") }
        } else {
            Text(path, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ cd("..") }, enabled = !busy) { Text("↑ Naik") }
                OutlinedButton({ run("Memuat…") { refresh() } }, enabled = !busy) { Text("Refresh") }
                OutlinedButton({ ul.launch("*/*") }, enabled = !busy) { Text("Upload") }
                OutlinedButton({ mk = "" }, enabled = !busy) { Text("+Folder") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(files) { f ->
                    ListItem(
                        headlineContent = { Text((if (f.isDirectory) "📁 " else "📄 ") + f.name) },
                        supportingContent = { if (!f.isDirectory) Text("%.2f MB".format(f.size / 1048576.0)) },
                        modifier = Modifier.clickable(enabled = !busy) { if (f.isDirectory) cd(f.name) else sel = f }
                    )
                    HorizontalDivider()
                }
            }
            Button({ runCatching { ftp.disconnect() }; on = false; files = emptyList(); status = "Terputus" },
                modifier = Modifier.fillMaxWidth()) { Text("Putuskan") }
        }
    }

    sel?.let { f ->
        AlertDialog(onDismissRequest = { sel = null }, title = { Text(f.name) },
            confirmButton = { TextButton({ pending = f.name; sel = null; dl.launch(f.name) }) { Text("Download") } },
            dismissButton = { TextButton({ val n = f.name; sel = null; run("Menghapus…") { ftp.deleteFile(n); refresh() } }) { Text("Hapus") } })
    }
    mk?.let { name ->
        AlertDialog(onDismissRequest = { mk = null }, title = { Text("Folder baru") },
            text = { OutlinedTextField(name, { mk = it }, singleLine = true) },
            confirmButton = { TextButton({ val n = name; mk = null; if (n.isNotBlank()) run("Membuat…") { ftp.makeDirectory(n); refresh() } }) { Text("Buat") } })
    }
}
