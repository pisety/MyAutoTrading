package com.my.autotradingapp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.my.autotradingapp.data.AccountInfo
import com.my.autotradingapp.data.HoldingStock
import com.my.autotradingapp.data.SurgeTradingConfig
import com.my.autotradingapp.data.SymbolSurgeConfig
import com.my.autotradingapp.ui.TradingViewModel
import com.my.autotradingapp.ui.theme.AutoTradingAppTheme
import com.my.autotradingapp.utils.CryptoUtils
import java.text.NumberFormat
import java.util.*
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.messaging.FirebaseMessaging
import com.my.autotradingapp.ui.components.WatchlistManagementDialog
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutoTradingAppTheme {
                MainContent()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(viewModel: TradingViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("주식", "코인")
    
    val currentUserId by viewModel.userId.collectAsState()
    val context = LocalContext.current
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            if (permission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(currentUserId) {
        if (currentUserId.isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    viewModel.saveFcmToken(task.result)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("자동매매 Pro") },
                actions = {
                    // Settings button handled per-tab inside tab content
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> StockTabContent(viewModel)
                1 -> CryptoTabContent(viewModel)
            }
        }
    }
}

// ==================== STOCK TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockTabContent(viewModel: TradingViewModel) {
    var showAccountSetup by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (showAccountSetup) {
            StockAccountSetupScreen(
                onDismiss = { showAccountSetup = false },
                viewModel = viewModel
            )
        } else {
            StockTradingScreen(
                viewModel = viewModel,
                onSettingsClick = { showAccountSetup = true }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockAccountSetupScreen(onDismiss: () -> Unit, viewModel: TradingViewModel) {
    val accountData by viewModel.stockAccountData.collectAsState()
    val currentUserId by viewModel.userId.collectAsState()
    val activeAcc = accountData.saved_accounts[accountData.active_account_id]
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TradingApp", android.content.Context.MODE_PRIVATE)
    val history = remember { 
        prefs.getStringSet("UserIdHistory", emptySet())?.toList()
            ?.filter { !it.contains("_MOCK_") && !it.contains("_REAL_") } 
            ?: emptyList() 
    }
    
    var userIdInput by remember { mutableStateOf(currentUserId) }
    var userIdExpanded by remember { mutableStateOf(false) }
    
    var broker by remember { mutableStateOf(activeAcc?.broker ?: "한국투자") }
    var isMock by remember { mutableStateOf(activeAcc?.is_mock ?: true) }
    var isOverseas by remember { mutableStateOf(activeAcc?.is_overseas ?: true) }
    var accountNumber by remember { mutableStateOf("") }
    var appKey by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var isEditingKeys by remember { mutableStateOf(false) }
    
    // PIN States
    var newPinInput by remember { mutableStateOf("") }
    var verifyPinInput by remember { mutableStateOf("") }
    var isPinVerified by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    val brokers = listOf("한국투자", "키움", "미래에셋")
    var brokerExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(broker) {
        if (broker == "키움") {
            isOverseas = false
        }
    }

    // PIN Verification Dialog Trigger
    LaunchedEffect(accountData.pin) {
        val savedPin = prefs.getString("SavedPin_stock_$currentUserId", "")
        if (accountData.pin.isNotEmpty() && !isPinVerified) {
            if (savedPin == accountData.pin) {
                isPinVerified = true
            } else {
                showPinDialog = true
            }
        } else {
            isPinVerified = true
        }
    }

    LaunchedEffect(accountData, broker, isMock, isOverseas, isPinVerified) {
        if (!isPinVerified) {
            accountNumber = ""
            appKey = ""
            appSecret = ""
            return@LaunchedEffect
        }
        val key = "${broker}_${if (isMock) "MOCK" else "REAL"}_${if (isOverseas) "OS" else "KR"}"
        val saved = accountData.saved_accounts[key]
        if (saved != null) {
            accountNumber = saved.account_number
            appKey = saved.app_key
            appSecret = saved.app_secret
            isEditingKeys = false
        } else {
            accountNumber = ""
            appKey = ""
            appSecret = ""
            isEditingKeys = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("주식 계좌 및 UID 설정", style = MaterialTheme.typography.headlineSmall)

        ExposedDropdownMenuBox(
            expanded = userIdExpanded,
            onExpandedChange = { userIdExpanded = !userIdExpanded }
        ) {
            OutlinedTextField(
                value = userIdInput,
                onValueChange = { userIdInput = it },
                label = { Text("User ID (Firebase 노드)") },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { 
                    TextButton(onClick = { 
                        if (userIdInput != currentUserId) {
                            isPinVerified = false
                            viewModel.setUserId(userIdInput)
                        }
                    }) {
                        Text("불러오기")
                    }
                }
            )
            if (history.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = userIdExpanded,
                    onDismissRequest = { userIdExpanded = false }
                ) {
                    history.forEach { histId ->
                        DropdownMenuItem(
                            text = { Text(histId) },
                            onClick = {
                                userIdInput = histId
                                userIdExpanded = false
                                if (histId != currentUserId) {
                                    isPinVerified = false
                                    viewModel.setUserId(histId)
                                }
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = newPinInput,
            onValueChange = { newPinInput = it },
            label = { Text("새로운 비밀번호(PIN) 4자리 설정 (선택)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation()
        )

        HorizontalDivider()

        ExposedDropdownMenuBox(
            expanded = brokerExpanded,
            onExpandedChange = { brokerExpanded = !brokerExpanded }
        ) {
            OutlinedTextField(
                value = broker,
                onValueChange = {},
                readOnly = true,
                label = { Text("증권사 선택") },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = brokerExpanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(
                expanded = brokerExpanded,
                onDismissRequest = { brokerExpanded = false }
            ) {
                brokers.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        broker = name
                        brokerExpanded = false
                    })
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("투자 구분", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.clickable { isMock = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isMock, onClick = { isMock = true })
                    Text("모의")
                }
                Row(modifier = Modifier.clickable { isMock = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !isMock, onClick = { isMock = false })
                    Text("실전")
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("시장 구분", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.clickable { isOverseas = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !isOverseas, onClick = { isOverseas = false })
                    Text("국내")
                }
                Row(modifier = Modifier.clickable(enabled = broker != "키움") { isOverseas = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isOverseas, onClick = { isOverseas = true }, enabled = broker != "키움")
                    Text("해외" + if (broker == "키움") " (지원안함)" else "")
                }
            }
        }

        if (!isEditingKeys) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("계좌 정보가 저장되어 있습니다.", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { isEditingKeys = true }) {
                    Text("계좌정보 수정")
                }
            }
        } else {
            OutlinedTextField(
                value = accountNumber,
                onValueChange = { accountNumber = it },
                label = { Text("계좌번호 (예: 12345678-01)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = appKey,
                onValueChange = { appKey = it },
                label = { Text("App Key") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                label = { Text("App Secret") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                viewModel.setUserId(userIdInput)
                viewModel.clearAuthErrorLocally("stock")
                viewModel.saveAccountSecurely(
                    AccountInfo(broker, isMock, isOverseas, accountNumber, appKey, appSecret),
                    newPinInput,
                    accountData.pin,
                    "stock"
                ) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (success) {
                        prefs.edit().putStringSet("UserIdHistory", history.toSet() + userIdInput).apply()
                        onDismiss()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("설정 저장 및 적용")
        }
        
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("닫기")
        }
    }

    if (showPinDialog) {
        var savePinChecked by remember { mutableStateOf(false) }
        val onPinConfirm = {
            val decryptedPin = try { CryptoUtils.decrypt(accountData.pin) } catch(e: Exception) { accountData.pin }
            if (verifyPinInput == decryptedPin) {
                isPinVerified = true
                showPinDialog = false
                verifyPinInput = ""
                prefs.edit().putStringSet("UserIdHistory", history.toSet() + userIdInput).apply()
                if (savePinChecked) {
                    prefs.edit().putString("SavedPin_stock_$currentUserId", accountData.pin).apply()
                } else {
                    prefs.edit().remove("SavedPin_stock_$currentUserId").apply()
                }
            } else {
                Toast.makeText(context, "PIN이 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        AlertDialog(
            onDismissRequest = { 
                showPinDialog = false
                isPinVerified = false
            },
            title = { Text("보안 계정 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = verifyPinInput,
                        onValueChange = { verifyPinInput = it },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onPinConfirm() }),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = savePinChecked, onCheckedChange = { savePinChecked = it })
                        Text("비밀번호 기억하기")
                    }
                }
            },
            confirmButton = {
                Button(onClick = onPinConfirm) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinDialog = false
                    isPinVerified = false
                }) { Text("취소") }
            }
        )
    }
}

@Composable
fun StockTradingScreen(
    viewModel: TradingViewModel,
    onSettingsClick: () -> Unit
) {
    val status by viewModel.stockStatus.collectAsState()
    val settings by viewModel.stockSettings.collectAsState()
    var isSurgeStatusExpanded by remember { mutableStateOf(true) }
    val orderStatus by viewModel.manualOrderStatus.collectAsState()
    val context = LocalContext.current
    var showHoldings by remember { mutableStateOf(true) }
    var showWatchList by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    
    LaunchedEffect(orderStatus) {
        orderStatus?.let {
            if (it.status == "FAILED") {
                Toast.makeText(context, "주문 실패: ${it.error_message}", Toast.LENGTH_LONG).show()
            } else if (it.status == "SUCCESS") {
                Toast.makeText(context, "주문 접수 성공!", Toast.LENGTH_SHORT).show()
            }
            viewModel.resetManualOrderStatus()
            viewModel.clearManualOrderFirebaseStatus("stock")
        }
    }
    
    val accountData by viewModel.stockAccountData.collectAsState()
    val activeAcc = accountData.saved_accounts[accountData.active_account_id]
    val isOverseas = activeAcc?.is_overseas ?: true
    val isMock = activeAcc?.is_mock ?: true
    val brokerName = activeAcc?.broker ?: "설정안됨"
    
    val suggestions = status.stock_names
    var showWatchlistDialog by remember { mutableStateOf(false) }

    if (showWatchlistDialog) {
        val filteredWatchSymbols = settings.watch_symbols.filterKeys { sym -> if (isOverseas) !sym.all { it.isDigit() } else sym.all { it.isDigit() } }
        WatchlistManagementDialog(
            initialSymbols = filteredWatchSymbols.entries.sortedBy { it.value.order }.map { (code, _) ->
                val name = status.stock_names[code] ?: suggestions[code]
                if (name != null) "$name ($code)" else code
            },
            isOverseas = isOverseas,
            isCrypto = false,
            suggestions = suggestions,
            onDismiss = { showWatchlistDialog = false },
            onSave = { managedList ->
                viewModel.updateWatchSymbols(managedList, "stock", isOverseas)
                showWatchlistDialog = false
            }
        )
    }

    val authError = status.authError
    LaunchedEffect(authError) {
        if (authError.isNotEmpty()) {
            Toast.makeText(context, authError, Toast.LENGTH_LONG).show()
        }
    }

    var showOrderDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (showOrderDialog != null) {
        val (symbol, command) = showOrderDialog!!
        var isConditional by remember { mutableStateOf(false) }
        var orderPrice by remember { mutableStateOf("") }
        var orderQty by remember { mutableStateOf("") }
        val displayName = status.stock_names[symbol] ?: status.holding_stocks[symbol]?.name?.ifEmpty { null } ?: symbol

        AlertDialog(
            onDismissRequest = { showOrderDialog = null },
            title = { Text(text = if (isConditional) "조건부 예약 주문" else "수동 주문") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (!isConditional) {
                            Button(onClick = { isConditional = false }, modifier = Modifier.weight(1f)) { Text("즉시 실행") }
                            OutlinedButton(onClick = { isConditional = true }, modifier = Modifier.weight(1f)) { Text("조건부 예약") }
                        } else {
                            OutlinedButton(onClick = { isConditional = false }, modifier = Modifier.weight(1f)) { Text("즉시 실행") }
                            Button(onClick = { isConditional = true }, modifier = Modifier.weight(1f)) { Text("조건부 예약") }
                        }
                    }
                    if (isConditional) {
                        Text(text = "현재가가 아래 목표가에 도달하면 자동으로 ${if (command == "BUY") "매수" else "매도"}합니다.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(text = "$displayName 종목을 지정가로 즉시 ${if (command == "BUY") "매수" else "매도"}합니다.")
                    }
                    
                    val currentPriceNum = status.stock_prices[symbol] ?: status.holding_stocks[symbol]?.current_price ?: 0.0
                    val currentPriceText = "현재가: ${if (isOverseas) "$" else "₩"}${NumberFormat.getNumberInstance(Locale.US).format(currentPriceNum)}"
                    Text(text = currentPriceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    
                    OutlinedTextField(
                        value = orderPrice,
                        onValueChange = { newValue ->
                            val clean = newValue.replace(",", "")
                            if (clean.isEmpty()) {
                                orderPrice = ""
                            } else {
                                if (!isOverseas) {
                                    val filtered = clean.filter { char -> char.isDigit() }.substringBefore('.')
                                    val num = filtered.toLongOrNull()
                                    orderPrice = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                                } else {
                                    val filtered = clean.filter { char -> char.isDigit() || char == '.' }
                                    val parts = filtered.split(".")
                                    val num = parts[0].toLongOrNull()
                                    var formatted = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                                    if (parts.size > 1) formatted += "." + parts[1]
                                    orderPrice = formatted
                                }
                            }
                        },
                        label = { Text(if (isConditional) "목표 가격 (${if (isOverseas) "$" else "₩"})" else "주문 가격 (${if (isOverseas) "$" else "₩"})") },
                        keyboardOptions = KeyboardOptions(keyboardType = if (!isOverseas) KeyboardType.Number else KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = orderQty,
                        onValueChange = { 
                            val filtered = it.filter { char -> char.isDigit() }
                            orderQty = filtered 
                        },
                        label = { Text("주문 수량") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val priceVal = orderPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                val qtyVal = orderQty.toDoubleOrNull() ?: 0.0
                val isValid = priceVal > 0.0 && qtyVal > 0.0
                Button(
                    enabled = isValid,
                    onClick = {
                        val qty = orderQty.toDoubleOrNull() ?: 1.0
                        val price = orderPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                    if (isConditional) {
                        viewModel.sendConditionalOrder(symbol, command, qty, price, "stock")
                        Toast.makeText(context, "조건부 예약 전송 완료", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.sendManualOrder(command, symbol, qty, price, "stock")
                    }
                    showOrderDialog = null
                }) {
                    Text(if (isConditional) "예약 전송" else "주문 전송")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOrderDialog = null }) {
                    Text("취소")
                }
            }
        )
    }

    var showStopLossDialog by remember { mutableStateOf<String?>(null) }

    if (showStopLossDialog != null) {
        val symbol = showStopLossDialog!!
        var triggerPrice by remember { mutableStateOf("") }
        var volumePercent by remember { mutableStateOf("100") }
        val displayName = status.stock_names[symbol] ?: status.holding_stocks[symbol]?.name?.ifEmpty { null } ?: symbol

        AlertDialog(
            onDismissRequest = { showStopLossDialog = null },
            title = { Text("자동 손절 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$displayName 종목이 목표가 이하로 하락 시 전량 또는 일부를 매도합니다.")
                    
                    val currentPriceNum = status.stock_prices[symbol] ?: status.holding_stocks[symbol]?.current_price ?: 0.0
                    val currentPriceText = "현재가: ${if (isOverseas) "$" else "₩"}${NumberFormat.getNumberInstance(Locale.US).format(currentPriceNum)}"
                    Text(text = currentPriceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    OutlinedTextField(
                        value = triggerPrice,
                        onValueChange = { newValue ->
                            val clean = newValue.replace(",", "")
                            if (clean.isEmpty()) {
                                triggerPrice = ""
                            } else {
                                if (!isOverseas) {
                                    val filtered = clean.filter { char -> char.isDigit() }.substringBefore('.')
                                    val num = filtered.toLongOrNull()
                                    triggerPrice = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                                } else {
                                    val filtered = clean.filter { char -> char.isDigit() || char == '.' }
                                    val parts = filtered.split(".")
                                    val num = parts[0].toLongOrNull()
                                    var formatted = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                                    if (parts.size > 1) formatted += "." + parts[1]
                                    triggerPrice = formatted
                                }
                            }
                        },
                        label = { Text("손절 트리거 가격") },
                        keyboardOptions = KeyboardOptions(keyboardType = if (!isOverseas) KeyboardType.Number else KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = volumePercent,
                        onValueChange = { 
                            val filtered = it.filter { char -> char.isDigit() }
                            volumePercent = filtered 
                        },
                        label = { Text("매도 비율 (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val priceVal = triggerPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                val percentVal = volumePercent.toIntOrNull() ?: 0
                val isValid = priceVal > 0.0 && percentVal in 1..100
                Button(
                    enabled = isValid,
                    onClick = {
                        val price = triggerPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                        val percent = volumePercent.toIntOrNull() ?: 100
                    viewModel.sendStopLossOrder(symbol, price, percent, "stock")
                    Toast.makeText(context, "자동 손절 설정 완료", Toast.LENGTH_SHORT).show()
                    showStopLossDialog = null
                }) {
                    Text("설정 완료")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopLossDialog = null }) { Text("취소") }
            }
        )
    }

    val filteredWatchSymbols = settings.watch_symbols.filterKeys { sym -> if (isOverseas) !sym.all { it.isDigit() } else sym.all { it.isDigit() } }
    val watchSymbolsList = filteredWatchSymbols.entries.sortedBy { it.value.order }.map { it.key }

    var showSurgeDialog by remember { mutableStateOf(false) }

    if (showSurgeDialog) {
        val surge = settings.surge_trading
        var surgeEnabled by remember { mutableStateOf(surge.enabled) }
        
        val timeWindows = remember { mutableStateMapOf<String, String>() }
        val surgePercents = remember { mutableStateMapOf<String, String>() }
        val buyQuantities = remember { mutableStateMapOf<String, String>() }
        val symConfigs = remember { mutableStateMapOf<String, List<String>>() }
        
        LaunchedEffect(surge, watchSymbolsList) {
            watchSymbolsList.forEach { sym ->
                if (!symConfigs.containsKey(sym)) {
                    val cfgs = surge.per_symbol_config[sym] ?: emptyList()
                    val ids = mutableListOf<String>()
                    if (cfgs.isEmpty()) {
                        val id = java.util.UUID.randomUUID().toString()
                        ids.add(id)
                        timeWindows[id] = "60"
                        surgePercents[id] = "3.0"
                        buyQuantities[id] = ""
                    } else {
                        cfgs.forEach { cfg ->
                            val id = java.util.UUID.randomUUID().toString()
                            ids.add(id)
                            timeWindows[id] = cfg.time_window_seconds.toString()
                            surgePercents[id] = cfg.surge_percent_threshold.toString()
                            buyQuantities[id] = if (cfg.buy_quantity == 0.0) "" else cfg.buy_quantity.toString()
                        }
                    }
                    symConfigs[sym] = ids
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showSurgeDialog = false },
            title = { Text("급등주 단타 감시 설정 (${if (settings.surge_trading.enabled) "ON" else "OFF"})") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("기능 전체 활성화", fontWeight = FontWeight.Bold)
                        Switch(checked = surgeEnabled, onCheckedChange = { surgeEnabled = it })
                    }
                    
                    if (watchSymbolsList.isEmpty()) {
                        Text("관심 종목이 없습니다.\n먼저 관심 종목을 추가하고 저장해주세요.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    } else {
                        var expandedSymbol by remember { mutableStateOf<String?>(null) }
                        
                        watchSymbolsList.forEach { sym ->
                            val name = status.stock_names[sym] ?: sym
                            val isExpanded = expandedSymbol == sym
                            
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedSymbol = if (isExpanded) null else sym
                                },
                                elevation = CardDefaults.cardElevation(defaultElevation = if (isExpanded) 4.dp else 1.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            val currentPrice = status.stock_prices[sym] ?: 0.0
                                            if (currentPrice > 0) {
                                                val priceStr = if (isOverseas) "$${String.format(java.util.Locale.US, "%.2f", currentPrice)}" else "₩${String.format(java.util.Locale.US, "%,.0f", currentPrice)}"
                                                Text(priceStr, fontSize = 14.sp, color = Color.DarkGray)
                                            } else {
                                                Text("로딩중...", fontSize = 14.sp, color = Color.Gray)
                                            }
                                        }
                                        Text(if (isExpanded) "▼" else "▶", color = Color.Gray, fontSize = 12.sp)
                                    }
                                    
                                    if (isExpanded) {
                                        symConfigs[sym]?.forEach { id ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = timeWindows[id] ?: "60",
                                                    onValueChange = { timeWindows[id] = it },
                                                    label = { Text("감시 (초)") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = surgePercents[id] ?: "3.0",
                                                    onValueChange = { surgePercents[id] = it },
                                                    label = { Text("급등 (%)") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                                                OutlinedTextField(
                                                    value = buyQuantities[id] ?: "0",
                                                    onValueChange = { buyQuantities[id] = it },
                                                    label = { Text("매수 수량 (0은 제외)") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(onClick = { 
                                                    val ids = symConfigs[sym]?.toMutableList() ?: mutableListOf()
                                                    ids.remove(id)
                                                    symConfigs[sym] = ids
                                                    timeWindows.remove(id)
                                                    surgePercents.remove(id)
                                                    buyQuantities.remove(id)
                                                }) { Text("❌") }
                                            }
                                        }
                                        TextButton(onClick = {
                                            val id = java.util.UUID.randomUUID().toString()
                                            val ids = symConfigs[sym]?.toMutableList() ?: mutableListOf()
                                            ids.add(id)
                                            symConfigs[sym] = ids
                                            timeWindows[id] = "60"
                                            surgePercents[id] = "3.0"
                                            buyQuantities[id] = ""
                                        }) { Text("+ 설정 추가") }
                                    } else {
                                        val activeIds = symConfigs[sym]?.filter { (buyQuantities[it]?.toDoubleOrNull() ?: 0.0) > 0.0 } ?: emptyList()
                                        if (activeIds.isNotEmpty()) {
                                            if (surgeEnabled) {
                                                activeIds.forEach { id ->
                                                    Text("설정됨: ${timeWindows[id]}초 / ${surgePercents[id]}% / ${buyQuantities[id]}주", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                                                }
                                            } else {
                                                activeIds.forEach { id ->
                                                    Text("대기중 (OFF): ${timeWindows[id]}초 / ${surgePercents[id]}% / ${buyQuantities[id]}주", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                                }
                                            }
                                        } else {
                                            Text("감시 제외 (수량 0)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val isAllValid = watchSymbolsList.all { sym ->
                    val ids = symConfigs[sym] ?: emptyList()
                    if (ids.isEmpty()) true
                    else ids.all { id ->
                        val qStr = buyQuantities[id]
                        if (qStr.isNullOrBlank()) true
                        else (qStr.toDoubleOrNull() ?: 0.0) > 0.0
                    }
                }
                Button(
                    enabled = isAllValid,
                    onClick = {
                    val perSymbolMap = mutableMapOf<String, List<SymbolSurgeConfig>>()
                    watchSymbolsList.forEach { sym ->
                        val list = mutableListOf<SymbolSurgeConfig>()
                        symConfigs[sym]?.forEach { id ->
                            val qty = buyQuantities[id]?.toDoubleOrNull() ?: 0.0
                            if (qty > 0.0 || symConfigs[sym]?.size == 1) {
                                list.add(SymbolSurgeConfig(
                                    time_window_seconds = timeWindows[id]?.toIntOrNull() ?: 60,
                                    surge_percent_threshold = surgePercents[id]?.toFloatOrNull() ?: 3.0f,
                                    buy_quantity = qty
                                ))
                            }
                        }
                        if (list.isNotEmpty()) perSymbolMap[sym] = list
                    }
                    val config = SurgeTradingConfig(
                        enabled = surgeEnabled,
                        allow_extended_hours = true,
                        per_symbol_config = perSymbolMap
                    )
                    viewModel.updateSurgeTradingSettings(config, "stock")
                    Toast.makeText(context, "급등주 감시 설정 완료", Toast.LENGTH_SHORT).show()
                    showSurgeDialog = false
                }) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSurgeDialog = false }) { Text("취소") }
            }
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .mouseScroll(listState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Settings button row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onSettingsClick) {
                    Text("⚙ 계좌 설정")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusText = "[${brokerName} | ${if (isMock) "모의" else "실전"} | ${if (isOverseas) "해외주식" else "국내주식"}]"
                    Text(text = statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                    
                    Text(text = "총 평가자산", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "${if (isOverseas) "$" else "₩"}${NumberFormat.getNumberInstance(Locale.US).format(status.total_balance)}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "수익률: ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${String.format(Locale.US, "%.2f", status.current_yield)}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (status.current_yield >= 0) Color(0xFFD32F2F) else Color(0xFF1976D2)
                        )
                    }

                    var lastKnownTime by remember { mutableStateOf("") }
                    if (status.last_updated.isNotBlank()) {
                        lastKnownTime = status.last_updated.replace(Regex("\\.(\\d)\\d+"), ".$1")
                    }
                    
                    Text(
                        text = if (lastKnownTime.isNotEmpty()) "최종 갱신: $lastKnownTime" else " ",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHoldings = !showHoldings }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("보유 종목 현황", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (showHoldings) "▲" else "▼", color = Color.Gray)
            }
        }

        if (showHoldings) {
            val visibleHoldings = status.holding_stocks.filter { it.value.current_price > 0.0 }
            if (visibleHoldings.isEmpty()) {
                item {
                    Text("보유 중인 종목이 없습니다.", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                }
            } else {
                items(visibleHoldings.toList()) { (symbol, stock) ->
                    HoldingStockItem(
                        symbol = symbol, 
                        stock = stock, 
                        isOverseas = isOverseas, 
                        isCrypto = false, 
                        onStopLossClick = { showStopLossDialog = symbol },
                        onBuyClick = { showOrderDialog = symbol to "BUY" },
                        onSellClick = { showOrderDialog = symbol to "SELL" }
                    )
                }
            }
        }

        item {
            Button(
                onClick = { showWatchlistDialog = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text("관심 종목 관리")
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showWatchList = !showWatchList }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("실시간 시세 및 주문", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (showWatchList) "▲" else "▼", color = Color.Gray)
            }
        }

        if (showWatchList) {
            if (watchSymbolsList.isEmpty()) {
                item {
                    Text("관심 종목이 없습니다.", color = Color.Gray)
                }
            } else {
                items(watchSymbolsList) { symbol ->
                    val price = status.stock_prices[symbol] ?: 0.0
                    StockItem(
                        symbol = symbol,
                        name = status.stock_names[symbol] ?: symbol,
                        price = price,
                        isOverseas = isOverseas,
                        onBuyClick = { showOrderDialog = symbol to "BUY" }
                    )
                }
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "자동매매 엔진 가동", fontWeight = FontWeight.Bold)
                Switch(
                    checked = settings.is_auto_trading,
                    onCheckedChange = { viewModel.toggleAutoTrading(it, "stock") }
                )
            }
            if (settings.is_auto_trading) {
                OutlinedButton(
                    onClick = { showSurgeDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("급등주 단타 감시 설정 (${if (settings.surge_trading.enabled) "ON" else "OFF"})")
                }
                
                if (settings.surge_trading.enabled) {
                    val activeSurge = settings.surge_trading.per_symbol_config.filter { it.value.any { cfg -> cfg.buy_quantity > 0 } }
                    if (activeSurge.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { isSurgeStatusExpanded = !isSurgeStatusExpanded }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("급등주 감시 현황", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(if (isSurgeStatusExpanded) "접기 ▲" else "펼치기 ▼", fontSize = 12.sp, color = Color.Gray)
                        }
                        if (isSurgeStatusExpanded) {
                            activeSurge.forEach { (sym, cfgs) ->
                                val name = status.stock_names[sym] ?: status.holding_stocks[sym]?.name?.ifEmpty { null } ?: sym
                                cfgs.filter { it.buy_quantity > 0 }.forEach { cfg ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(name)
                                        Text("${cfg.time_window_seconds}초 / ${cfg.surge_percent_threshold}% / ${cfg.buy_quantity}주", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ==================== CRYPTO TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoTabContent(viewModel: TradingViewModel) {
    var showAccountSetup by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (showAccountSetup) {
            CryptoAccountSetupScreen(
                onDismiss = { showAccountSetup = false },
                viewModel = viewModel
            )
        } else {
            CryptoTradingScreen(
                viewModel = viewModel,
                onSettingsClick = { showAccountSetup = true }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoAccountSetupScreen(onDismiss: () -> Unit, viewModel: TradingViewModel) {
    val accountData by viewModel.cryptoAccountData.collectAsState()
    val currentUserId by viewModel.userId.collectAsState()
    val activeAcc = accountData.saved_accounts[accountData.active_account_id]
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TradingApp", android.content.Context.MODE_PRIVATE)
    val history = remember { 
        prefs.getStringSet("UserIdHistory", emptySet())?.toList()
            ?.filter { !it.contains("_MOCK_") && !it.contains("_REAL_") } 
            ?: emptyList() 
    }
    
    var userIdInput by remember { mutableStateOf(currentUserId) }
    var userIdExpanded by remember { mutableStateOf(false) }
    
    // Crypto: broker is always 업비트, no mock/real, no overseas, no account number
    var appKey by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var isEditingKeys by remember { mutableStateOf(false) }
    
    // PIN States
    var newPinInput by remember { mutableStateOf("") }
    var verifyPinInput by remember { mutableStateOf("") }
    var isPinVerified by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    // PIN Verification Dialog Trigger
    LaunchedEffect(accountData.pin) {
        val savedPin = prefs.getString("SavedPin_crypto_$currentUserId", "")
        if (accountData.pin.isNotEmpty() && !isPinVerified) {
            if (savedPin == accountData.pin) {
                isPinVerified = true
            } else {
                showPinDialog = true
            }
        } else {
            isPinVerified = true
        }
    }

    LaunchedEffect(accountData, isPinVerified) {
        if (!isPinVerified) {
            appKey = ""
            appSecret = ""
            return@LaunchedEffect
        }
        val key = "업비트_REAL_CRYPTO"
        val saved = accountData.saved_accounts[key]
        if (saved != null) {
            appKey = saved.app_key
            appSecret = saved.app_secret
            isEditingKeys = false
        } else {
            appKey = ""
            appSecret = ""
            isEditingKeys = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("코인 계좌 및 UID 설정", style = MaterialTheme.typography.headlineSmall)

        ExposedDropdownMenuBox(
            expanded = userIdExpanded,
            onExpandedChange = { userIdExpanded = !userIdExpanded }
        ) {
            OutlinedTextField(
                value = userIdInput,
                onValueChange = { userIdInput = it },
                label = { Text("User ID (Firebase 노드)") },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { 
                    TextButton(onClick = { 
                        if (userIdInput != currentUserId) {
                            isPinVerified = false
                            viewModel.setUserId(userIdInput)
                        }
                    }) {
                        Text("불러오기")
                    }
                }
            )
            if (history.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = userIdExpanded,
                    onDismissRequest = { userIdExpanded = false }
                ) {
                    history.forEach { histId ->
                        DropdownMenuItem(
                            text = { Text(histId) },
                            onClick = {
                                userIdInput = histId
                                userIdExpanded = false
                                if (histId != currentUserId) {
                                    isPinVerified = false
                                    viewModel.setUserId(histId)
                                }
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = newPinInput,
            onValueChange = { newPinInput = it },
            label = { Text("새로운 비밀번호(PIN) 4자리 설정 (선택)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation()
        )

        HorizontalDivider()

        // Broker is fixed to 업비트
        OutlinedTextField(
            value = "업비트",
            onValueChange = {},
            readOnly = true,
            label = { Text("거래소") },
            modifier = Modifier.fillMaxWidth()
        )

        if (!isEditingKeys) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("계좌 정보가 저장되어 있습니다.", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { isEditingKeys = true }) {
                    Text("계좌정보 수정")
                }
            }
        } else {
            OutlinedTextField(
                value = appKey,
                onValueChange = { appKey = it },
                label = { Text("Access Key") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                label = { Text("Secret Key") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                viewModel.setUserId(userIdInput)
                viewModel.clearAuthErrorLocally("crypto")
                viewModel.saveAccountSecurely(
                    AccountInfo("업비트", false, false, "", appKey, appSecret),
                    newPinInput,
                    accountData.pin,
                    "crypto"
                ) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (success) {
                        prefs.edit().putStringSet("UserIdHistory", history.toSet() + userIdInput).apply()
                        onDismiss()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("설정 저장 및 적용")
        }
        
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("닫기")
        }
    }

    if (showPinDialog) {
        var savePinChecked by remember { mutableStateOf(false) }
        val onPinConfirm = {
            val decryptedPin = try { CryptoUtils.decrypt(accountData.pin) } catch(e: Exception) { accountData.pin }
            if (verifyPinInput == decryptedPin) {
                isPinVerified = true
                showPinDialog = false
                verifyPinInput = ""
                prefs.edit().putStringSet("UserIdHistory", history.toSet() + userIdInput).apply()
                if (savePinChecked) {
                    prefs.edit().putString("SavedPin_crypto_$currentUserId", accountData.pin).apply()
                } else {
                    prefs.edit().remove("SavedPin_crypto_$currentUserId").apply()
                }
            } else {
                Toast.makeText(context, "PIN이 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        AlertDialog(
            onDismissRequest = { 
                showPinDialog = false
                isPinVerified = false
            },
            title = { Text("보안 계정 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = verifyPinInput,
                        onValueChange = { verifyPinInput = it },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onPinConfirm() }),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = savePinChecked, onCheckedChange = { savePinChecked = it })
                        Text("비밀번호 기억하기")
                    }
                }
            },
            confirmButton = {
                Button(onClick = onPinConfirm) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinDialog = false
                    isPinVerified = false
                }) { Text("취소") }
            }
        )
    }
}

@Composable
fun CryptoTradingScreen(
    viewModel: TradingViewModel,
    onSettingsClick: () -> Unit
) {
    val status by viewModel.cryptoStatus.collectAsState()
    val settings by viewModel.cryptoSettings.collectAsState()
    var isSurgeStatusExpanded by remember { mutableStateOf(true) }
    val orderStatus by viewModel.manualOrderStatus.collectAsState()
    val context = LocalContext.current
    var showHoldings by remember { mutableStateOf(true) }
    var showWatchList by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    
    LaunchedEffect(orderStatus) {
        orderStatus?.let {
            if (it.status == "FAILED") {
                Toast.makeText(context, "주문 실패: ${it.error_message}", Toast.LENGTH_LONG).show()
            } else if (it.status == "SUCCESS") {
                Toast.makeText(context, "주문 접수 성공!", Toast.LENGTH_SHORT).show()
            }
            viewModel.resetManualOrderStatus()
            viewModel.clearManualOrderFirebaseStatus("crypto")
        }
    }
    
    val accountData by viewModel.cryptoAccountData.collectAsState()
    val activeAcc = accountData.saved_accounts[accountData.active_account_id]
    val brokerName = activeAcc?.broker ?: "설정안됨"
    
    val suggestions = status.stock_names
    var showWatchlistDialog by remember { mutableStateOf(false) }

    if (showWatchlistDialog) {
        WatchlistManagementDialog(
            initialSymbols = settings.watch_symbols.entries.sortedBy { it.value.order }.map { (code, _) ->
                val cleanCode = code.removePrefix("KRW-")
                val name = status.stock_names[code] ?: suggestions[code] // Look up by full code (e.g. KRW-BTC)
                if (name != null) "$name ($cleanCode)" else cleanCode
            },
            isOverseas = false,
            isCrypto = true,
            suggestions = suggestions,
            onDismiss = { showWatchlistDialog = false },
            onSave = { managedList ->
                viewModel.updateWatchSymbols(managedList, "crypto")
                showWatchlistDialog = false
            }
        )
    }

    val authError = status.authError
    LaunchedEffect(authError) {
        if (authError.isNotEmpty()) {
            Toast.makeText(context, authError, Toast.LENGTH_LONG).show()
        }
    }

    var showOrderDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (showOrderDialog != null) {
        val (symbol, command) = showOrderDialog!!
        var isConditional by remember { mutableStateOf(false) }
        var orderPrice by remember { mutableStateOf("") }
        var orderQty by remember { mutableStateOf("") }
        val displayName = status.stock_names[symbol] ?: status.holding_stocks[symbol]?.name?.ifEmpty { null } ?: symbol

        AlertDialog(
            onDismissRequest = { showOrderDialog = null },
            title = { Text(text = if (isConditional) "조건부 예약 주문" else "수동 주문") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (!isConditional) {
                            Button(onClick = { isConditional = false }, modifier = Modifier.weight(1f)) { Text("즉시 실행") }
                            OutlinedButton(onClick = { isConditional = true }, modifier = Modifier.weight(1f)) { Text("조건부 예약") }
                        } else {
                            OutlinedButton(onClick = { isConditional = false }, modifier = Modifier.weight(1f)) { Text("즉시 실행") }
                            Button(onClick = { isConditional = true }, modifier = Modifier.weight(1f)) { Text("조건부 예약") }
                        }
                    }
                    if (isConditional) {
                        Text(text = "현재가가 아래 목표가에 도달하면 자동으로 ${if (command == "BUY") "매수" else "매도"}합니다.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(text = "$displayName 코인을 지정가로 즉시 ${if (command == "BUY") "매수" else "매도"}합니다.")
                    }
                    
                    val currentPriceNum = status.stock_prices[symbol] ?: status.holding_stocks[symbol]?.current_price ?: 0.0
                    val currentPriceText = "현재가: ₩${NumberFormat.getNumberInstance(Locale.US).format(currentPriceNum)}"
                    Text(text = currentPriceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    
                    OutlinedTextField(
                        value = orderPrice,
                        onValueChange = { newValue ->
                            val clean = newValue.replace(",", "")
                            if (clean.isEmpty()) {
                                orderPrice = ""
                            } else {
                                val filtered = clean.filter { char -> char.isDigit() || char == '.' }
                                val parts = filtered.split(".")
                                val num = parts[0].toLongOrNull()
                                var formatted = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                                if (parts.size > 1) formatted += "." + parts[1]
                                orderPrice = formatted
                            }
                        },
                        label = { Text(if (isConditional) "목표 가격 (₩)" else "주문 가격 (₩)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = orderQty,
                        onValueChange = { orderQty = it },
                        label = { Text("주문 수량 (소수 가능)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val priceVal = orderPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                val qtyVal = orderQty.toDoubleOrNull() ?: 0.0
                val isValid = priceVal > 0.0 && qtyVal > 0.0
                Button(
                    enabled = isValid,
                    onClick = {
                        val qty = orderQty.toDoubleOrNull() ?: 1.0
                        val price = orderPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                    if (isConditional) {
                        viewModel.sendConditionalOrder(symbol, command, qty, price, "crypto")
                        Toast.makeText(context, "조건부 예약 전송 완료", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.sendManualOrder(command, symbol, qty, price, "crypto")
                    }
                    showOrderDialog = null
                }) {
                    Text(if (isConditional) "예약 전송" else "주문 전송")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOrderDialog = null }) {
                    Text("취소")
                }
            }
        )
    }

    var showStopLossDialog by remember { mutableStateOf<String?>(null) }

    if (showStopLossDialog != null) {
        val symbol = showStopLossDialog!!
        var triggerPrice by remember { mutableStateOf("") }
        var volumePercent by remember { mutableStateOf("100") }
        val displayName = status.stock_names[symbol] ?: status.holding_stocks[symbol]?.name?.ifEmpty { null } ?: symbol

        AlertDialog(
            onDismissRequest = { showStopLossDialog = null },
            title = { Text("자동 손절 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$displayName 코인이 목표가 이하로 하락 시 전량 또는 일부를 매도합니다.")
                    
                    val currentPriceNum = status.stock_prices[symbol] ?: status.holding_stocks[symbol]?.current_price ?: 0.0
                    val currentPriceText = "현재가: ₩${NumberFormat.getNumberInstance(Locale.US).format(currentPriceNum)}"
                    Text(text = currentPriceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    
                    OutlinedTextField(
                        value = triggerPrice,
                        onValueChange = { newValue ->
                            val clean = newValue.replace(",", "")
                            if (clean.isEmpty()) {
                                triggerPrice = ""
                            } else {
                                val filtered = clean.filter { char -> char.isDigit() || char == '.' }
                                val parts = filtered.split(".")
                                val num = parts[0].toLongOrNull()
                                var formatted = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                                if (parts.size > 1) formatted += "." + parts[1]
                                triggerPrice = formatted
                            }
                        },
                        label = { Text("손절 트리거 가격 (₩)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = volumePercent,
                        onValueChange = { 
                            val filtered = it.filter { char -> char.isDigit() }
                            volumePercent = filtered 
                        },
                        label = { Text("매도 비율 (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val priceVal = triggerPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                val percentVal = volumePercent.toIntOrNull() ?: 0
                val isValid = priceVal > 0.0 && percentVal in 1..100
                Button(
                    enabled = isValid,
                    onClick = {
                        val price = triggerPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                        val percent = volumePercent.toIntOrNull() ?: 100
                    viewModel.sendStopLossOrder(symbol, price, percent, "crypto")
                    Toast.makeText(context, "자동 손절 설정 완료", Toast.LENGTH_SHORT).show()
                    showStopLossDialog = null
                }) {
                    Text("설정 완료")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopLossDialog = null }) { Text("취소") }
            }
        )
    }

    val watchSymbolsList = settings.watch_symbols.entries.sortedBy { it.value.order }.map { it.key }

    var showSurgeDialog by remember { mutableStateOf(false) }

    if (showSurgeDialog) {
        val surge = settings.surge_trading
        var surgeEnabled by remember { mutableStateOf(surge.enabled) }
        
        val timeWindows = remember { mutableStateMapOf<String, String>() }
        val surgePercents = remember { mutableStateMapOf<String, String>() }
        val buyQuantities = remember { mutableStateMapOf<String, String>() }
        val symConfigs = remember { mutableStateMapOf<String, List<String>>() }
        
        LaunchedEffect(surge, watchSymbolsList) {
            watchSymbolsList.forEach { sym ->
                if (!symConfigs.containsKey(sym)) {
                    val cfgs = surge.per_symbol_config[sym] ?: emptyList()
                    val ids = mutableListOf<String>()
                    if (cfgs.isEmpty()) {
                        val id = java.util.UUID.randomUUID().toString()
                        ids.add(id)
                        timeWindows[id] = "60"
                        surgePercents[id] = "3.0"
                        buyQuantities[id] = ""
                    } else {
                        cfgs.forEach { cfg ->
                            val id = java.util.UUID.randomUUID().toString()
                            ids.add(id)
                            timeWindows[id] = cfg.time_window_seconds.toString()
                            surgePercents[id] = cfg.surge_percent_threshold.toString()
                            buyQuantities[id] = if (cfg.buy_quantity == 0.0) "" else cfg.buy_quantity.toString()
                        }
                    }
                    symConfigs[sym] = ids
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showSurgeDialog = false },
            title = { Text("급등 코인 감시 설정 (${if (settings.surge_trading.enabled) "ON" else "OFF"})") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("기능 전체 활성화", fontWeight = FontWeight.Bold)
                        Switch(checked = surgeEnabled, onCheckedChange = { surgeEnabled = it })
                    }
                    
                    if (watchSymbolsList.isEmpty()) {
                        Text("관심 코인이 없습니다.\n먼저 관심 코인을 추가하고 저장해주세요.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    } else {
                        var expandedSymbol by remember { mutableStateOf<String?>(null) }
                        
                        watchSymbolsList.forEach { sym ->
                            val name = status.stock_names[sym] ?: sym
                            val isExpanded = expandedSymbol == sym
                            
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedSymbol = if (isExpanded) null else sym
                                },
                                elevation = CardDefaults.cardElevation(defaultElevation = if (isExpanded) 4.dp else 1.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            val currentPrice = status.stock_prices[sym] ?: 0.0
                                            if (currentPrice > 0) {
                                                val priceStr = "₩${String.format(java.util.Locale.US, "%,.0f", currentPrice)}"
                                                Text(priceStr, fontSize = 14.sp, color = Color.DarkGray)
                                            } else {
                                                Text("로딩중...", fontSize = 14.sp, color = Color.Gray)
                                            }
                                        }
                                        Text(if (isExpanded) "▼" else "▶", color = Color.Gray, fontSize = 12.sp)
                                    }
                                    
                                    if (isExpanded) {
                                        symConfigs[sym]?.forEach { id ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = timeWindows[id] ?: "60",
                                                    onValueChange = { timeWindows[id] = it },
                                                    label = { Text("감시 (초)") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = surgePercents[id] ?: "3.0",
                                                    onValueChange = { surgePercents[id] = it },
                                                    label = { Text("급등 (%)") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                                                OutlinedTextField(
                                                    value = buyQuantities[id] ?: "0",
                                                    onValueChange = { buyQuantities[id] = it },
                                                    label = { Text("매수 수량 (0은 제외)") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(onClick = { 
                                                    val ids = symConfigs[sym]?.toMutableList() ?: mutableListOf()
                                                    ids.remove(id)
                                                    symConfigs[sym] = ids
                                                    timeWindows.remove(id)
                                                    surgePercents.remove(id)
                                                    buyQuantities.remove(id)
                                                }) { Text("❌") }
                                            }
                                        }
                                        TextButton(onClick = {
                                            val id = java.util.UUID.randomUUID().toString()
                                            val ids = symConfigs[sym]?.toMutableList() ?: mutableListOf()
                                            ids.add(id)
                                            symConfigs[sym] = ids
                                            timeWindows[id] = "60"
                                            surgePercents[id] = "3.0"
                                            buyQuantities[id] = ""
                                        }) { Text("+ 설정 추가") }
                                    } else {
                                        val activeIds = symConfigs[sym]?.filter { (buyQuantities[it]?.toDoubleOrNull() ?: 0.0) > 0.0 } ?: emptyList()
                                        if (activeIds.isNotEmpty()) {
                                            if (surgeEnabled) {
                                                activeIds.forEach { id ->
                                                    Text("설정됨: ${timeWindows[id]}초 / ${surgePercents[id]}% / ${buyQuantities[id]}개", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                                                }
                                            } else {
                                                activeIds.forEach { id ->
                                                    Text("대기중 (OFF): ${timeWindows[id]}초 / ${surgePercents[id]}% / ${buyQuantities[id]}개", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                                }
                                            }
                                        } else {
                                            Text("감시 제외 (수량 0)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val isAllValid = watchSymbolsList.all { sym ->
                    val ids = symConfigs[sym] ?: emptyList()
                    if (ids.isEmpty()) true
                    else ids.all { id ->
                        val qStr = buyQuantities[id]
                        if (qStr.isNullOrBlank()) true
                        else (qStr.toDoubleOrNull() ?: 0.0) > 0.0
                    }
                }
                Button(
                    enabled = isAllValid,
                    onClick = {
                    val perSymbolMap = mutableMapOf<String, List<SymbolSurgeConfig>>()
                    watchSymbolsList.forEach { sym ->
                        val list = mutableListOf<SymbolSurgeConfig>()
                        symConfigs[sym]?.forEach { id ->
                            val qty = buyQuantities[id]?.toDoubleOrNull() ?: 0.0
                            if (qty > 0.0 || symConfigs[sym]?.size == 1) {
                                list.add(SymbolSurgeConfig(
                                    time_window_seconds = timeWindows[id]?.toIntOrNull() ?: 60,
                                    surge_percent_threshold = surgePercents[id]?.toFloatOrNull() ?: 3.0f,
                                    buy_quantity = qty
                                ))
                            }
                        }
                        if (list.isNotEmpty()) perSymbolMap[sym] = list
                    }
                    val config = SurgeTradingConfig(
                        enabled = surgeEnabled,
                        allow_extended_hours = true,
                        per_symbol_config = perSymbolMap
                    )
                    viewModel.updateSurgeTradingSettings(config, "crypto")
                    Toast.makeText(context, "급등 코인 감시 설정 완료", Toast.LENGTH_SHORT).show()
                    showSurgeDialog = false
                }) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSurgeDialog = false }) { Text("취소") }
            }
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .mouseScroll(listState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Settings button row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onSettingsClick) {
                    Text("⚙ 계좌 설정")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "[${brokerName}]", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                    
                    Text(text = "총 평가자산", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "₩${NumberFormat.getNumberInstance(Locale.US).format(status.total_balance)}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "수익률: ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${String.format(Locale.US, "%.2f", status.current_yield)}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (status.current_yield >= 0) Color(0xFFD32F2F) else Color(0xFF1976D2)
                        )
                    }

                    var lastKnownTime by remember { mutableStateOf("") }
                    if (status.last_updated.isNotBlank()) {
                        lastKnownTime = status.last_updated.replace(Regex("\\.(\\d)\\d+"), ".$1")
                    }
                    
                    Text(
                        text = if (lastKnownTime.isNotEmpty()) "최종 갱신: $lastKnownTime" else " ",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHoldings = !showHoldings }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("보유 코인 현황", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (showHoldings) "▲" else "▼", color = Color.Gray)
            }
        }

        if (showHoldings) {
            val visibleHoldings = status.holding_stocks.filter { it.value.current_price > 0.0 && (it.value.current_price * it.value.quantity) > 100.0 }
            if (visibleHoldings.isEmpty()) {
                item {
                    Text("보유 중인 코인이 없습니다.", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                }
            } else {
                items(visibleHoldings.toList()) { (symbol, stock) ->
                    HoldingStockItem(
                        symbol = symbol, 
                        stock = stock, 
                        isOverseas = false, 
                        isCrypto = true, 
                        onStopLossClick = { showStopLossDialog = symbol },
                        onBuyClick = { showOrderDialog = symbol to "BUY" },
                        onSellClick = { showOrderDialog = symbol to "SELL" }
                    )
                }
            }
        }

        item {
            Button(
                onClick = { showWatchlistDialog = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text("관심 종목 관리")
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showWatchList = !showWatchList }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("실시간 시세 및 주문", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (showWatchList) "▲" else "▼", color = Color.Gray)
            }
        }

        if (showWatchList) {
            if (watchSymbolsList.isEmpty()) {
                item {
                    Text("관심 코인이 없습니다.", color = Color.Gray)
                }
            } else {
                items(watchSymbolsList) { symbol ->
                    val price = status.stock_prices[symbol] ?: 0.0
                    StockItem(
                        symbol = symbol,
                        name = status.stock_names[symbol] ?: symbol,
                        price = price,
                        isOverseas = false,
                        onBuyClick = { showOrderDialog = symbol to "BUY" }
                    )
                }
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "자동매매 엔진 가동", fontWeight = FontWeight.Bold)
                Switch(
                    checked = settings.is_auto_trading,
                    onCheckedChange = { viewModel.toggleAutoTrading(it, "crypto") }
                )
            }
            if (settings.is_auto_trading) {
                OutlinedButton(
                    onClick = { showSurgeDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("급등 코인 감시 설정 (${if (settings.surge_trading.enabled) "ON" else "OFF"})")
                }
                
                if (settings.surge_trading.enabled) {
                    val activeSurge = settings.surge_trading.per_symbol_config.filter { it.value.any { cfg -> cfg.buy_quantity > 0 } }
                    if (activeSurge.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { isSurgeStatusExpanded = !isSurgeStatusExpanded }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("급등 코인 감시 현황", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(if (isSurgeStatusExpanded) "접기 ▲" else "펼치기 ▼", fontSize = 12.sp, color = Color.Gray)
                        }
                        if (isSurgeStatusExpanded) {
                            activeSurge.forEach { (sym, cfgs) ->
                                val name = status.stock_names[sym] ?: status.holding_stocks[sym]?.name?.ifEmpty { null } ?: sym
                                cfgs.filter { it.buy_quantity > 0 }.forEach { cfg ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(name)
                                        Text("${cfg.time_window_seconds}초 / ${cfg.surge_percent_threshold}% / ${cfg.buy_quantity}개", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ==================== SHARED COMPONENTS ====================

/**
 * Format crypto quantity: up to 8 decimal places, trim trailing zeros.
 */
fun formatCryptoQuantity(qty: Double): String {
    val formatted = String.format(Locale.US, "%.8f", qty)
    return formatted.trimEnd('0').trimEnd('.')
}

@Composable
fun HoldingStockItem(
    symbol: String, 
    stock: HoldingStock, 
    isOverseas: Boolean, 
    isCrypto: Boolean = false, 
    onStopLossClick: () -> Unit,
    onBuyClick: () -> Unit,
    onSellClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stock.name.ifEmpty { symbol }, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (isCrypto) {
                        Text(text = "${formatCryptoQuantity(stock.quantity)}개", style = MaterialTheme.typography.bodySmall)
                    } else {
                        val intQty = stock.quantity.toInt()
                        Text(text = "${intQty}주", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    val priceStr = if (isCrypto) {
                        "₩${String.format(Locale.US, "%,.0f", stock.current_price)}"
                    } else if (isOverseas) {
                        "$${String.format(Locale.US, "%.2f", stock.current_price)}"
                    } else {
                        "₩${String.format(Locale.US, "%,.0f", stock.current_price)}"
                    }
                    Text(text = priceStr, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${String.format(Locale.US, "%.2f", stock.yield)}%",
                        color = if (stock.yield >= 0) Color(0xFFD32F2F) else Color(0xFF1976D2),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onBuyClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)), modifier = Modifier.weight(1f)) {
                    Text("매수", color = Color.White)
                }
                Button(onClick = onSellClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)), modifier = Modifier.weight(1f)) {
                    Text("매도", color = Color.White)
                }
                OutlinedButton(onClick = onStopLossClick, modifier = Modifier.weight(1f)) {
                    Text("손절")
                }
            }
        }
    }
}

@Composable
fun StockItem(
    symbol: String,
    name: String,
    price: Double,
    isOverseas: Boolean,
    onBuyClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                val priceStr = if (price > 0) {
                    if (isOverseas) "$${String.format(Locale.US, "%.2f", price)}" else "₩${String.format(Locale.US, "%,.0f", price)}"
                } else {
                    "로딩중..."
                }
                Text(text = priceStr, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = onBuyClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text("매수", color = Color.White)
            }
        }
    }
}

fun Modifier.mouseScroll(state: LazyListState): Modifier = composed {
    val coroutineScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    this.pointerInput(Unit) {
        val scrollAmount = with(density) { 64.dp.toPx() }
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll) {
                    val delta = event.changes.first().scrollDelta.y
                    if (delta != 0f) {
                        event.changes.forEach { it.consume() }
                        coroutineScope.launch {
                            state.scrollBy(delta * scrollAmount)
                        }
                    }
                }
            }
        }
    }
}
