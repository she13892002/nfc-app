package com.example.nfcapp

import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
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
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NfcAppUI()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let { adapter ->
            if (adapter.isEnabled) {
                try {
                    val cardEmulation = CardEmulation.getInstance(adapter)
                    val componentName = ComponentName(this, NfcEmulationService::class.java)
                    // 強制當前 App 開啟時佔用優先權，防止跳出「嵌入式標記」選單
                    cardEmulation.setPreferredService(this, componentName)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.let { adapter ->
            try {
                val cardEmulation = CardEmulation.getInstance(adapter)
                cardEmulation.unsetPreferredService(this)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun NfcAppUI() {
    var inputText by remember { mutableStateOf("") }
    val cardList = remember { mutableStateListOf("測試 NDEF 資料 1", "https://example.com") }
    var activeSendingText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("NFC 卡套模擬器", fontSize = 22.sp, modifier = Modifier.padding(bottom = 12.dp))

        // 狀態顯示區
        activeSendingText?.let { text ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = "📡 正在發送：$text",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // 新增文字框
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("輸入要儲存的 NDEF 內容") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (inputText.isNotBlank()) {
                    cardList.add(inputText)
                    inputText = ""
                }
            }) {
                Text("新增")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 列表
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(cardList) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = item, modifier = Modifier.weight(1f))
                        Row {
                            Button(onClick = {
                                activeSendingText = item
                                NfcEmulationService.currentNdefMessage =
                                    NfcEmulationService.createTextNdefRecord(item)
                            }) {
                                Text("點擊發送")
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(onClick = {
                                cardList.remove(item)
                                if (activeSendingText == item) activeSendingText = null
                            }) {
                                Text("刪除")
                            }
                        }
                    }
                }
            }
        }
    }
}
