package com.my.autotradingapp.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.my.autotradingapp.data.*
import com.my.autotradingapp.utils.CryptoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

class TradingViewModel : ViewModel() {

    private val dbUrl = "https://my-auto-trading-9deb3-default-rtdb.asia-southeast1.firebasedatabase.app/"
    private val database = FirebaseDatabase.getInstance(dbUrl)
    
    // 기본값 설정
    private val _userId = MutableStateFlow("test_user")
    val userId = _userId.asStateFlow()

    // Stock state flows
    private val _stockStatus = MutableStateFlow(TradingStatus())
    val stockStatus = _stockStatus.asStateFlow()

    private val _stockSettings = MutableStateFlow(TradingSettings())
    val stockSettings = _stockSettings.asStateFlow()

    private val _laoreSettings = MutableStateFlow<Map<String, LaoreSettings>>(emptyMap())
    val laoreSettings = _laoreSettings.asStateFlow()

    private val _laoreStatus = MutableStateFlow<Map<String, LaoreStatus>>(emptyMap())
    val laoreStatus = _laoreStatus.asStateFlow()

    private val _stockAccountData = MutableStateFlow(UserAccountData())
    val stockAccountData = _stockAccountData.asStateFlow()

    // Crypto state flows
    private val _cryptoStatus = MutableStateFlow(TradingStatus())
    val cryptoStatus = _cryptoStatus.asStateFlow()

    private val _cryptoSettings = MutableStateFlow(TradingSettings())
    val cryptoSettings = _cryptoSettings.asStateFlow()

    private val _cryptoAccountData = MutableStateFlow(UserAccountData())
    val cryptoAccountData = _cryptoAccountData.asStateFlow()

    private val _manualOrderStatus = MutableStateFlow<ManualOrder?>(null)
    val manualOrderStatus = _manualOrderStatus.asStateFlow()

    // Market data for autocomplete
    private val _domesticStocks = MutableStateFlow<Map<String, String>>(emptyMap())
    val domesticStocks = _domesticStocks.asStateFlow()

    private val _upbitCoins = MutableStateFlow<Map<String, String>>(emptyMap())
    val upbitCoins = _upbitCoins.asStateFlow()

    private val _overseasStocks = MutableStateFlow<Map<String, String>>(emptyMap())
    val overseasStocks = _overseasStocks.asStateFlow()

    // Listeners for stock
    private var stockStatusListener: ValueEventListener? = null
    private var stockSettingsListener: ValueEventListener? = null
    private var stockAccountListener: ValueEventListener? = null
    private var stockOrderListener: ValueEventListener? = null

    // Listeners for crypto
    private var cryptoStatusListener: ValueEventListener? = null
    private var cryptoSettingsListener: ValueEventListener? = null
    private var cryptoAccountListener: ValueEventListener? = null
    private var cryptoOrderListener: ValueEventListener? = null
    private var laoreSettingsListener: ValueEventListener? = null
    private var laoreStatusListener: ValueEventListener? = null

    private var stockActiveAccountId = ""
    private var cryptoActiveAccountId = ""

    private var stockStatusRef: com.google.firebase.database.DatabaseReference? = null
    private var stockSettingsRef: com.google.firebase.database.DatabaseReference? = null
    private var stockOrderRef: com.google.firebase.database.DatabaseReference? = null
    private var laoreSettingsRef: com.google.firebase.database.DatabaseReference? = null
    private var laoreStatusRef: com.google.firebase.database.DatabaseReference? = null

    private var cryptoStatusRef: com.google.firebase.database.DatabaseReference? = null
    private var cryptoSettingsRef: com.google.firebase.database.DatabaseReference? = null
    private var cryptoOrderRef: com.google.firebase.database.DatabaseReference? = null

    init {
        startListening(_userId.value)
        loadMarketData()
    }

    private fun getUserPath(id: String): String {
        val cleanId = id.trim()
        return "users/$cleanId"
    }

    private fun getAccountPath(id: String, serviceType: String): String {
        val servicePath = getServicePath(id, serviceType)
        val activeId = if (serviceType == "crypto") cryptoActiveAccountId else stockActiveAccountId
        return if (activeId.isNotEmpty()) "$servicePath/accounts/$activeId" else servicePath
    }

    private fun getServicePath(id: String, serviceType: String): String {
        val cleanId = id.trim()
        return "users/$cleanId/$serviceType"
    }

