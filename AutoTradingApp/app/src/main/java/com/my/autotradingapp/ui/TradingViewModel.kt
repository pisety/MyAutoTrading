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

    init {
        startListening(_userId.value)
    }

    private fun getUserPath(id: String): String {
        val cleanId = id.trim()
        return "users/$cleanId"
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
        
        // ============ STOCK LISTENERS ============
        stockAccountListener = database.getReference("$stockPath/account_data").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _stockAccountData.value = parseAccountData(snapshot, stockPath)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("TradingApp", "Stock Account Listener Cancelled: ${error.message}")
            }
        })

        stockStatusListener = database.getReference("$stockPath/status").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _stockStatus.value = parseStatus(snapshot, stockPath)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        stockSettingsListener = database.getReference("$stockPath/settings").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _stockSettings.value = parseSettings(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        stockOrderListener = database.getReference("$stockPath/manual_orders").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                parseOrderStatus(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // ============ CRYPTO LISTENERS ============
        cryptoAccountListener = database.getReference("$cryptoPath/account_data").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _cryptoAccountData.value = parseAccountData(snapshot, cryptoPath)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("TradingApp", "Crypto Account Listener Cancelled: ${error.message}")
            }
        })

        cryptoStatusListener = database.getReference("$cryptoPath/status").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _cryptoStatus.value = parseStatus(snapshot, cryptoPath)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        cryptoSettingsListener = database.getReference("$cryptoPath/settings").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _cryptoSettings.value = parseSettings(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        cryptoOrderListener = database.getReference("$cryptoPath/manual_orders").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                parseOrderStatus(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
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

    private fun parseSettings(snapshot: DataSnapshot): TradingSettings {
        if (!snapshot.exists()) {
            return TradingSettings()
        }
        val isAutoTrading = snapshot.child("is_auto_trading").value as? Boolean ?: false
        val memo = snapshot.child("memo").value as? String ?: ""
        
        val watchSymbols = mutableMapOf<String, SymbolSettings>()
        snapshot.child("watch_symbols").children.forEach { child ->
            val key = child.key ?: return@forEach
            val enabled = child.child("enabled").value as? Boolean ?: true
            watchSymbols[key] = SymbolSettings(enabled)
        }
        
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

        stockStatusListener?.let { database.getReference("$stockPath/status").removeEventListener(it) }
        stockSettingsListener?.let { database.getReference("$stockPath/settings").removeEventListener(it) }
        stockAccountListener?.let { database.getReference("$stockPath/account_data").removeEventListener(it) }
        stockOrderListener?.let { database.getReference("$stockPath/manual_orders").removeEventListener(it) }

        cryptoStatusListener?.let { database.getReference("$cryptoPath/status").removeEventListener(it) }
        cryptoSettingsListener?.let { database.getReference("$cryptoPath/settings").removeEventListener(it) }
        cryptoAccountListener?.let { database.getReference("$cryptoPath/account_data").removeEventListener(it) }
        cryptoOrderListener?.let { database.getReference("$cryptoPath/manual_orders").removeEventListener(it) }
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
        val servicePath = getServicePath(_userId.value, serviceType)
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
        val servicePath = getServicePath(_userId.value, serviceType)
        database.getReference("$servicePath/settings/is_auto_trading").setValue(enabled)
    }

    fun updateWatchSymbols(symbolsStr: String, serviceType: String) {
        val servicePath = getServicePath(_userId.value, serviceType)
        val symbolsMap = symbolsStr.split(",").filter { it.isNotBlank() }.associate { 
            var sym = it.trim().uppercase()
            if (serviceType == "crypto" && !sym.contains("-")) {
                sym = "KRW-$sym"
            }
            sym to SymbolSettings(true)
        }
        database.getReference("$servicePath/settings/watch_symbols").setValue(symbolsMap)
    }

    fun sendManualOrder(command: String, symbol: String, quantity: Double, price: Double, serviceType: String) {
        val servicePath = getServicePath(_userId.value, serviceType)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val order = ManualOrder(command, symbol, quantity, price, timestamp)
        database.getReference("$servicePath/manual_orders").setValue(order)
    }

    fun sendConditionalOrder(symbol: String, side: String, quantity: Double, targetPrice: Double, serviceType: String) {
        val servicePath = getServicePath(_userId.value, serviceType)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val orderId = java.util.UUID.randomUUID().toString()
        val order = ConditionalOrder(symbol, side, quantity, targetPrice, false, "PENDING", timestamp)
        database.getReference("$servicePath/conditional_orders/$orderId").setValue(order)
    }

    fun sendStopLossOrder(symbol: String, triggerPrice: Double, volumePercent: Int, serviceType: String) {
        val servicePath = getServicePath(_userId.value, serviceType)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val orderId = java.util.UUID.randomUUID().toString()
        val order = StopLossOrder(symbol, triggerPrice, volumePercent, false, "PENDING", timestamp)
        database.getReference("$servicePath/stop_loss_orders/$orderId").setValue(order)
    }

    fun updateSurgeTradingSettings(config: SurgeTradingConfig, serviceType: String) {
        val servicePath = getServicePath(_userId.value, serviceType)
        database.getReference("$servicePath/settings/surge_trading").setValue(config)
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}
