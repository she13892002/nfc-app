package com.example.nfcapp

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.content.ComponentName

// 在 MainActivity 內新增以下內容：
override fun onResume() {
    super.onResume()
    val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    if (nfcAdapter != null && nfcAdapter.isEnabled) {
        val cardEmulation = CardEmulation.getInstance(nfcAdapter)
        val componentName = ComponentName(this, NfcEmulationService::class.java)
        // 強制設定此 App 開啟時為最高優先權 HCE 服務
        cardEmulation.setPreferredService(this, componentName)
    }
}

override fun onPause() {
    super.onPause()
    val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    if (nfcAdapter != null) {
        val cardEmulation = CardEmulation.getInstance(nfcAdapter)
        // 離開 App 時解除優先權
        cardEmulation.unsetPreferredService(this)
    }
}

data class NfcItem(val id: String, val name: String, val payloadText: String)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private var isScanningState = mutableStateOf(false)
    private var pendingPayloadState = mutableStateOf<String?>(null)
    private var recordsState = mutableStateListOf<NfcItem>()
    private var activeEmulatingId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        loadRecords()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        records = recordsState,
                        isScanning = isScanningState.value,
                        pendingPayload = pendingPayloadState.value,
                        activeEmulatingId = activeEmulatingId.value,
                        onStartScan = { startScan() },
                        onCancelScan = { stopScan() },
                        onSaveRecord = { name, payload -> saveRecord(name, payload) },
                        onDeleteRecord = { id -> deleteRecord(id) },
                        onSelectEmulate = { item -> startEmulating(item) },
                        onStopEmulate = { stopEmulating() }
                    )
                }
            }
        }
    }

    private fun startScan() {
        if (nfcAdapter == null || !nfcAdapter!!.isEnabled) {
            Toast.makeText(this, "請先在手機設定中開啟 NFC 功能", Toast.LENGTH_SHORT).show()
            return
        }
        isScanningState.value = true
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter?.enableReaderMode(this, this, flags, null)
    }

    private fun stopScan() {
        isScanningState.value = false
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag?) {
        val ndef = Ndef.get(tag)
        var rawData = ""
        try {
            ndef?.connect()
            val message = ndef?.ndefMessage
            if (message != null && message.records.isNotEmpty()) {
                rawData = String(message.records[0].payload)
            } else {
                rawData = tag?.id?.joinToString("") { "%02X".format(it) } ?: "UNKNOWN_TAG"
            }
            ndef?.close()
        } catch (e: Exception) {
            rawData = tag?.id?.joinToString("") { "%02X".format(it) } ?: "UNKNOWN_TAG"
        }

        runOnUiThread {
            stopScan()
            pendingPayloadState.value = rawData
        }
    }

    private fun saveRecord(name: String, payload: String) {
        val newItem = NfcItem(id = System.currentTimeMillis().toString(), name = name, payloadText = payload)
        recordsState.add(newItem)
        persistRecords()
        pendingPayloadState.value = null
    }

    private fun deleteRecord(id: String) {
        recordsState.removeAll { it.id == id }
        if (activeEmulatingId.value == id) stopEmulating()
        persistRecords()
    }

    private fun startEmulating(item: NfcItem) {
        activeEmulatingId.value = item.id
        NfcEmulationService.currentNdefMessage = NfcEmulationService.createTextNdefRecord(item.payloadText)
        Toast.makeText(this, "已開啟發送：${item.name}", Toast.LENGTH_SHORT).show()
    }

    private fun stopEmulating() {
        activeEmulatingId.value = null
        NfcEmulationService.currentNdefMessage = NfcEmulationService.createTextNdefRecord("")
    }

    private fun persistRecords() {
        val prefs = getSharedPreferences("nfc_prefs", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        recordsState.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("payloadText", item.payloadText)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_items", jsonArray.toString()).apply()
    }

    private fun loadRecords() {
        val prefs = getSharedPreferences("nfc_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_items", "[]") ?: "[]"
        recordsState.clear()
        val jsonArray = JSONArray(jsonStr)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            recordsState.add(NfcItem(obj.getString("id"), obj.getString("name"), obj.getString("payloadText")))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    records: List<NfcItem>,
    isScanning: Boolean,
    pendingPayload: String?,
    activeEmulatingId: String?,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onSaveRecord: (String, String) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onSelectEmulate: (NfcItem) -> Unit,
    onStopEmulate: () -> Unit
) {
    var inputName by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NFC 讀取與發送卡套") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onStartScan) { Text("新增感應資料") }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (activeEmulatingId != null) {
                val activeItem = records.find { it.id == activeEmulatingId }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("📡 正在模擬發送中...", style = MaterialTheme.typography.titleMedium)
                            Text("項目：${activeItem?.name ?: ""}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = onStopEmulate) { Text("停止發送") }
                    }
                }
            }

            Text("已儲存的 NFC 資料庫：", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (records.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("目前無資料，請點擊右下角按鈕新增卡片。")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(records) { item ->
                        val isEmulating = item.id == activeEmulatingId
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = item.name, style = MaterialTheme.typography.titleMedium)
                                    Text(text = "內容: ${item.payloadText}", style = MaterialTheme.typography.bodySmall)
                                }
                                Button(
                                    onClick = { onSelectEmulate(item) },
                                    enabled = !isEmulating
                                ) {
                                    Text(if (isEmulating) "發送中" else "點擊發送")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(onClick = { onDeleteRecord(item.id) }) {
                                    Text("刪除")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isScanning) {
            AlertDialog(
                onDismissRequest = onCancelScan,
                title = { Text("請感應 NFC 標籤") },
                text = { Text("請將 NFC 卡片或標籤靠在手機背面...") },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onCancelScan) { Text("取消") } }
            )
        }

        if (pendingPayload != null) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("感應成功！") },
                text = {
                    Column {
                        Text("讀取內容：$pendingPayload")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputName,
                            onValueChange = { inputName = it },
                            label = { Text("請輸入卡片名稱") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (inputName.isNotBlank()) {
                            onSaveRecord(inputName, pendingPayload)
                            inputName = ""
                        }
                    }) {
                        Text("儲存")
                    }
                }
            )
        }
    }
}
