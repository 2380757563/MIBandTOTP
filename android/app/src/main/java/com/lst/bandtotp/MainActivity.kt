package com.lst.bandtotp

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lst.bandtotp.ui.theme.BandtotpTheme
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader


class MainActivity : ComponentActivity() {

    private lateinit var nodeId: String
    private lateinit var curNode: Node
    private lateinit var nodeApi: NodeApi
    private val handler = Handler(Looper.getMainLooper())
    private var logTextState = mutableStateOf("")

    enum class ImportMode { TXT, JSON, CSV }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nodeApi = Wearable.getNodeApi(this)
        enableEdgeToEdge()
        setContent {
            BandtotpTheme() {
                MainContent()
            }
        }
    }

    private fun openApp() {
        nodeApi.isWearAppInstalled(nodeId)
            .addOnSuccessListener {
                nodeApi.launchWearApp(nodeId, "pages/index").addOnSuccessListener {
                    log("打开手环端软件成功")
                }.addOnFailureListener {
                    log("打开手环端软件失败")
                }
            }
            .addOnFailureListener {
                log("手环未安装小程序")
                Toast.makeText(
                    this,
                    "手环未安装小程序！如果已经安装，请尝试重启手环",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun sendMessageToWearable(message: String) {
        val messageApi = Wearable.getMessageApi(this)
        if (::nodeId.isInitialized) {
            messageApi.sendMessage(nodeId, message.toByteArray())
                .addOnSuccessListener {
                    Toast.makeText(this, "Message sent successfully", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to send message: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryConnectedDevices() {
        nodeApi.connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNotEmpty()) {
                curNode = nodes[0]
                nodeId = curNode.id
                log(curNode.name)
                checkAndRequestPermissions()
            }
        }.addOnFailureListener { e ->
            Toast.makeText(
                this,
                "Failed to get connected devices: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH), 1001)
        }
        val authApi = Wearable.getAuthApi(this)
        if (::nodeId.isInitialized) {
            val did = nodeId
            authApi.checkPermission(did, Permission.DEVICE_MANAGER)
                .addOnSuccessListener { granted ->
                    if (!granted) {
                        authApi.requestPermission(did, Permission.DEVICE_MANAGER)
                            .addOnSuccessListener {
                                log("Permissions granted")
                            }.addOnFailureListener { e ->
                                Toast.makeText(this, "Failed to request permissions: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        log("Permissions already granted")
                    }
                }.addOnFailureListener { e ->
                    e.message?.let { log(it) }
                }
        }
    }

    @Composable
    fun MainContent(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        var connectedDeviceText by remember { mutableStateOf("设备未连接") }
        var logText by remember { logTextState }
        var selectedMode by remember { mutableStateOf(ImportMode.TXT) }
        var showHelp by remember { mutableStateOf(false) }

        val pickFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                val contentResolver = context.contentResolver
                val datas = parseFile(contentResolver, it, selectedMode)
                if (datas.isEmpty()) {
                    log("未解析到有效数据，请检查文件格式")
                } else {
                    log("解析成功，共 ${datas.size} 条")
                    datas.forEach { info -> log(info.name) }
                }
                val json = buildJson(datas)
                sendMessageToWearable(json)
            }
        }

        fun startUpload() {
            if (::nodeId.isInitialized) {
                openApp()
                pickFileLauncher.launch("*/*")
            } else {
                Toast.makeText(this, "未连接到设备", Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(Unit) {
            while (!(::nodeId.isInitialized)) {
                queryConnectedDevices()
                delay(1000)
            }
            connectedDeviceText = "设备:${curNode.name}"
        }

        Column(
            modifier = modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = connectedDeviceText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text(
                text = "选择导入格式",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Column(Modifier.selectableGroup()) {
                ImportMode.values().forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (selectedMode == mode),
                                onClick = { selectedMode = mode }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedMode == mode),
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (mode) {
                                ImportMode.TXT -> "TXT - 每行一个 otpauth URI"
                                ImportMode.JSON -> "JSON - 标准数组格式"
                                ImportMode.CSV -> "CSV - 表格格式"
                            }
                        )
                    }
                }
            }

            Button(
                onClick = { showHelp = !showHelp },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showHelp) "隐藏格式示例" else "查看格式示例")
            }

            if (showHelp) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = getFormatExample(selectedMode),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }

            Button(
                onClick = { startUpload() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("选择导入文件")
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                elevation = CardDefaults.cardElevation(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = "LOGS",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Column(modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())) {
                    Text(
                        text = logText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                elevation = CardDefaults.cardElevation(4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "About",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "BandTOTP by lesetong\n本软件使用MIT协议开源\nCopyright (c) 2024 lesetong",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        BandtotpTheme() {
            MainContent()
        }
    }

    private fun log(message: Any) {
        logTextState.value += "$message\n"
    }

    private fun getFormatExample(mode: ImportMode): String {
        return when (mode) {
            ImportMode.TXT -> """
                TXT 格式：每行一个 otpauth:// URI
                name 取自 path 中的 issuer（可自定义），issuer 取自 &issuer 参数（用于 Steam 判断）
                
                示例：
                otpauth://totp/GitHub:user@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub
                otpauth://totp/Steam-787:787_tuerqi?secret=T3KQO2V4N3M6X5R2W4ZQ3L2K4X3V5M&issuer=Steam
            """.trimIndent()

            ImportMode.JSON -> """
                JSON 格式：对象数组
                支持字段别名：name/title/service, issuer/type, usr/account/username/email, key/secret/totp/seed
                
                示例：
                [
                  {
                    "name": "我的Steam",
                    "issuer": "Steam",
                    "usr": "787_tuerqi",
                    "key": "T3KQO2V4N3M6X5R2W4ZQ3L2K4X3V5M",
                    "algorithm": "SHA1",
                    "digits": 6,
                    "period": 30
                  }
                ]
            """.trimIndent()

            ImportMode.CSV -> """
                CSV 格式：第一行为表头，支持以下列名（不区分大小写）
                name/title/service, issuer/type, usr/account/username/email, key/secret/totp/seed, algorithm/algo, digits, period
                
                示例：
                name,issuer,usr,key,algorithm,digits,period
                我的Steam,Steam,787_tuerqi,T3KQO2V4N3M6X5R2W4ZQ3L2K4X3V5M,SHA1,6,30
                GitHub,GitHub,user@example.com,JBSWY3DPEHPK3PXP,SHA1,6,30
            """.trimIndent()
        }
    }

    private fun readTextFromUri(contentResolver: ContentResolver, uri: Uri): String {
        val stringBuilder = StringBuilder()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line).append("\n")
                }
            }
        }
        return stringBuilder.toString()
    }

    private data class TOTPInfo(
        val name: String,
        val issuer: String = "",
        val usr: String = "",
        val key: String,
        val algorithm: String = "SHA1",
        val digits: Int = 6,
        val period: Int = 30
    ) {
        fun toJson(): String {
            return JSONObject().apply {
                put("name", name)
                put("issuer", issuer)
                put("usr", usr)
                put("key", key)
                put("algorithm", algorithm)
                put("digits", digits)
                put("period", period)
            }.toString()
        }
    }

    private fun buildJson(list: List<TOTPInfo>): String {
        val array = JSONArray()
        list.forEach { array.put(JSONObject(it.toJson())) }
        return JSONObject().put("list", array).toString()
    }

    private fun parseFile(contentResolver: ContentResolver, uri: Uri, mode: ImportMode): List<TOTPInfo> {
        return when (mode) {
            ImportMode.TXT -> parseTxtFile(contentResolver, uri)
            ImportMode.JSON -> parseJsonFile(contentResolver, uri)
            ImportMode.CSV -> parseCsvFile(contentResolver, uri)
        }
    }

    private fun parseTxtFile(contentResolver: ContentResolver, uri: Uri): List<TOTPInfo> {
        val result = mutableListOf<TOTPInfo>()
        processFileLineByLine(contentResolver, uri) { line ->
            if (line.isBlank()) return@processFileLineByLine
            parseTotpUri(line)?.let { result.add(it) }
        }
        return result
    }

    private fun parseJsonFile(contentResolver: ContentResolver, uri: Uri): List<TOTPInfo> {
        val result = mutableListOf<TOTPInfo>()
        try {
            val text = readTextFromUri(contentResolver, uri)
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                parseJsonObject(obj)?.let { result.add(it) }
            }
        } catch (e: Exception) {
            log("JSON 解析失败: ${e.message}")
        }
        return result
    }

    private fun parseCsvFile(contentResolver: ContentResolver, uri: Uri): List<TOTPInfo> {
        val result = mutableListOf<TOTPInfo>()
        try {
            val lines = readTextFromUri(contentResolver, uri).lines()
            if (lines.size < 2) return result
            val headers = parseCsvLine(lines[0])
            val indices = headers.mapIndexed { index, s -> s.lowercase() to index }.toMap()
            for (i in 1 until lines.size) {
                if (lines[i].isBlank()) continue
                val values = parseCsvLine(lines[i])
                parseCsvRow(headers, values)?.let { result.add(it) }
            }
        } catch (e: Exception) {
            log("CSV 解析失败: ${e.message}")
        }
        return result
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(char)
            }
        }
        result.add(sb.toString().trim())
        return result
    }

    private fun parseCsvRow(headers: List<String>, values: List<String>): TOTPInfo? {
        val map = headers.mapIndexed { index, name ->
            name.lowercase() to values.getOrElse(index) { "" }
        }.toMap()

        val key = getValueFromAliases(map, "key", "secret", "totp", "seed") ?: return null
        val name = getValueFromAliases(map, "name", "title", "service") ?: ""
        val issuer = getValueFromAliases(map, "issuer", "type") ?: ""
        val usr = getValueFromAliases(map, "usr", "account", "username", "email") ?: ""
        val algorithm = getValueFromAliases(map, "algorithm", "algo") ?: "SHA1"
        val digits = getValueFromAliases(map, "digits")?.toIntOrNull() ?: 6
        val period = getValueFromAliases(map, "period")?.toIntOrNull() ?: 30

        return TOTPInfo(
            name = name,
            issuer = issuer,
            usr = usr,
            key = key,
            algorithm = algorithm,
            digits = digits,
            period = period
        )
    }

    private fun parseJsonObject(obj: JSONObject): TOTPInfo? {
        val key = getJsonString(obj, "key", "secret", "totp", "seed") ?: return null
        val name = getJsonString(obj, "name", "title", "service") ?: ""
        val issuer = getJsonString(obj, "issuer", "type") ?: ""
        val usr = getJsonString(obj, "usr", "account", "username", "email") ?: ""
        val algorithm = getJsonString(obj, "algorithm", "algo") ?: "SHA1"
        val digits = getJsonInt(obj, "digits") ?: 6
        val period = getJsonInt(obj, "period") ?: 30

        return TOTPInfo(
            name = name,
            issuer = issuer,
            usr = usr,
            key = key,
            algorithm = algorithm,
            digits = digits,
            period = period
        )
    }

    private fun getJsonString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                return obj.getString(key)
            }
        }
        return null
    }

    private fun getJsonInt(obj: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                return when (val value = obj.get(key)) {
                    is Int -> value
                    is String -> value.toIntOrNull()
                    else -> null
                }
            }
        }
        return null
    }

    private fun getValueFromAliases(map: Map<String, String>, vararg aliases: String): String? {
        for (alias in aliases) {
            val value = map[alias.lowercase()]
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun parseTotpUri(totpUri: String): TOTPInfo? {
        try {
            val uri = Uri.parse(totpUri)

            if (uri.scheme != "otpauth" || uri.host != "totp") {
                return null
            }

            val path = uri.path ?: return null
            val splitPath = path.split(":")
            if (splitPath.size != 2) {
                return null
            }

            val issuerFromPath = splitPath[0].trim('/')
            val account = splitPath[1]

            val secret = uri.getQueryParameter("secret") ?: return null
            val issuerFromQuery = uri.getQueryParameter("issuer") ?: ""
            val algorithm = uri.getQueryParameter("algorithm") ?: "SHA1"
            val digits = uri.getQueryParameter("digits")?.toIntOrNull() ?: 6
            val period = uri.getQueryParameter("period")?.toIntOrNull() ?: 30

            return TOTPInfo(
                name = issuerFromPath,
                issuer = issuerFromQuery,
                usr = account,
                key = secret,
                algorithm = algorithm,
                digits = digits,
                period = period
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun processFileLineByLine(contentResolver: ContentResolver, uri: Uri, processLine: (String) -> Unit) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    processLine(line!!)
                }
            }
        }
    }
}