    fun setUserId(newId: String) {
        val trimmedId = newId.trim()
        if (trimmedId.isEmpty()) return
        if (_userId.value == trimmedId) return
        
        Log.d("TradingApp", "Switching User ID to: $trimmedId")
        stopListening()
        _userId.value = trimmedId
        startListening(trimmedId)
    }

    private fun startListening(id: String) {
        val stockPath = getServicePath(id, "stock")
        val cryptoPath = getServicePath(id, "crypto")
        Log.d("TradingApp", "Start listening to paths: $stockPath, $cryptoPath")
        
        stockAccountListener = database.getReference("$stockPath/account_data").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newActiveId = snapshot.child("active_account_id").value as? String ?: ""
                val changed = stockActiveAccountId != newActiveId
                
                _stockAccountData.value = parseAccountData(snapshot, stockPath)
                
                if (changed) {
                    stockActiveAccountId = newActiveId
                    restartStockAccountSpecificListeners(stockPath, newActiveId)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("TradingApp", "Stock Account Listener Cancelled: ${error.message}")
            }
        })

        cryptoAccountListener = database.getReference("$cryptoPath/account_data").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newActiveId = snapshot.child("active_account_id").value as? String ?: ""
                val changed = cryptoActiveAccountId != newActiveId
                
                _cryptoAccountData.value = parseAccountData(snapshot, cryptoPath)
                
                if (changed) {
                    cryptoActiveAccountId = newActiveId
                    restartCryptoAccountSpecificListeners(cryptoPath, newActiveId)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("TradingApp", "Crypto Account Listener Cancelled: ${error.message}")
            }
        })
    }

    private fun restartStockAccountSpecificListeners(servicePath: String, activeId: String) {
        val accountPath = if (activeId.isNotEmpty()) "$servicePath/accounts/$activeId" else servicePath
        Log.d("TradingApp", "Restarting Stock listeners on $accountPath")
        
        stockStatusListener?.let { stockStatusRef?.removeEventListener(it) }
        stockSettingsListener?.let { stockSettingsRef?.removeEventListener(it) }
        stockOrderListener?.let { stockOrderRef?.removeEventListener(it) }
        laoreSettingsListener?.let { laoreSettingsRef?.removeEventListener(it) }
        laoreStatusListener?.let { laoreStatusRef?.removeEventListener(it) }

        stockStatusRef = database.getReference("$accountPath/status")
        stockSettingsRef = database.getReference("$accountPath/settings")
        stockOrderRef = database.getReference("$accountPath/manual_orders")
        laoreSettingsRef = database.getReference("$accountPath/laore_settings")
        laoreStatusRef = database.getReference("$accountPath/laore_status")

        if (stockStatusListener == null) {
            stockStatusListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _stockStatus.value = parseStatus(snapshot, accountPath)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
        }
        if (stockSettingsListener == null) {
            stockSettingsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _stockSettings.value = parseSettings(snapshot)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
        }
        if (stockOrderListener == null) {
            stockOrderListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    parseOrderStatus(snapshot)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
        }
        if (laoreSettingsListener == null) {
            laoreSettingsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _laoreSettings.value = parseLaoreSettings(snapshot)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
        }
        if (laoreStatusListener == null) {
            laoreStatusListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _laoreStatus.value = parseLaoreStatus(snapshot)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
        }

        stockStatusRef?.addValueEventListener(stockStatusListener!!)
        stockSettingsRef?.addValueEventListener(stockSettingsListener!!)
        stockOrderRef?.addValueEventListener(stockOrderListener!!)
        laoreSettingsRef?.addValueEventListener(laoreSettingsListener!!)
        laoreStatusRef?.addValueEventListener(laoreStatusListener!!)
    }

    private fun restartCryptoAccountSpecificListeners(servicePath: String, activeId: String) {
        val accountPath = if (activeId.isNotEmpty()) "$servicePath/accounts/$activeId" else servicePath
        Log.d("TradingApp", "Restarting Crypto listeners on $accountPath")
        
        cryptoStatusListener?.let { cryptoStatusRef?.removeEventListener(it) }
        cryptoSettingsListener?.let { cryptoSettingsRef?.removeEventListener(it) }
        cryptoOrderListener?.let { cryptoOrderRef?.removeEventListener(it) }

        cryptoStatusRef = database.getReference("$accountPath/status")
        cryptoSettingsRef = database.getReference("$accountPath/settings")
        cryptoOrderRef = database.getReference("$accountPath/manual_orders")

        if (cryptoStatusListener == null) {
            cryptoStatusListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _cryptoStatus.value = parseStatus(snapshot, accountPath)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
        }
        if (cryptoSettingsListener == null) {
            cryptoSettingsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _cryptoSettings.value = parseSettings(snapshot)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
        }
        if (cryptoOrderListener == null) {
            cryptoOrderListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    parseOrderStatus(snapshot)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
        }

        cryptoStatusRef?.addValueEventListener(cryptoStatusListener!!)
        cryptoSettingsRef?.addValueEventListener(cryptoSettingsListener!!)
        cryptoOrderRef?.addValueEventListener(cryptoOrderListener!!)
    }

    // ============ PARSING HELPERS ============
    private fun parseAccountData(snapshot: DataSnapshot, path: String): UserAccountData {
        try {
            if (!snapshot.exists()) {
                Log.w("TradingApp", "Account data does not exist at $path")
                return UserAccountData()
            }
            val activeAccountId = snapshot.child("active_account_id").value as? String ?: ""
            val pinCode = snapshot.child("pin").value as? String ?: ""
            
            val savedAccounts = mutableMapOf<String, AccountInfo>()
            snapshot.child("saved_accounts").children.forEach { child ->
                val key = child.key ?: return@forEach
                val broker = child.child("broker").value as? String ?: ""
                val isMock = (child.child("_mock").value ?: child.child("is_mock").value) as? Boolean ?: true
                val isOverseas = (child.child("_overseas").value ?: child.child("is_overseas").value) as? Boolean ?: true
                val accNum = child.child("account_number").value as? String ?: ""
                val rawAppKey = child.child("app_key").value as? String ?: ""
                val appKey = CryptoUtils.decrypt(rawAppKey)
                val rawAppSecret = child.child("app_secret").value as? String ?: ""
                val appSecret = CryptoUtils.decrypt(rawAppSecret)
                val lastUsed = child.child("last_used_timestamp").value as? String ?: ""
                
                savedAccounts[key] = AccountInfo(broker, isMock, isOverseas, accNum, appKey, appSecret, lastUsed)
            }
            
            Log.d("TradingApp", "Account data loaded: ${savedAccounts.size} accounts from $path")
            return UserAccountData(activeAccountId, pinCode, savedAccounts)
        } catch (e: Exception) {
            Log.e("TradingApp", "Error parsing account data: ${e.message}")
            return UserAccountData()
        }
    }

    private fun parseStatus(snapshot: DataSnapshot, path: String): TradingStatus {
        try {
            if (!snapshot.exists()) {
                return TradingStatus()
            }
            val totalBalance = (snapshot.child("total_balance").value as? Number)?.toDouble() ?: 0.0
            val currentYield = (snapshot.child("current_yield").value as? Number)?.toFloat() ?: 0.0f
            val lastUpdated = snapshot.child("last_updated").value as? String ?: ""
            
            val stockPrices = mutableMapOf<String, Double>()
            snapshot.child("stock_prices").children.forEach { child ->
                val key = child.key ?: return@forEach
                val value = (child.value as? Number)?.toDouble() ?: 0.0
                stockPrices[key] = value
            }

            val stockNames = mutableMapOf<String, String>()
            snapshot.child("stock_names").children.forEach { child ->
                val key = child.key ?: return@forEach
                val value = child.value as? String ?: ""
                // Filter out corrupted keys inserted by old versions (e.g. KRW-KRW-BTC or Korean characters)
                if (key.contains("KRW-KRW-") || key.matches(Regex(".*[가-힣]+.*"))) {
                    return@forEach
                }
                stockNames[key] = value
            }

            val holdingStocks = mutableMapOf<String, HoldingStock>()
            val holdingsNode = snapshot.child("holdings")
            holdingsNode.children.forEach { child ->
                val symbol = child.child("symbol").value as? String ?: return@forEach
                val qty = (child.child("quantity").value as? Number)?.toDouble() ?: 0.0
                val avgPrice = (child.child("purchase_avg_price").value as? Number)?.toDouble() ?: 0.0
                val curPrice = (child.child("current_price").value as? Number)?.toDouble() ?: 0.0
                val plAmount = (child.child("profit_loss_amount").value as? Number)?.toDouble() ?: 0.0
                val yieldRate = (child.child("profit_loss_rate").value as? Number)?.toFloat() ?: 0.0f
                val name = child.child("name").value as? String ?: ""
                holdingStocks[symbol] = HoldingStock(symbol, qty, avgPrice, curPrice, plAmount, yieldRate, name)
            }
            val authError = snapshot.child("auth_error").value as? String ?: ""

            Log.d("TradingApp", "Status Updated at $path")
            return TradingStatus(totalBalance, currentYield, lastUpdated, stockPrices, stockNames, holdingStocks, authError)
        } catch (e: Exception) {
            Log.e("TradingApp", "Error parsing status: ${e.message}")
            return TradingStatus()
        }
    }

        private fun parseLaoreSettings(snapshot: DataSnapshot): Map<String, LaoreSettings> {
        if (!snapshot.exists()) return emptyMap()
        val result = mutableMapOf<String, LaoreSettings>()
        for (child in snapshot.children) {
            val key = child.key ?: continue
            result[key] = LaoreSettings(
                enabled = child.child("enabled").value as? Boolean ?: false,
                total_budget = (child.child("total_budget").value as? Number)?.toDouble() ?: 0.0,
                split_count = (child.child("split_count").value as? Number)?.toInt() ?: 40,
                target_yield_percent = (child.child("target_yield_percent").value as? Number)?.toFloat() ?: 10.0f
            )
        }
        return result
    }

        private fun parseLaoreStatus(snapshot: DataSnapshot): Map<String, LaoreStatus> {
        if (!snapshot.exists()) return emptyMap()
        val result = mutableMapOf<String, LaoreStatus>()
        for (child in snapshot.children) {
            val key = child.key ?: continue
            result[key] = LaoreStatus(
                current_round = (child.child("current_round").value as? Number)?.toInt() ?: 0,
                holding_quantity = (child.child("holding_quantity").value as? Number)?.toDouble() ?: 0.0,
                average_price = (child.child("average_price").value as? Number)?.toDouble() ?: 0.0,
                status_message = child.child("status_message").value as? String ?: "",
                current_mode = child.child("current_mode").value as? String ?: "일반 모드(전반전)",
                t_value = (child.child("t_value").value as? Number)?.toDouble() ?: 0.0,
                target_sell_price = (child.child("target_sell_price").value as? Number)?.toDouble() ?: 0.0,
                remaining_cash = (child.child("remaining_cash").value as? Number)?.toDouble() ?: 0.0
            )
        }
        return result
    }

    fun updateStockName(symbol: String, name: String, serviceType: String = "stock") {
        var sym = symbol.uppercase()
        if (serviceType == "crypto" && !sym.contains("-")) {
            sym = "KRW-$sym"
        }
        val path = "${getAccountPath(_userId.value, serviceType)}/status/stock_names"
        database.getReference(path).child(sym).setValue(name)
    }
    fun saveLaoreSetting(compositeKey: String, settings: LaoreSettings) {
        val id = _userId.value.trim()
        if (id.isEmpty()) return
        database.getReference("${getAccountPath(id, "stock")}/laore_settings/$compositeKey").setValue(settings)
    }
    
    fun removeLaoreSetting(compositeKey: String) {
        val id = _userId.value.trim()
        if (id.isEmpty()) return
        database.getReference("${getAccountPath(id, "stock")}/laore_settings/$compositeKey/enabled").setValue(false)
    }

    private fun parseSettings(snapshot: DataSnapshot): TradingSettings {
        if (!snapshot.exists()) {
            return TradingSettings()
        }
        val isAutoTrading = snapshot.child("is_auto_trading").value as? Boolean ?: false
        val memo = snapshot.child("memo").value as? String ?: ""
        
        val watchSymbolsRaw = mutableMapOf<String, SymbolSettings>()
        snapshot.child("watch_symbols").children.forEach { child ->
            val key = child.key ?: return@forEach
            val enabled = child.child("enabled").value as? Boolean ?: true
            val order = (child.child("order").value as? Long)?.toInt() ?: 0
            watchSymbolsRaw[key] = SymbolSettings(enabled, order)
        }
        val watchSymbols = watchSymbolsRaw.entries.sortedBy { it.value.order }.associate { it.key to it.value }
        
        val surgeNode = snapshot.child("surge_trading")
        val surgeEnabled = surgeNode.child("enabled").value as? Boolean ?: false
        
        val perSymbolConfig = mutableMapOf<String, List<SymbolSurgeConfig>>()
        surgeNode.child("per_symbol_config").children.forEach { child ->
            val key = child.key ?: return@forEach
            val list = mutableListOf<SymbolSurgeConfig>()
            
            // Check if this child has numbered children (array) or is a single object
            if (child.hasChildren() && child.children.firstOrNull()?.key?.toIntOrNull() != null) {
                child.children.forEach { cfgChild ->
                    val timeWindow = (cfgChild.child("time_window_seconds").value as? Number)?.toInt() ?: 60
                    val threshold = (cfgChild.child("surge_percent_threshold").value as? Number)?.toFloat() ?: 3.0f
                    val qty = (cfgChild.child("buy_quantity").value as? Number)?.toDouble() ?: 0.0
                    list.add(SymbolSurgeConfig(timeWindow, threshold, qty))
                }
            } else {
                // Fallback for old single-object structure
                val timeWindow = (child.child("time_window_seconds").value as? Number)?.toInt() ?: 60
                val threshold = (child.child("surge_percent_threshold").value as? Number)?.toFloat() ?: 3.0f
                val qty = (child.child("buy_quantity").value as? Number)?.toDouble() ?: 0.0
                list.add(SymbolSurgeConfig(timeWindow, threshold, qty))
            }
            perSymbolConfig[key] = list
        }
        
        val surgeExt = surgeNode.child("allow_extended_hours").value as? Boolean ?: true
        val surgeTrading = SurgeTradingConfig(surgeEnabled, surgeExt, perSymbolConfig)
        
        return TradingSettings(isAutoTrading, watchSymbols, surgeTrading, memo)
    }

    private fun parseOrderStatus(snapshot: DataSnapshot) {
        try {
            val status = snapshot.child("status").value as? String ?: return
            if (status == "FAILED" || status == "SUCCESS") {
                val cmd = snapshot.child("command").value as? String ?: "NONE"
                val err = snapshot.child("error_message").value as? String ?: ""
                val order = ManualOrder(cmd, "", 0.0, 0.0, "", status, err)
                _manualOrderStatus.value = order
            }
        } catch(e: Exception) {}
    }

    fun updatePin(pin: String, serviceType: String) {
        val id = _userId.value.trim()
        if (id.isEmpty()) return
        val servicePath = getServicePath(id, serviceType)
        database.getReference("$servicePath/account_data/pin").setValue(CryptoUtils.encrypt(pin))
    }

    private fun stopListening() {
        val oldId = _userId.value
        val stockPath = getServicePath(oldId, "stock")
        val cryptoPath = getServicePath(oldId, "crypto")

        stockStatusListener?.let { stockStatusRef?.removeEventListener(it) }
        stockSettingsListener?.let { stockSettingsRef?.removeEventListener(it) }
        stockAccountListener?.let { database.getReference("$stockPath/account_data").removeEventListener(it) }
        stockOrderListener?.let { stockOrderRef?.removeEventListener(it) }
        laoreSettingsListener?.let { laoreSettingsRef?.removeEventListener(it) }
        laoreStatusListener?.let { laoreStatusRef?.removeEventListener(it) }

        cryptoStatusListener?.let { cryptoStatusRef?.removeEventListener(it) }
        cryptoSettingsListener?.let { cryptoSettingsRef?.removeEventListener(it) }
        cryptoAccountListener?.let { database.getReference("$cryptoPath/account_data").removeEventListener(it) }
        cryptoOrderListener?.let { cryptoOrderRef?.removeEventListener(it) }
        
        stockActiveAccountId = ""
        cryptoActiveAccountId = ""
    }

    fun saveAccountSecurely(info: AccountInfo, newPinToSet: String, currentlyVerifiedPin: String, serviceType: String, onComplete: (Boolean, String) -> Unit) {
        val id = _userId.value.trim()
        if (id.isEmpty()) {
            onComplete(false, "User ID is empty")
            return
        }
        
        val servicePath = getServicePath(id, serviceType)
        val accRef = database.getReference("$servicePath/account_data")
        
        // 서버에서 현재 PIN을 가져와 이중 검증
        accRef.child("pin").get().addOnSuccessListener { snapshot ->
            val serverPin = snapshot.value as? String ?: ""
            
            val decryptedServerPin = if (serverPin.isNotEmpty()) {
                try { CryptoUtils.decrypt(serverPin) } catch (e: Exception) { serverPin }
            } else { "" }
            
            val decryptedCurrentlyVerifiedPin = if (currentlyVerifiedPin.isNotEmpty()) {
                try { CryptoUtils.decrypt(currentlyVerifiedPin) } catch (e: Exception) { currentlyVerifiedPin }
            } else { "" }
            
            if (decryptedServerPin.isNotEmpty() && decryptedServerPin != decryptedCurrentlyVerifiedPin) {
                onComplete(false, "보안 차단: 이미 다른 분이 PIN을 설정한 계정입니다.")
                return@addOnSuccessListener
            }
            
            // 검증 통과 - 저장 진행
            val key = if (serviceType == "crypto") {
                "${info.broker}_REAL_CRYPTO"
            } else {
                "${info.broker}_${if (info.is_mock) "MOCK" else "REAL"}_${if (info.is_overseas) "OS" else "KR"}"
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            val accountMap = mutableMapOf<String, Any>(
                "broker" to info.broker,
                "is_mock" to info.is_mock,
                "is_overseas" to info.is_overseas,
                "app_key" to CryptoUtils.encrypt(info.app_key),
                "app_secret" to CryptoUtils.encrypt(info.app_secret),
                "last_used_timestamp" to timestamp
            )
            if (serviceType != "crypto") {
                accountMap["account_number"] = info.account_number
            }
            
            accRef.child("saved_accounts").child(key).setValue(accountMap)
                .addOnSuccessListener { 
                    accRef.child("active_account_id").setValue(key)
                    if (newPinToSet.isNotEmpty()) {
                        accRef.child("pin").setValue(CryptoUtils.encrypt(newPinToSet))
                    }
                    database.getReference("$servicePath/status").removeValue()
                    if (serviceType == "crypto") {
                        _cryptoStatus.value = TradingStatus()
                    } else {
                        _stockStatus.value = TradingStatus()
                    }
                    onComplete(true, "저장 완료")
                }
                .addOnFailureListener { onComplete(false, "저장 실패: ${it.message}") }
                
        }.addOnFailureListener {
            onComplete(false, "서버 연결 실패")
        }
    }

    fun resetManualOrderStatus() {
        _manualOrderStatus.value = null
    }

    fun clearManualOrderFirebaseStatus(serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        database.getReference("$servicePath/manual_orders/status").setValue("NONE")
        database.getReference("$servicePath/manual_orders/error_message").setValue("")
    }

    fun clearAuthErrorLocally(serviceType: String) {
        if (serviceType == "crypto") {
            _cryptoStatus.value = _cryptoStatus.value.copy(authError = "")
        } else {
            _stockStatus.value = _stockStatus.value.copy(authError = "")
        }
    }

    fun saveFcmToken(token: String) {
        val id = _userId.value
        if (id.isNotEmpty()) {
            database.reference.child("users").child(id).child("fcm_token").setValue(token)
        }
    }

    private fun loadMarketData() {
        database.reference.child("market_data").child("domestic_stocks")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val map = mutableMapOf<String, String>()
                    for (child in snapshot.children) {
                        val key = child.key
                        val value = child.getValue(String::class.java)
                        if (key != null && value != null) {
                            map[key] = value
                        }
                    }
                    _domesticStocks.value = map
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("TradingViewModel", "Failed to load domestic stocks", error.toException())
                }
            })

        database.reference.child("market_data").child("upbit_coins")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val map = mutableMapOf<String, String>()
                    for (child in snapshot.children) {
                        val key = child.key
                        val value = child.getValue(String::class.java)
                        if (key != null && value != null) {
                            map[key] = value
                        }
                    }
                    _upbitCoins.value = map
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("TradingViewModel", "Failed to load upbit coins", error.toException())
                }
            })

        database.reference.child("market_data").child("overseas_stocks")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val map = mutableMapOf<String, String>()
                    for (child in snapshot.children) {
                        val key = child.key
                        val value = child.getValue(String::class.java)
                        if (key != null && value != null) {
                            map[key] = value
                        }
                    }
                    _overseasStocks.value = map
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("TradingViewModel", "Failed to load overseas stocks", error.toException())
                }
            })
    }

    fun saveAccount(info: AccountInfo, serviceType: String) {
        val id = _userId.value
        val servicePath = getServicePath(id, serviceType)
        
        val key = if (serviceType == "crypto") {
            "${info.broker}_REAL_CRYPTO"
        } else {
            "${info.broker}_${if (info.is_mock) "MOCK" else "REAL"}_${if (info.is_overseas) "OS" else "KR"}"
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val accountMap = mutableMapOf<String, Any>(
            "broker" to info.broker,
            "is_mock" to info.is_mock,
            "is_overseas" to info.is_overseas,
            "app_key" to CryptoUtils.encrypt(info.app_key),
            "app_secret" to CryptoUtils.encrypt(info.app_secret),
            "last_used_timestamp" to timestamp
        )
        if (serviceType != "crypto") {
            accountMap["account_number"] = info.account_number
        }
        
        Log.d("TradingApp", "Saving account to: $servicePath/account_data/saved_accounts/$key")
        
        val accRef = database.getReference("$servicePath/account_data")
        accRef.child("saved_accounts").child(key).setValue(accountMap)
            .addOnSuccessListener { Log.d("TradingApp", "Account saved successfully") }
            .addOnFailureListener { Log.e("TradingApp", "Failed to save account: ${it.message}") }
            
        accRef.child("active_account_id").setValue(key)
        database.getReference("$servicePath/status").removeValue()
        if (serviceType == "crypto") {
            _cryptoStatus.value = TradingStatus()
        } else {
            _stockStatus.value = TradingStatus()
        }
    }

    fun toggleAutoTrading(enabled: Boolean, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        database.getReference("$servicePath/settings/is_auto_trading").setValue(enabled)
    }

    fun updateWatchSymbols(symbols: List<String>, serviceType: String, isOverseas: Boolean? = null) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        val symbolsMap = mutableMapOf<String, SymbolSettings>()
        var index = 0
        
        if (serviceType == "stock" && isOverseas != null) {
            val currentSymbols = _stockSettings.value.watch_symbols.keys
            val otherMarketSymbols = currentSymbols.filter { sym ->
                if (isOverseas) sym.all { it.isDigit() } else !sym.all { it.isDigit() }
            }
            otherMarketSymbols.forEach { sym ->
                symbolsMap[sym] = SymbolSettings(true, index++)
            }
        }

        val stockNamesPath = "$servicePath/status/stock_names"

        symbols.forEach { symbolStr -> 
            val str = symbolStr.trim()
            var sym = str
            var name = ""
            if (str.contains("(") && str.endsWith(")")) {
                name = str.substringBeforeLast("(").trim()
                sym = str.substringAfterLast("(").removeSuffix(")")
            }
            sym = sym.uppercase()
            if (serviceType == "crypto" && !sym.contains("-")) {
                sym = "KRW-$sym"
            }
            symbolsMap[sym] = SymbolSettings(true, index++)
            
            // 이름이 있는 경우 UI 지연을 없애기 위해 Firebase에 즉시 이름 저장
            if (name.isNotEmpty()) {
                database.getReference(stockNamesPath).child(sym).setValue(name)
            }
        }
        database.getReference("$servicePath/settings/watch_symbols").setValue(symbolsMap)
    }

    fun sendManualOrder(command: String, symbol: String, quantity: Double, price: Double, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val order = ManualOrder(command, symbol, quantity, price, timestamp)
        database.getReference("$servicePath/manual_orders").setValue(order)
    }

    fun sendConditionalOrder(symbol: String, side: String, quantity: Double, targetPrice: Double, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val orderId = java.util.UUID.randomUUID().toString()
        val order = ConditionalOrder(symbol, side, quantity, targetPrice, false, "PENDING", timestamp)
        database.getReference("$servicePath/conditional_orders/$orderId").setValue(order)
    }

    fun sendStopLossOrder(symbol: String, triggerPrice: Double, volumePercent: Int, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val orderId = java.util.UUID.randomUUID().toString()
        val order = StopLossOrder(symbol, triggerPrice, volumePercent, false, "PENDING", timestamp)
        database.getReference("$servicePath/stop_loss_orders/$orderId").setValue(order)
    }

    fun updateSurgeTradingSettings(config: SurgeTradingConfig, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        database.getReference("$servicePath/settings/surge_trading").setValue(config)
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}







